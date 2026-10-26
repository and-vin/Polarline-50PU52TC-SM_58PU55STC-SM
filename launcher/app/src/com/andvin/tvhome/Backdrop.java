package com.andvin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

/**
 * Ambient background: the wallpaper of the day (assets/wallpapers, one per day) with the focused
 * app's colour field washed over it. The field is a tiny (32x18) bitmap upscaled with bilinear
 * filtering, which reads as a heavy blur for almost no cost. Glass panels sample a tiny copy of the
 * wallpaper plus the same wash, so they look like frosted glass over it.
 */
final class Backdrop {
    static final int W = 32, H = 18;
    private static final long FADE_MS = 420;
    /** Strength of the app colour wash over the wallpaper. */
    private static final int WASH = 96;

    private Bitmap wall, wallSmall;
    private int wallIndex = -1;
    /** App colour fields; null means no wash (home). */
    private Bitmap from, to;
    private boolean fading;
    private long fadeStart;
    private final List<View> dependents = new ArrayList<>();
    private final Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint wash = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix m = new Matrix();
    private View host;

    Backdrop() {
        wall = defaultField();
        wallSmall = wall;
        ColorMatrix pm = new ColorMatrix();
        pm.setSaturation(1.1f);
        pm.postConcat(new ColorMatrix(new float[]{
                0.95f, 0, 0, 0, 0,
                0, 0.95f, 0, 0, 0,
                0, 0, 0.97f, 0, 0,
                0, 0, 0, 1, 0}));
        photo.setColorFilter(new ColorMatrixColorFilter(pm));
        ColorMatrix wm = new ColorMatrix();
        wm.setSaturation(1.35f);
        wash.setColorFilter(new ColorMatrixColorFilter(wm));
        ColorMatrix lift = new ColorMatrix();
        lift.setSaturation(1.2f);
        lift.postConcat(new ColorMatrix(new float[]{
                0.72f, 0, 0, 0, 22,
                0, 0.72f, 0, 0, 22,
                0, 0, 0.74f, 0, 28,
                0, 0, 0, 1, 0}));
        glass.setColorFilter(new ColorMatrixColorFilter(lift));
    }

    void attach(View host) {
        this.host = host;
    }

    /** Views whose drawing samples the backdrop; they are invalidated while a fade runs. */
    void addDependent(View v) {
        dependents.add(v);
    }

    /** Loads the wallpaper of the day; a no-op when it is already shown. */
    void loadWallpaper(Context c) {
        try {
            String[] names = c.getAssets().list("wallpapers");
            List<String> jpgs = new ArrayList<>();
            if (names != null) for (String n : names) if (n.endsWith(".jpg")) jpgs.add(n);
            if (jpgs.isEmpty()) return;
            String[] sorted = jpgs.toArray(new String[0]);
            Arrays.sort(sorted);
            int idx = Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % sorted.length;
            if (idx == wallIndex) return;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b;
            try (InputStream in = c.getAssets().open("wallpapers/" + sorted[idx])) {
                b = BitmapFactory.decodeStream(in, null, o);
            }
            if (b == null) return;
            wall = b;
            wallSmall = shrink(b, 48);
            wallIndex = idx;
            invalidateAll();
        } catch (Exception e) {
            Log.w("TvHome", "wallpaper", e);
        }
    }

    /** Fades the app colour wash to field (null: back to the bare wallpaper). */
    void setTarget(Bitmap field) {
        if (field == to) return;
        from = to;
        to = field;
        fading = true;
        fadeStart = SystemClock.uptimeMillis();
        tick();
    }

    private float progress() {
        if (!fading) return 1f;
        float t = (SystemClock.uptimeMillis() - fadeStart) / (float) FADE_MS;
        if (t >= 1f) {
            fading = false;
            from = null;
            return 1f;
        }
        // ease-in-out
        return t * t * (3 - 2 * t);
    }

    private void invalidateAll() {
        if (host == null) return;
        host.invalidate();
        for (View v : dependents) v.invalidate();
    }

    private void tick() {
        invalidateAll();
        if (fading && host != null) host.postOnAnimation(this::tick);
    }

    /** Full-screen ambient layer. */
    void drawFull(Canvas c, int w, int h) {
        float p = progress();
        m.setScale(w / (float) wall.getWidth(), h / (float) wall.getHeight());
        c.drawBitmap(wall, m, photo);
        m.setScale(w / (float) W, h / (float) H);
        if (from != null) {
            wash.setAlpha(Math.round(WASH * (1 - p)));
            c.drawBitmap(from, m, wash);
        }
        if (to != null) {
            wash.setAlpha(Math.round(WASH * p));
            c.drawBitmap(to, m, wash);
        }
    }

    /**
     * Frosted glass fill: the blurred backdrop as it lies under rect r, brightened and more saturated.
     * (ox, oy) is the view's position in the root, (rw, rh) the root size.
     */
    void drawGlass(Canvas c, RectF r, float radius, float ox, float oy, int rw, int rh) {
        float p = progress();
        draw(c, wallSmall, r, radius, ox, oy, rw, rh, 255);
        if (from != null) draw(c, from, r, radius, ox, oy, rw, rh, Math.round(WASH * (1 - p)));
        if (to != null) draw(c, to, r, radius, ox, oy, rw, rh, Math.round(WASH * p));
    }

    private void draw(Canvas c, Bitmap b, RectF r, float radius, float ox, float oy, int rw, int rh, int alpha) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix lm = new Matrix();
        lm.setScale(rw / (float) b.getWidth(), rh / (float) b.getHeight());
        lm.postTranslate(-ox, -oy);
        s.setLocalMatrix(lm);
        glass.setShader(s);
        glass.setAlpha(alpha);
        c.drawRoundRect(r, radius, radius, glass);
        glass.setShader(null);
    }

    /** Halves repeatedly until the width is at most maxW, so every pixel is a true average. */
    private static Bitmap shrink(Bitmap src, int maxW) {
        Bitmap cur = src;
        while (cur.getWidth() > maxW) {
            Bitmap next = Bitmap.createScaledBitmap(cur, Math.max(1, cur.getWidth() / 2), Math.max(1, cur.getHeight() / 2), true);
            if (cur != src) cur.recycle();
            cur = next;
        }
        return cur;
    }

    /**
     * Ambient field from artwork: halve repeatedly down to 6x4 (so every pixel is a true average and
     * no letters or logos survive), then bilinear-upscale to the field size for smooth colour washes.
     */
    static Bitmap fieldFrom(Bitmap art) {
        if (art == null) return null;
        Bitmap cur = art;
        while (cur.getWidth() / 2 >= 6 && cur.getHeight() / 2 >= 4) {
            Bitmap next = Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
            if (cur != art) cur.recycle();
            cur = next;
        }
        Bitmap tiny = Bitmap.createScaledBitmap(cur, 6, 4, true);
        if (cur != art) cur.recycle();
        Bitmap field = Bitmap.createScaledBitmap(tiny, W, H, true);
        tiny.recycle();
        return field;
    }

    /** Fallback when no wallpaper loads: deep indigo with teal and violet glows, like an evening sky. */
    static Bitmap defaultField() {
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF0E1230);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        blob(c, p, W * 0.15f, H * 0.1f, W * 0.55f, 0xFF2B4BA8);
        blob(c, p, W * 0.85f, H * 0.25f, W * 0.5f, 0xFF7A3FA8);
        blob(c, p, W * 0.55f, H * 1.05f, W * 0.6f, 0xFF0F7A86);
        return b;
    }

    private static void blob(Canvas c, Paint p, float x, float y, float r, int color) {
        p.setShader(new RadialGradient(x, y, r, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, p);
    }
}
