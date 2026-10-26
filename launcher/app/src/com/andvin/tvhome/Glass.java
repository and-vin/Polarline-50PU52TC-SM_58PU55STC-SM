package com.andvin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * "Liquid glass" surface: frosted backdrop sample, milky tint, specular top highlight and a rim
 * that is bright at the top and fades downwards. Only round rects and shaders (no clipPath).
 */
final class Glass {
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] loc = new int[2];
    private final int[] rootLoc = new int[2];
    private float shineH = -1, rimH = -1;
    private boolean rimFocused;
    private final float u;

    Glass(float u) {
        this.u = u;
        rim.setStyle(Paint.Style.STROKE);
    }

    void draw(Canvas c, View v, View root, Backdrop bd, RectF r, float radius, boolean focused) {
        if (bd != null && root != null) {
            v.getLocationInWindow(loc);
            root.getLocationInWindow(rootLoc);
            bd.drawGlass(c, r, radius, loc[0] - rootLoc[0], loc[1] - rootLoc[1], root.getWidth(), root.getHeight());
        }
        tint.setColor(focused ? 0x33FFFFFF : 0x17FFFFFF);
        c.drawRoundRect(r, radius, radius, tint);

        float h = r.height();
        if (shineH != h) {
            shineH = h;
            shine.setShader(new LinearGradient(0, r.top, 0, r.top + h * 0.6f, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        }
        c.drawRoundRect(r, radius, radius, shine);

        if (rimH != h || rimFocused != focused) {
            rimH = h;
            rimFocused = focused;
            rim.setStrokeWidth((focused ? 3f : 1.6f) * u);
            rim.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                    focused ? 0xFFFFFFFF : 0x99FFFFFF, focused ? 0x66FFFFFF : 0x1AFFFFFF, Shader.TileMode.CLAMP));
        }
        float i = rim.getStrokeWidth() / 2;
        c.drawRoundRect(r.left + i, r.top + i, r.right - i, r.bottom - i, radius, radius, rim);
    }
}
