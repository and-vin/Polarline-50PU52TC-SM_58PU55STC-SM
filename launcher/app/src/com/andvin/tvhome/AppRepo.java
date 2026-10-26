package com.andvin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Launchable apps plus the user's favorites order and hidden set (SharedPreferences). */
final class AppRepo {
    static final class App {
        final String pkg;
        final ComponentName component;
        final String label;
        final boolean leanback;
        Drawable banner;
        Drawable icon;

        App(String pkg, ComponentName component, String label, boolean leanback) {
            this.pkg = pkg;
            this.component = component;
            this.label = label;
            this.leanback = leanback;
        }
    }

    /** Video apps first on a fresh install. */
    private static final List<String> DEFAULT_FAVORITES = Arrays.asList(
            "ru.kinopoisk.tv", "rtb.mobile.android", "com.vk.tv", "com.google.android.youtube.tv",
            "ru.more.play", "ru.ivi.client");

    /** Never shown as tiles: launchers, setup stubs, factory tools. */
    private static final Set<String> EXCLUDED = new HashSet<>(Arrays.asList(
            "com.andvin.tvhome", "com.cvte.tv.launcher.fall", "com.example.user.myapplication",
            "com.cvte.fac.menu", "com.google.android.tungsten.setupwraith"));

    private final Context ctx;
    private final PackageManager pm;
    private final SharedPreferences prefs;

    AppRepo(Context ctx) {
        this.ctx = ctx;
        this.pm = ctx.getPackageManager();
        this.prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    /** All visible apps keyed by package, leanback activity preferred over phone launcher activity. */
    Map<String, App> loadAll() {
        Map<String, App> map = new LinkedHashMap<>();
        collect(map, "android.intent.category.LEANBACK_LAUNCHER", true);
        collect(map, Intent.CATEGORY_LAUNCHER, false);
        return map;
    }

    private void collect(Map<String, App> map, String category, boolean leanback) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || EXCLUDED.contains(ai.packageName) || map.containsKey(ai.packageName)) continue;
            CharSequence label = ri.loadLabel(pm);
            map.put(ai.packageName, new App(ai.packageName, new ComponentName(ai.packageName, ai.name),
                    TextUtils.isEmpty(label) ? ai.packageName : label.toString(), leanback));
        }
    }

    void loadArt(App a) {
        if (a.banner != null || a.icon != null) return;
        try {
            a.banner = pm.getActivityBanner(a.component);
            if (a.banner == null) a.banner = pm.getApplicationBanner(a.pkg);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        if (a.banner == null) {
            try {
                a.icon = pm.getActivityIcon(a.component);
            } catch (PackageManager.NameNotFoundException e) {
                a.icon = pm.getDefaultActivityIcon();
            }
        }
    }

    List<String> favorites() {
        String s = prefs.getString("favorites", null);
        if (s == null) return new ArrayList<>(DEFAULT_FAVORITES);
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    void setFavorites(List<String> favs) {
        prefs.edit().putString("favorites", TextUtils.join(",", favs)).apply();
    }

    /**
     * Hidden on a fresh install: Wi-Fi Display starts Wi-Fi Direct listening that drops the AltoBeam
     * Wi-Fi link for ~2 minutes; keyboard settings do not belong on the home screen.
     */
    private static final Set<String> DEFAULT_HIDDEN = new HashSet<>(Arrays.asList(
            "com.mediatek.androidbox", "org.liskovsoft.androidtv.rukeyboard"));

    Set<String> hidden() {
        return new HashSet<>(prefs.getStringSet("hidden", DEFAULT_HIDDEN));
    }

    void setHidden(Set<String> hidden) {
        prefs.edit().putStringSet("hidden", new HashSet<>(hidden)).apply();
    }

    /** Favorites in saved order (installed and not hidden). */
    List<App> favoriteApps(Map<String, App> all) {
        Set<String> hidden = hidden();
        List<App> out = new ArrayList<>();
        for (String p : favorites()) {
            App a = all.get(p);
            if (a != null && !hidden.contains(p)) out.add(a);
        }
        return out;
    }

    /** Everything else, alphabetical. */
    List<App> otherApps(Map<String, App> all) {
        Set<String> skip = new HashSet<>(favorites());
        skip.addAll(hidden());
        List<App> out = new ArrayList<>();
        for (App a : all.values()) if (!skip.contains(a.pkg)) out.add(a);
        final Collator c = Collator.getInstance();
        Collections.sort(out, (x, y) -> c.compare(x.label, y.label));
        return out;
    }

    List<App> hiddenApps(Map<String, App> all) {
        List<App> out = new ArrayList<>();
        for (String p : hidden()) {
            App a = all.get(p);
            if (a != null) out.add(a);
        }
        return out;
    }

    /** Remembers a launch from the home screen; most recent first, at most 5. */
    void noteLaunched(String pkg) {
        List<String> r = recent();
        r.remove(pkg);
        r.add(0, pkg);
        while (r.size() > 5) r.remove(r.size() - 1);
        prefs.edit().putString("recent", TextUtils.join(",", r)).apply();
    }

    private List<String> recent() {
        List<String> out = new ArrayList<>();
        for (String p : prefs.getString("recent", "").split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    /** Recently launched apps that are installed and not hidden, most recent first. */
    List<App> recentApps(Map<String, App> all, int max) {
        Set<String> hidden = hidden();
        List<App> out = new ArrayList<>();
        for (String p : recent()) {
            App a = all.get(p);
            if (a != null && !hidden.contains(p)) out.add(a);
            if (out.size() == max) break;
        }
        return out;
    }

    /** Preinstalled apps cannot be removed (the TV has no Settings app to disable them either). */
    boolean isSystem(App a) {
        try {
            return (pm.getApplicationInfo(a.pkg, 0).flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    Intent launchIntent(App a) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(a.pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(a.pkg);
        if (i == null) i = new Intent(Intent.ACTION_MAIN).setComponent(a.component);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }
}
