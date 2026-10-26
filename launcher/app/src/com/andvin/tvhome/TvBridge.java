package com.andvin.tvhome;

import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.cvte.tv.api.TvApiSDKManager;
import com.cvte.tv.api.TvServiceConnection;
import com.cvte.tv.api.aidl.EntityInputSource;
import com.cvte.tv.api.aidl.ITVApiScreenWindowAidl;
import com.cvte.tv.api.aidl.ITVApiSystemInputSourceAidl;
import com.cvte.tv.api.aidl.ITvApiManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Talks to the CVTE TV service (com.cvte.tv.api.impl) the same way the stock launcher does.
 * The SDK classes live on the TV's BOOTCLASSPATH. Every call is off the main thread and guarded:
 * if the service is missing or refuses us, the launcher keeps working without TV inputs.
 */
final class TvBridge {
    private static final String TAG = "TvHome.Tv";

    static final class Input {
        final int id;
        final String title;
        final boolean tv;
        final boolean connected;

        Input(int id, String title, boolean tv, boolean connected) {
            this.id = id;
            this.title = title;
            this.tv = tv;
            this.connected = connected;
        }
    }

    interface InputsCallback {
        void onInputs(List<Input> inputs, Input current);
    }

    private static TvBridge sInstance;

    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile ITVApiSystemInputSourceAidl sources;
    private volatile ITVApiScreenWindowAidl window;
    private final List<Runnable> pending = new ArrayList<>();

    static TvBridge get() {
        return sInstance;
    }

    static void init(Application app) {
        sInstance = new TvBridge();
        sInstance.connect(app);
    }

    private TvBridge() {
        HandlerThread t = new HandlerThread("tv-bridge");
        t.start();
        worker = new Handler(t.getLooper());
    }

    // The SDK keeps listeners as WeakReferences: hold ours strongly or it is collected before the callback.
    private final TvServiceConnection connection = new TvServiceConnection() {
        @Override
        public void onServiceConnected(final ITvApiManager manager) {
            worker.post(() -> onConnected(manager));
        }

        @Override
        public void onServiceDisconnected() {
            sources = null;
            window = null;
        }
    };

    private void connect(Application app) {
        try {
            TvApiSDKManager.init(app);
            TvApiSDKManager.getInstance().getTvApi(connection);
        } catch (Throwable t) {
            Log.w(TAG, "TV API unavailable", t);
        }
    }

    private void onConnected(ITvApiManager manager) {
        try {
            sources = manager.getTVApiSystemInputSource();
            window = manager.getTVApiScreenWindow();
            Log.i(TAG, "TV API connected");
        } catch (Throwable t) {
            Log.w(TAG, "TV API connect failed", t);
            return;
        }
        List<Runnable> run;
        synchronized (pending) {
            run = new ArrayList<>(pending);
            pending.clear();
        }
        for (Runnable r : run) r.run();
    }

    boolean ready() {
        return sources != null;
    }

    /** Runs now if connected, otherwise once the service connects. Always on the worker thread. */
    private void whenReady(Runnable r) {
        worker.post(() -> {
            if (sources != null) {
                r.run();
            } else {
                synchronized (pending) {
                    pending.add(r);
                }
            }
        });
    }

    void loadInputs(InputsCallback cb) {
        whenReady(() -> {
            List<Input> list = new ArrayList<>();
            Input current = null;
            try {
                EntityInputSource cur = sources.eventSystemInputSourceGetInputSource();
                List<EntityInputSource> all = sources.eventSystemInputSourceGetList();
                boolean tvAdded = false;
                if (all != null) {
                    for (EntityInputSource e : all) {
                        String cat = String.valueOf(e.category);
                        String status = String.valueOf(e.status);
                        if (status.contains("HIDE") || cat.contains("APP")) continue;
                        boolean tv = isTvCategory(cat);
                        // One "ТВ" button for all tuners; the TV app remembers which one was used.
                        if (tv && tvAdded && (cur == null || cur.id != e.id)) continue;
                        Input in = new Input(e.id, title(e, cat), tv, !status.contains("DISCONNECT"));
                        if (tv) tvAdded = true;
                        list.add(in);
                        if (cur != null && cur.id == e.id) current = in;
                    }
                }
                if (current == null && cur != null) {
                    String cat = String.valueOf(cur.category);
                    if (!cat.contains("APP")) current = new Input(cur.id, title(cur, cat), isTvCategory(cat), true);
                }
            } catch (Throwable t) {
                Log.w(TAG, "loadInputs failed", t);
            }
            // ТВ, then HDMI in order, then rarely used AV/VGA/etc.
            java.util.Collections.sort(list, (a, b) -> Integer.compare(rank(a), rank(b)));
            final Input c = current;
            main.post(() -> cb.onInputs(list, c));
        });
    }

    private static int rank(Input in) {
        if (in.tv) return 0;
        if (in.title.startsWith("HDMI")) {
            String d = in.title.replaceAll("[^0-9]", "");
            return 10 + (d.isEmpty() ? 0 : Integer.parseInt(d));
        }
        return 100;
    }

    private static boolean isTvCategory(String cat) {
        return cat.contains("_TV") || cat.contains("ATV") || cat.contains("DVB")
                || cat.contains("DTMB") || cat.contains("ATSC") || cat.contains("ISDB");
    }

    private static String title(EntityInputSource e, String cat) {
        if (isTvCategory(cat)) return "ТВ";
        String digits = e.name == null ? "" : e.name.replaceAll("[^0-9]", "");
        String base;
        if (cat.contains("HDMI")) base = "HDMI";
        else if (cat.contains("YPBPR")) base = "YPbPr";
        else if (cat.contains("PC")) base = "VGA";
        else if (cat.contains("SCART")) base = "SCART";
        else if (cat.contains("AV")) base = "AV";
        else base = e.name != null ? e.name : cat;
        return digits.isEmpty() ? base : base + " " + digits;
    }

    /** Switches the TV to an input and opens the full-screen TV player, like the stock launcher. */
    void watch(final Context ctx, final int inputId) {
        whenReady(() -> {
            try {
                if (inputId >= 0) sources.eventSystemInputSourceSetInputSource(inputId);
                window.eventScreenWindowSetFull();
            } catch (Throwable t) {
                Log.w(TAG, "watch failed", t);
            }
            main.post(() -> openTvPlayer(ctx));
        });
    }

    static void openTvPlayer(Context ctx) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory("android.intent.category.TV_HOME");
        i.putExtra("isLauncherGoToTv", true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "no TV player", e);
        }
    }

    /** Shows the current input's picture in a screen rectangle (the window must be transparent there). */
    void preview(final Rect r) {
        whenReady(() -> {
            try {
                window.eventScreenWindowSetPipValue(r.left, r.top, r.width(), r.height());
                window.eventScreenWindowMute(false);
            } catch (Throwable t) {
                Log.w(TAG, "preview failed", t);
            }
        });
    }

    /** Hides the input picture (and its sound) while an app or the launcher without preview is shown. */
    void hidePreview() {
        if (window == null) return;
        worker.post(() -> {
            try {
                window.eventScreenWindowMute(true);
            } catch (Throwable t) {
                Log.w(TAG, "hidePreview failed", t);
            }
        });
    }
}
