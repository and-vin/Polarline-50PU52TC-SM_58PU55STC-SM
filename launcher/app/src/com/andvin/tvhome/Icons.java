package com.andvin.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Line icons drawn in code (centre cx, cy; size s), white strokes like the tvOS symbol set. */
final class Icons {
    static final int SETTINGS = 1, BLUETOOTH = 2, KEYBOARD = 3, HIDDEN = 4, TV = 5, FILES = 6;

    private Icons() {
    }

    static Paint stroke(float w) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(0xFFFFFFFF);
        return p;
    }

    static void draw(Canvas c, int kind, float cx, float cy, float s, Paint p) {
        switch (kind) {
            case SETTINGS: gear(c, cx, cy, s, p); break;
            case BLUETOOTH: bluetooth(c, cx, cy, s, p); break;
            case KEYBOARD: keyboard(c, cx, cy, s, p); break;
            case HIDDEN: eyeOff(c, cx, cy, s, p); break;
            case TV: tv(c, cx, cy, s, p); break;
            case FILES: folder(c, cx, cy, s, p); break;
        }
    }

    private static void gear(Canvas c, float cx, float cy, float s, Paint p) {
        float r = s * 0.30f, R = s * 0.44f;
        Path path = new Path();
        int teeth = 8;
        for (int i = 0; i < teeth * 2; i++) {
            double a = Math.PI * i / teeth;
            float rr = i % 2 == 0 ? R : r * 1.18f;
            float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
        c.drawPath(path, p);
        c.drawCircle(cx, cy, s * 0.13f, p);
    }

    private static void bluetooth(Canvas c, float cx, float cy, float s, Paint p) {
        float h = s * 0.42f, w = s * 0.22f;
        Path path = new Path();
        path.moveTo(cx - w, cy - h * 0.5f);
        path.lineTo(cx + w, cy + h * 0.5f);
        path.lineTo(cx, cy + h);
        path.lineTo(cx, cy - h);
        path.lineTo(cx + w, cy - h * 0.5f);
        path.lineTo(cx - w, cy + h * 0.5f);
        c.drawPath(path, p);
    }

    private static void keyboard(Canvas c, float cx, float cy, float s, Paint p) {
        RectF r = new RectF(cx - s * 0.46f, cy - s * 0.28f, cx + s * 0.46f, cy + s * 0.28f);
        c.drawRoundRect(r, s * 0.08f, s * 0.08f, p);
        for (int row = 0; row < 2; row++) {
            for (int i = 0; i < 5; i++) {
                float x = r.left + s * 0.16f + i * s * 0.15f;
                float y = r.top + s * 0.17f + row * s * 0.14f;
                c.drawPoint(x, y, p);
            }
        }
        c.drawLine(cx - s * 0.2f, r.bottom - s * 0.14f, cx + s * 0.2f, r.bottom - s * 0.14f, p);
    }

    private static void eyeOff(Canvas c, float cx, float cy, float s, Paint p) {
        RectF r = new RectF(cx - s * 0.44f, cy - s * 0.26f, cx + s * 0.44f, cy + s * 0.26f);
        Path eye = new Path();
        eye.moveTo(r.left, cy);
        eye.quadTo(cx, r.top - s * 0.18f, r.right, cy);
        eye.quadTo(cx, r.bottom + s * 0.18f, r.left, cy);
        c.drawPath(eye, p);
        c.drawCircle(cx, cy, s * 0.11f, p);
        c.drawLine(cx - s * 0.38f, cy - s * 0.38f, cx + s * 0.38f, cy + s * 0.38f, p);
    }

    private static void tv(Canvas c, float cx, float cy, float s, Paint p) {
        RectF r = new RectF(cx - s * 0.46f, cy - s * 0.3f, cx + s * 0.46f, cy + s * 0.22f);
        c.drawRoundRect(r, s * 0.06f, s * 0.06f, p);
        c.drawLine(cx - s * 0.18f, cy + s * 0.36f, cx + s * 0.18f, cy + s * 0.36f, p);
    }

    private static void folder(Canvas c, float cx, float cy, float s, Paint p) {
        Path path = new Path();
        float l = cx - s * 0.44f, r = cx + s * 0.44f, t = cy - s * 0.3f, b = cy + s * 0.3f;
        path.moveTo(l, b);
        path.lineTo(l, t);
        path.lineTo(cx - s * 0.12f, t);
        path.lineTo(cx, t + s * 0.1f);
        path.lineTo(r, t + s * 0.1f);
        path.lineTo(r, b);
        path.close();
        c.drawPath(path, p);
    }

    /** Wi-Fi fan with `level` of 0..4 bars lit; -1 means no network (fan dimmed, slash). */
    static void wifi(Canvas c, float cx, float cy, float s, int level, Paint on, Paint off) {
        float base = cy + s * 0.32f;
        for (int i = 1; i <= 3; i++) {
            float r = s * 0.22f * i;
            RectF a = new RectF(cx - r, base - r, cx + r, base + r);
            c.drawArc(a, 225, 90, false, level >= i + 1 ? on : off);
        }
        Paint dot = level >= 1 ? on : off;
        c.drawCircle(cx, base - s * 0.02f, s * 0.04f, dot);
        if (level < 0) c.drawLine(cx - s * 0.38f, cy - s * 0.34f, cx + s * 0.38f, cy + s * 0.4f, on);
    }

    /** Ethernet: three connected boxes. */
    static void ethernet(Canvas c, float cx, float cy, float s, Paint p) {
        float b = s * 0.16f;
        c.drawRect(cx - b, cy - s * 0.42f, cx + b, cy - s * 0.42f + 2 * b, p);
        c.drawRect(cx - s * 0.44f, cy + s * 0.1f, cx - s * 0.44f + 2 * b, cy + s * 0.1f + 2 * b, p);
        c.drawRect(cx + s * 0.44f - 2 * b, cy + s * 0.1f, cx + s * 0.44f, cy + s * 0.1f + 2 * b, p);
        c.drawLine(cx, cy - s * 0.42f + 2 * b, cx, cy + s * 0.02f, p);
        c.drawLine(cx - s * 0.44f + b, cy + s * 0.02f, cx + s * 0.44f - b, cy + s * 0.02f, p);
        c.drawLine(cx - s * 0.44f + b, cy + s * 0.02f, cx - s * 0.44f + b, cy + s * 0.1f, p);
        c.drawLine(cx + s * 0.44f - b, cy + s * 0.02f, cx + s * 0.44f - b, cy + s * 0.1f, p);
    }
}
