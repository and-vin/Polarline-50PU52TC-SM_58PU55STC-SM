package com.andvin.tvagent;

import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Keeps an outbound long-poll to the command queue and runs the commands it gets.
 *
 * Protocol (the cloud function in step 6 implements the same):
 *   GET  {url}/tv/poll        Authorization: Bearer {secret}  -> 200 command JSON, or 204 after ~25 s
 *   POST {url}/tv/ack         {"id": ..., "ok": true|false, "error": "..."}
 * Commands: {"id", "app": "rutube|vk|kinopoisk", "action": "open|search", "uri": "..."}.
 *
 * Configure once over adb (kept in shared preferences):
 *   am startservice -n com.andvin.tvagent/.AgentService --es url https://... --es secret ...
 */
public class AgentService extends Service {
    private static final String TAG = "TvAgent";
    private static final int POLL_READ_TIMEOUT_MS = 40_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Thread worker;
    private volatile boolean running;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("url")) {
            prefs().edit()
                    .putString("url", trimSlash(intent.getStringExtra("url")))
                    .putString("secret", intent.getStringExtra("secret"))
                    .apply();
            Log.i(TAG, "configured: " + prefs().getString("url", ""));
        }
        if (worker == null || !worker.isAlive()) {
            running = true;
            worker = new Thread(this::loop, "tvagent-poll");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("agent", Context.MODE_PRIVATE);
    }

    private static String trimSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private void loop() {
        long backoff = 2_000;
        while (running) {
            String url = prefs().getString("url", null);
            String secret = prefs().getString("secret", "");
            if (url == null) {
                sleep(30_000);
                continue;
            }
            try {
                String body = http("GET", url + "/tv/poll", secret, null);
                backoff = 2_000;
                if (body == null) continue;   // 204: nothing queued
                JSONObject cmd = new JSONObject(body);
                String error = runOnMain(cmd);
                JSONObject ack = new JSONObject()
                        .put("id", cmd.optString("id"))
                        .put("ok", error == null)
                        .put("error", error == null ? JSONObject.NULL : error);
                http("POST", url + "/tv/ack", secret, ack.toString());
            } catch (Exception e) {
                // Wi-Fi drops, the queue being down, a bad command: wait and retry.
                Log.w(TAG, "poll: " + e);
                sleep(backoff);
                backoff = Math.min(backoff * 2, 60_000);
            }
        }
    }

    /** Runs the command on the main thread and returns an error text, or null on success. */
    private String runOnMain(JSONObject cmd) throws InterruptedException {
        final String[] error = {"timeout"};
        CountDownLatch done = new CountDownLatch(1);
        main.post(() -> {
            try {
                error[0] = execute(cmd);
            } catch (Exception e) {
                error[0] = e.toString();
            }
            done.countDown();
        });
        done.await(10, TimeUnit.SECONDS);
        return error[0];
    }

    private String execute(JSONObject cmd) {
        String action = cmd.optString("action");
        String pkg = packageOf(cmd.optString("app"));
        Log.i(TAG, "command: " + cmd);
        switch (action) {
            case "open":
            case "search": {
                String uri = cmd.optString("uri", null);
                if (uri == null || pkg == null) return "need app and uri";
                wakeScreen();
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                        .setPackage(pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(i);
                } catch (ActivityNotFoundException e) {
                    return "app does not accept " + uri;
                }
                return null;
            }
            case "ping":
                return null;
            default:
                return "unsupported action " + action;
        }
    }

    private static String packageOf(String app) {
        switch (app) {
            case "rutube": return "rtb.mobile.android";
            case "vk": return "com.vk.tv";
            case "kinopoisk": return "ru.kinopoisk.tv";
            default: return null;
        }
    }

    @SuppressWarnings("deprecation")
    private void wakeScreen() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isInteractive()) return;
        PowerManager.WakeLock wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG);
        wl.acquire(5_000);
    }

    /** Returns the body for 200, null for 204; throws on anything else. */
    private static String http(String method, String url, String secret, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(POLL_READ_TIMEOUT_MS);
            c.setRequestProperty("Authorization", "Bearer " + secret);
            if (json != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream o = c.getOutputStream()) {
                    o.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code == 204) return null;
            if (code != 200) throw new IllegalStateException(method + " " + url + " -> " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
