package com.andvin.tvagent;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Minimal adb client for the TV's own adbd (127.0.0.1:5555, enabled by the firmware). An app cannot
 * inject key presses into other apps; the shell user can, so the agent runs `input ...` through adb.
 *
 * The first connection shows "Allow USB debugging?" on the TV for this app's key; choose "Always allow".
 */
final class AdbShell {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAX_DATA = 4096;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    /** DER prefix of a SHA-1 DigestInfo: adbd checks the signature as RSA PKCS#1 v1.5 over SHA-1. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02,
            0x1a, 0x05, 0x00, 0x04, 0x14};

    private final KeyPair keys;

    AdbShell(Context context) throws Exception {
        keys = loadOrCreateKeys(context.getFilesDir());
    }

    /** Runs a shell command and returns its output. `authTimeoutMs` covers waiting for the user's approval. */
    synchronized String run(String command, int authTimeoutMs) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 3_000);
            s.setSoTimeout(authTimeoutMs);
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            send(out, CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean sentSignature = false;
            while (true) {
                Msg m = read(in);
                if (m.cmd == CNXN) break;
                if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected adb message " + m.cmd);
                if (!sentSignature) {
                    send(out, AUTH, AUTH_SIGNATURE, 0, sign(m.data));
                    sentSignature = true;
                } else {
                    // Key not known yet: offer it; adbd shows the approval dialog and answers with CNXN.
                    send(out, AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob());
                }
            }
            s.setSoTimeout(15_000);
            send(out, OPEN, 1, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            while (true) {
                Msg m = read(in);
                if (m.cmd == WRTE) {
                    result.write(m.data);
                    send(out, OKAY, 1, m.arg0, new byte[0]);
                } else if (m.cmd == CLSE) {
                    send(out, CLSE, 1, m.arg0, new byte[0]);
                    break;
                }
            }
            return result.toString("UTF-8");
        }
    }

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private static Msg read(DataInputStream in) throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte x : data) sum += x & 0xff;
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        out.write(b.array());
        out.flush();
    }

    private byte[] sign(byte[] token) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, keys.getPrivate());
        c.update(SHA1_PREFIX);
        return c.doFinal(token);
    }

    /** The key in adb's android_pubkey format, base64, plus a name. */
    private byte[] publicKeyBlob() {
        RSAPublicKey pub = (RSAPublicKey) keys.getPublic();
        BigInteger n = pub.getModulus();
        int words = 2048 / 32;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        long n0inv = r32.subtract(n.mod(r32).modInverse(r32)).longValue();
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt((int) n0inv);
        putWords(b, n, words);
        putWords(b, rr, words);
        b.putInt(pub.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " tvagent@tv\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void putWords(ByteBuffer b, BigInteger v, int words) {
        BigInteger mask = BigInteger.valueOf(0xffffffffL);
        for (int i = 0; i < words; i++) b.putInt(v.shiftRight(32 * i).and(mask).intValue());
    }

    private static KeyPair loadOrCreateKeys(File dir) throws Exception {
        File priv = new File(dir, "adbkey"), pub = new File(dir, "adbkey.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            PrivateKey p = kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv)));
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(readAll(pub))), p);
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        writeAll(priv, kp.getPrivate().getEncoded());
        writeAll(pub, kp.getPublic().getEncoded());
        return kp;
    }

    private static byte[] readAll(File f) throws IOException {
        byte[] b = new byte[(int) f.length()];
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            in.readFully(b);
        }
        return b;
    }

    private static void writeAll(File f, byte[] b) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(b);
        }
    }
}
