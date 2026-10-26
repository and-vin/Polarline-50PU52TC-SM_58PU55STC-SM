package com.andvin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.widget.FrameLayout;

/**
 * Ambient backdrop (see Backdrop) under a soft vignette, with an optional transparent hole where
 * the TV video plane shows through.
 */
final class HomeRoot extends FrameLayout {
    final Backdrop backdrop = new Backdrop();
    private final Paint shade = new Paint();
    private final Paint vignette = new Paint();
    private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RectF hole;
    private float holeRadius;

    HomeRoot(Context c) {
        super(c);
        setWillNotDraw(false);
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        backdrop.attach(this);
        backdrop.loadWallpaper(c);
    }

    void setHole(RectF r, float radius) {
        hole = r;
        holeRadius = radius;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // Light touch: the wallpaper stays bright; only the very bottom and the corners are dimmed a bit.
        shade.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x00000000, 0x00000000, 0x66000000},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        vignette.setShader(new RadialGradient(w * 0.5f, h * 0.45f, w * 0.75f, 0x00000000, 0x40000000, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        backdrop.drawFull(c, getWidth(), getHeight());
        c.drawPaint(shade);
        c.drawPaint(vignette);
        if (hole != null) c.drawRoundRect(hole, holeRadius, holeRadius, clear);
    }
}
