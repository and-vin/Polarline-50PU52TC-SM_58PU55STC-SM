package com.andvin.tvhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Home screen, top to bottom: inputs capsule and status capsule; favorites on a glass tray; a shelf of
 * large cards (live TV, then the two most recently launched apps); the grid of all other apps followed
 * by the system tiles. No headings: the wallpaper stays visible, as on tvOS-style launchers.
 */
public class HomeActivity extends Activity implements Tile.Listener {
    private static final String TAG = "TvHome";
    private static final int COLS = 5;
    private static final int RECENT = 2;

    private float u;
    private int tileW, tileH, favW, favH, bigW, bigH, gap, side;
    private AppRepo repo;
    private Map<String, AppRepo.App> apps;
    private boolean appsDirty = true;

    private HomeRoot root;
    private ScrollView scroll;
    private Tile live;
    private GlassBar inputsBar;
    private GlassBar statusBar;
    private StatusView status;
    private GlassBar favTray;
    private LinearLayout favRow;
    private LinearLayout shelf;
    private LinearLayout grid;
    private final List<String> shelfPkgs = new ArrayList<>();

    private List<TvBridge.Input> inputs = new ArrayList<>();
    private TvBridge.Input current;
    private boolean goingToTv;
    private boolean previewShown;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            appsDirty = true;
            if (!isFinishing() && hasWindowFocus()) reloadApps();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFormat(PixelFormat.TRANSLUCENT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        u = Math.max(dm.widthPixels, dm.heightPixels) / 1920f;
        // Content width is 1920 - 2 * side = 1752: five grid tiles, three shelf cards, five favorites
        // inside the tray padding.
        side = px(84);
        gap = px(34);
        tileW = px(320);
        tileH = px(180);
        favW = px(296);
        favH = px(166);
        bigW = px(560);
        bigH = px(315);
        repo = new AppRepo(this);
        buildUi();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    private int px(float v) {
        return Math.round(v * u);
    }

    // ---------------------------------------------------------------- layout

    private void buildUi() {
        root = new HomeRoot(this);
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(side, px(36), side, px(90));
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        content.addView(buildTopBar());

        favTray = glassBar();
        favTray.cornerRadius = px(56);
        int tp = px(22);
        favTray.setPadding(tp, tp, tp, tp);
        favTray.setGravity(Gravity.CENTER);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        favRow = row();
        hs.addView(favRow);
        favTray.addView(hs, new LinearLayout.LayoutParams(-2, -2));
        content.addView(favTray, margins(-1, px(34)));

        shelf = row();
        live = new Tile(this, u, bigW, bigH, Tile.LIVE);
        glass(live);
        live.setListener(this);
        live.setCenterText("ТВ", "Нажмите OK, чтобы смотреть");
        live.setLabel("ТВ");
        live.tag = "live";
        shelf.addView(live, gapBefore(0));
        content.addView(shelf, margins(-1, px(40)));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, margins(-1, px(40)));

        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            updatePreview();
            // Glass samples the backdrop at its window position, which changes while scrolling.
            inputsBar.invalidate();
            statusBar.invalidate();
            favTray.invalidate();
            live.invalidate();
            for (int r = 0; r < grid.getChildCount(); r++) {
                ViewGroup g = (ViewGroup) grid.getChildAt(r);
                for (int i = 0; i < g.getChildCount(); i++) g.getChildAt(i).invalidate();
            }
        });
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> onFocusMoved(newF));
        setContentView(root);
    }

    private GlassBar glassBar() {
        GlassBar g = new GlassBar(this, u);
        g.backdrop = root.backdrop;
        g.root = root;
        root.backdrop.addDependent(g);
        return g;
    }

    /** Lets a glass tile sample the backdrop. */
    private void glass(Tile t) {
        t.backdrop = root.backdrop;
        t.root = root;
        root.backdrop.addDependent(t);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        return r;
    }

    private LinearLayout.LayoutParams margins(int w, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, -2);
        lp.topMargin = top;
        return lp;
    }

    /** Gap before every item but the first, so a full row is exactly the content width. */
    private LinearLayout.LayoutParams gapBefore(int index) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        if (index > 0) lp.leftMargin = gap;
        return lp;
    }

    private View buildTopBar() {
        FrameLayout top = new FrameLayout(this);
        top.setClipChildren(false);

        inputsBar = glassBar();
        top.addView(inputsBar, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));

        statusBar = glassBar();
        statusBar.setPadding(px(28), px(8), px(10), px(8));
        status = new StatusView(this, u);
        statusBar.addView(status);
        View settings = statusBar.iconButton(Icons.SETTINGS, "Настройки");
        settings.setOnClickListener(v -> openSettings());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = px(14);
        statusBar.addView(settings, lp);
        top.addView(statusBar, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        return top;
    }

    private List<View> systemTiles() {
        List<View> out = new ArrayList<>();
        out.add(systemTile("Настройки", "settings", Icons.SETTINGS));
        out.add(systemTile("Bluetooth", "bluetooth", Icons.BLUETOOTH));
        out.add(systemTile("Клавиатура", "keyboard", Icons.KEYBOARD));
        out.add(systemTile("Скрытые", "hidden", Icons.HIDDEN));
        out.add(systemTile("Родной лаунчер", "stock", Icons.TV));
        return out;
    }

    private Tile systemTile(String title, String action, int icon) {
        Tile t = new Tile(this, u, tileW, tileH, Tile.GLASS);
        glass(t);
        t.tag = action;
        t.setLabel(title);
        t.setCenterText(title, null);
        t.setIcon(icon);
        t.setListener(this);
        return t;
    }

    private void onFocusMoved(View v) {
        // The wallpaper takes the colour of the focused app; anything else shows it bare.
        if (v instanceof Tile && ((Tile) v).kind == Tile.APP) root.backdrop.setTarget(((Tile) v).getAmbient());
        else root.backdrop.setTarget(null);
    }

    // ---------------------------------------------------------------- apps

    private void reloadApps() {
        appsDirty = false;
        View focused = getCurrentFocus();
        String focusedPkg = focused instanceof Tile && ((Tile) focused).tag instanceof AppRepo.App
                ? ((AppRepo.App) ((Tile) focused).tag).pkg : null;
        ViewGroup focusedIn = focused != null && focused.getParent() instanceof ViewGroup ? (ViewGroup) focused.getParent() : null;
        apps = repo.loadAll();

        favRow.removeAllViews();
        List<AppRepo.App> favs = repo.favoriteApps(apps);
        for (AppRepo.App a : favs) favRow.addView(appTile(a, favW, favH), gapBefore(favRow.getChildCount()));
        favTray.setVisibility(favs.isEmpty() ? View.GONE : View.VISIBLE);

        shelfPkgs.clear();
        buildShelf();

        grid.removeAllViews();
        List<View> cells = new ArrayList<>();
        for (AppRepo.App a : repo.otherApps(apps)) cells.add(appTile(a, tileW, tileH));
        cells.addAll(systemTiles());
        LinearLayout r = null;
        for (int i = 0; i < cells.size(); i++) {
            if (i % COLS == 0) {
                r = row();
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.bottomMargin = gap;
                grid.addView(r, lp);
            }
            r.addView(cells.get(i), gapBefore(i % COLS));
        }

        View target = null;
        if (focusedPkg != null) {
            target = focusedIn == shelf ? findIn(shelf, focusedPkg) : null;
            if (target == null) target = findTile(focusedPkg);
        }
        if (target == null && (focused == null || focused.getParent() == null || !focused.isAttachedToWindow()))
            target = favRow.getChildCount() > 0 ? favRow.getChildAt(0) : live;
        if (target != null) target.requestFocus();
    }

    /**
     * Live TV card stays; after it the most recently launched apps, topped up with favorites while the
     * history is short. Rebuilt only when the list changed.
     */
    private void buildShelf() {
        if (apps == null) return;
        List<AppRepo.App> recent = repo.recentApps(apps, RECENT);
        for (AppRepo.App f : repo.favoriteApps(apps)) {
            if (recent.size() >= RECENT) break;
            if (!recent.contains(f)) recent.add(f);
        }
        List<String> pkgs = new ArrayList<>();
        for (AppRepo.App a : recent) pkgs.add(a.pkg);
        if (pkgs.equals(shelfPkgs)) return;
        boolean hadFocus = shelf.hasFocus() && !live.isFocused();
        shelf.removeViews(1, shelf.getChildCount() - 1);
        for (AppRepo.App a : recent) shelf.addView(appTile(a, bigW, bigH), gapBefore(shelf.getChildCount()));
        shelfPkgs.clear();
        shelfPkgs.addAll(pkgs);
        if (hadFocus) (shelf.getChildCount() > 1 ? shelf.getChildAt(1) : live).requestFocus();
    }

    private Tile appTile(AppRepo.App a, int w, int h) {
        repo.loadArt(a);
        Tile t = new Tile(this, u, w, h, Tile.APP);
        t.tag = a;
        t.setLabel(a.label);
        if (a.banner != null) t.setArt(a.banner, false);
        else t.setArt(a.icon, true);
        t.setListener(this);
        return t;
    }

    private View findTile(String pkg) {
        View v = findIn(favRow, pkg);
        for (int r = 0; v == null && r < grid.getChildCount(); r++) v = findIn((ViewGroup) grid.getChildAt(r), pkg);
        return v != null ? v : findIn(shelf, pkg);
    }

    private static View findIn(ViewGroup g, String pkg) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof Tile && ((Tile) v).tag instanceof AppRepo.App && ((AppRepo.App) ((Tile) v).tag).pkg.equals(pkg)) return v;
        }
        return null;
    }

    @Override
    public void onClick(Tile t) {
        if ("live".equals(t.tag)) {
            watch(current != null ? current : lastWatched());
        } else if ("settings".equals(t.tag)) {
            openSettings();
        } else if ("bluetooth".equals(t.tag)) {
            start(new Intent().setComponent(new ComponentName("com.cvte.tv.androidsetting",
                    "com.cvte.tv.androidsetting.bluetooth.activity.BluetoothActivity")), "Bluetooth недоступен");
        } else if ("keyboard".equals(t.tag)) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        } else if ("hidden".equals(t.tag)) {
            showHiddenMenu();
        } else if ("stock".equals(t.tag)) {
            // The stock CVTE launcher as a normal app, just in case; Home brings this launcher back.
            start(new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName("com.cvte.tv.launcher.fall",
                    "com.cvte.tv.launcher.NebulaFallActivity")), "Родной лаунчер недоступен");
        } else if (t.tag instanceof AppRepo.App) {
            AppRepo.App a = (AppRepo.App) t.tag;
            repo.noteLaunched(a.pkg);
            start(repo.launchIntent(a), "Не удалось открыть");
        }
    }

    private void start(Intent i, String error) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.w(TAG, "start failed: " + i, e);
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onMenu(Tile t) {
        if (!(t.tag instanceof AppRepo.App)) return;
        final AppRepo.App a = (AppRepo.App) t.tag;
        final List<String> favs = repo.favorites();
        final boolean isFav = favs.contains(a.pkg);
        List<String> items = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        items.add("Открыть");
        actions.add(() -> onClick(t));
        if (isFav) {
            final int idx = favs.indexOf(a.pkg);
            if (idx > 0) {
                items.add("Сдвинуть влево");
                actions.add(() -> move(favs, idx, -1, a.pkg));
            }
            if (idx < favs.size() - 1) {
                items.add("Сдвинуть вправо");
                actions.add(() -> move(favs, idx, 1, a.pkg));
            }
            items.add("Убрать из избранного");
            actions.add(() -> {
                favs.remove(a.pkg);
                repo.setFavorites(favs);
                reloadApps();
            });
        } else {
            items.add("Добавить в избранное");
            actions.add(() -> {
                favs.add(a.pkg);
                repo.setFavorites(favs);
                reloadApps();
            });
        }
        items.add("Скрыть");
        actions.add(() -> {
            Set<String> h = repo.hidden();
            h.add(a.pkg);
            repo.setHidden(h);
            reloadApps();
        });
        if (!repo.isSystem(a)) {
            items.add("Удалить");
            actions.add(() -> start(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)), "Не удалось удалить"));
        }
        GlassMenu.show(this, u, a.label, items, i -> actions.get(i).run());
    }

    private void move(List<String> favs, int idx, int dir, String pkg) {
        int to = idx + dir;
        if (to < 0 || to >= favs.size()) return;
        favs.remove(idx);
        favs.add(to, pkg);
        repo.setFavorites(favs);
        reloadApps();
        View v = findIn(favRow, pkg);
        if (v != null) v.requestFocus();
    }

    private void showHiddenMenu() {
        final List<AppRepo.App> hidden = repo.hiddenApps(apps);
        if (hidden.isEmpty()) {
            Toast.makeText(this, "Скрытых приложений нет", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> names = new ArrayList<>();
        for (AppRepo.App a : hidden) names.add(a.label);
        GlassMenu.show(this, u, "Показать снова", names, w -> {
            Set<String> h = repo.hidden();
            h.remove(hidden.get(w).pkg);
            repo.setHidden(h);
            reloadApps();
        });
    }

    private void openSettings() {
        Intent[] tries = {
                new Intent(Settings.ACTION_SETTINGS),
                getPackageManager().getLaunchIntentForPackage("com.cvte.tv.androidsetting"),
        };
        for (Intent i : tries) {
            if (i == null) continue;
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(this, "Настройки недоступны", Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- TV inputs

    private void refreshInputs() {
        TvBridge tv = TvBridge.get();
        if (tv == null) return;
        tv.loadInputs((list, cur) -> {
            inputs = list;
            current = cur;
            View focused = getCurrentFocus();
            boolean hadFocus = focused != null && focused.getParent() == inputsBar;
            int focusedIndex = hadFocus ? inputsBar.indexOfChild(focused) : -1;
            inputsBar.removeAllViews();
            for (final TvBridge.Input in : list) {
                TextView s = inputsBar.segment(in.title);
                s.setOnClickListener(v -> watch(in));
                inputsBar.addView(s);
            }
            if (focusedIndex >= 0 && focusedIndex < inputsBar.getChildCount()) inputsBar.getChildAt(focusedIndex).requestFocus();
            // Android apps are the current source after watching something in them: offer the last
            // watched input instead (the preview itself only runs for a real TV/HDMI source).
            TvBridge.Input shown = cur != null ? cur : lastWatched();
            if (shown != null) {
                live.setLabel(shown.title);
                live.setCenterText(shown.title, shown.connected || shown.tv ? "Нажмите OK, чтобы смотреть" : "Нет сигнала");
            }
            updatePreview();
        });
    }

    private TvBridge.Input lastWatched() {
        int id = getSharedPreferences("home", MODE_PRIVATE).getInt("lastInput", -1);
        for (TvBridge.Input i : inputs) if (i.id == id) return i;
        return firstTv();
    }

    private TvBridge.Input firstTv() {
        for (TvBridge.Input i : inputs) if (i.tv) return i;
        return inputs.isEmpty() ? null : inputs.get(0);
    }

    private TvBridge.Input hdmi(int n) {
        for (TvBridge.Input i : inputs) if (i.title.equals("HDMI " + n)) return i;
        return null;
    }

    private void watch(TvBridge.Input in) {
        TvBridge tv = TvBridge.get();
        goingToTv = true;
        if (in != null) getSharedPreferences("home", MODE_PRIVATE).edit().putInt("lastInput", in.id).apply();
        if (tv != null && tv.ready()) {
            tv.watch(this, in != null ? in.id : -1);
        } else {
            TvBridge.openTvPlayer(this);
        }
    }

    /** Puts the live TV picture behind the live card while it is fully on screen. */
    private void updatePreview() {
        TvBridge tv = TvBridge.get();
        int[] rl = new int[2], tl = new int[2];
        root.getLocationOnScreen(rl);
        live.getLocationOnScreen(tl);
        boolean onScreen = tl[1] >= rl[1] && tl[1] + live.cardH <= rl[1] + root.getHeight();
        boolean want = hasWindowFocus() && current != null && (current.tv || current.connected)
                && onScreen && live.getWidth() > 0 && tv != null && tv.ready();
        if (want) {
            RectF hole = new RectF(tl[0] - rl[0], tl[1] - rl[1], tl[0] - rl[0] + live.cardW, tl[1] - rl[1] + live.cardH);
            root.setHole(hole, live.radius);
            live.setLive(true);
            tv.preview(new Rect(tl[0], tl[1], tl[0] + live.cardW, tl[1] + live.cardH));
            previewShown = true;
        } else if (previewShown) {
            hidePreview();
        }
    }

    private void hidePreview() {
        root.setHole(null, 0);
        live.setLive(false);
        TvBridge tv = TvBridge.get();
        if (tv != null) tv.hidePreview();
        previewShown = false;
    }

    // ---------------------------------------------------------------- lifecycle & keys

    @Override
    protected void onResume() {
        super.onResume();
        goingToTv = false;
        // Delayed: right after Home the stock launcher is still stopping and not yet killable.
        root.postDelayed(this::unloadStockLauncher, 3000);
        root.backdrop.loadWallpaper(this);
        if (appsDirty || apps == null) reloadApps();
        else buildShelf();
        refreshInputs();
        status.refresh();
        onFocusMoved(getCurrentFocus());
    }

    /**
     * The stock launcher is not running normally; after the "Родной лаунчер" tile or the service
     * menu it stays cached at ~50 MB. Only kills it while it is in the background.
     */
    private void unloadStockLauncher() {
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am != null) am.killBackgroundProcesses("com.cvte.tv.launcher.fall");
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) {
            if (appsDirty) reloadApps();
            root.post(this::updatePreview);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!goingToTv && previewShown) hidePreview();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        scroll.smoothScrollTo(0, 0);
        View first = favRow.getChildCount() > 0 ? favRow.getChildAt(0) : live;
        first.requestFocus();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                if (scroll.getScrollY() > 0) onNewIntent(getIntent());
                return true;
            case KeyEvent.KEYCODE_TV_INPUT:
                showInputsMenu();
                return true;
            case KeyEvent.KEYCODE_TV:
                watch(firstTv());
                return true;
            case KeyEvent.KEYCODE_TV_INPUT_HDMI_1:
            case KeyEvent.KEYCODE_TV_INPUT_HDMI_2:
            case KeyEvent.KEYCODE_TV_INPUT_HDMI_3:
            case KeyEvent.KEYCODE_TV_INPUT_HDMI_4: {
                TvBridge.Input in = hdmi(keyCode - KeyEvent.KEYCODE_TV_INPUT_HDMI_1 + 1);
                if (in != null) watch(in);
                return true;
            }
            case KeyEvent.KEYCODE_SETTINGS:
                openSettings();
                return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    private void showInputsMenu() {
        if (inputs.isEmpty()) {
            TvBridge.openTvPlayer(this);
            return;
        }
        List<String> names = new ArrayList<>();
        for (TvBridge.Input i : inputs) names.add(i.title);
        GlassMenu.show(this, u, "Источник", names, w -> watch(inputs.get(w)));
    }
}
