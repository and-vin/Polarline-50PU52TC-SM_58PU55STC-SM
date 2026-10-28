package com.andvin.tvagent;

import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Keeps an outbound long-poll to the command queue and runs the commands it gets.
 *
 * Protocol (the cloud function in step 6 implements the same):
 *   GET  {url}/tv/poll        Authorization: Bearer {secret}  -> 200 command JSON, or 204 after ~25 s
 *   POST {url}/tv/ack         {"id": ..., "ok": true|false, "error": "..."}
 * Commands: {"id", "app": "rutube|vk|kinopoisk", "action": "open|search", "uri": "..."},
 *           {"id", "app": "kinopoisk", "action": "search_keys", "query": "..."}, {"id", "action": "ping"}.
 * Key presses (КиноПоиск account prompt and search) go through the TV's own adb, see AdbShell.
 *
 * Configure once over adb (kept in shared preferences):
 *   am startservice -n com.andvin.tvagent/.AgentService --es url https://... --es secret ...
 */
public class AgentService extends Service {
    private static final String TAG = "TvAgent";
    private static final int POLL_READ_TIMEOUT_MS = 40_000;

    private static final String KP = "ru.kinopoisk.tv";
    private static final String KP_SPLASH = KP + ".presentation.splash.SplashActivity";
    private static final String KP_HOME = KP + ".hd.presentation.home.HomeActivity";
    private static final String KP_ACCOUNT = "SelectFamilyAccountActivity";
    private static final String KP_CARD = "HdContentCardActivity";
    /** КиноПоиск's own on-screen keyboard (Russian layout); focus starts on "й" when search opens. */
    private static final String[] KP_KEYS = {"йцукенгшщзх", "фывапролджэ", "ячсмитьбю \b"};

    private Thread worker;
    private AdbShell adb;
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
                String error;
                try {
                    error = execute(cmd);
                } catch (Exception e) {
                    error = e.toString();
                }
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

    private String execute(JSONObject cmd) throws Exception {
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
                if (KP.equals(pkg)) {
                    // КиноПоиск may ask "Choose an account" first; a film link survives the choice.
                    String focus = waitFocus(new String[]{KP_ACCOUNT, KP_CARD, KP_HOME}, 30_000);
                    if (focus.contains(KP_ACCOUNT)) shell("input keyevent 23");
                }
                return null;
            }
            case "search_keys": {
                String query = cmd.optString("query", "").trim();
                if (!KP.equals(pkg) || query.isEmpty()) return "search_keys needs app=kinopoisk and query";
                wakeScreen();
                return kinopoiskSearch(query);
            }
            case "ping":
                return null;
            default:
                return "unsupported action " + action;
        }
    }

    /**
     * Opens КиноПоиск's search screen and types the query: restart the app to its home screen, confirm the
     * account if asked, side menu -> Search, then the text. Stops on the results; picking one is up to the user.
     */
    private String kinopoiskSearch(String query) throws Exception {
        startActivity(new Intent().setClassName(KP, KP_SPLASH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        String focus = waitFocus(new String[]{KP_ACCOUNT, KP_HOME}, 40_000);
        if (focus.contains(KP_ACCOUNT)) {
            shell("input keyevent 23");
            focus = waitFocus(new String[]{KP_HOME}, 20_000);
        }
        if (!focus.contains(KP_HOME)) return "КиноПоиск did not reach its home screen: " + focus.trim();
        Thread.sleep(3_000);   // the home screen keeps loading after it gets focus
        shell("input keyevent 21 19 23");   // left into the side menu, up to Search, OK
        Thread.sleep(2_000);
        if (query.matches("[A-Za-z0-9 ]+")) {
            shell("input text " + query.replace(" ", "%s"));
            return null;
        }
        String keys = kinopoiskKeyPresses(query);
        if (keys == null) return "cannot type " + query + " on the КиноПоиск keyboard";
        shell("input keyevent " + keys);
        return null;
    }

    /** D-pad presses that type `text` on КиноПоиск's Russian keyboard, starting from "й". */
    static String kinopoiskKeyPresses(String text) {
        StringBuilder out = new StringBuilder();
        int row = 0, col = 0;
        for (char ch : text.toLowerCase().replace('ё', 'е').replace('ъ', 'ь').toCharArray()) {
            int r = -1, c = -1;
            for (int i = 0; i < KP_KEYS.length && r < 0; i++) {
                int j = KP_KEYS[i].indexOf(ch);
                if (j >= 0) { r = i; c = j; }
            }
            if (r < 0) {
                if (Character.isLetterOrDigit(ch)) return null;
                continue;   // punctuation is dropped: "Кин-дза-дза!" -> "кинДзаДза" still finds the film
            }
            for (; row < r; row++) out.append("20 ");
            for (; row > r; row--) out.append("19 ");
            for (; col < c; col++) out.append("22 ");
            for (; col > c; col--) out.append("21 ");
            out.append("23 ");
        }
        return out.toString().trim();
    }

    private String shell(String command) throws Exception {
        if (adb == null) adb = new AdbShell(this);
        // The first call may wait for "Allow USB debugging" on the TV.
        return adb.run(command, 60_000);
    }

    /** Polls the focused window until it contains one of `names`; returns the last focus line. */
    private String waitFocus(String[] names, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        String focus = "";
        while (System.currentTimeMillis() < end) {
            focus = shell("dumpsys window windows | grep mCurrentFocus");
            for (String n : names) if (focus.contains(n)) return focus;
            Thread.sleep(1_000);
        }
        return focus;
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
