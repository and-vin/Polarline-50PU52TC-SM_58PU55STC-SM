package com.andvin.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Frosted glass container holding focusable children: a capsule (tvOS-style top bar) by default, or a
 * rounded tray with a fixed corner radius (the favorites shelf).
 */
final class GlassBar extends LinearLayout {
    private final Glass glass;
    private final RectF r = new RectF();
    private final float u;
    /** Corner radius; negative means a capsule (half the height). */
    float cornerRadius = -1;
    Backdrop backdrop;
    View root;

    GlassBar(Context c, float u) {
        super(c);
        this.u = u;
        glass = new Glass(u);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int p = Math.round(8 * u);
        setPadding(p, p, p, p);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
    }

    @Override
    protected void onDraw(Canvas c) {
        r.set(0, 0, getWidth(), getHeight());
        float rad = cornerRadius < 0 ? getHeight() / 2f : cornerRadius;
        glass.draw(c, this, root, backdrop, r, rad, hasFocus());
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        invalidate();
    }

    @Override
    public void clearChildFocus(View child) {
        super.clearChildFocus(child);
        invalidate();
    }

    /** Text segment: transparent, white capsule with dark text when focused. */
    TextView segment(String title) {
        TextView t = new TextView(getContext());
        t.setText(title);
        t.setTextSize(0, 28 * u);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(Math.round(28 * u), Math.round(10 * u), Math.round(28 * u), Math.round(10 * u));
        t.setFocusable(true);
        t.setClickable(true);
        t.setGravity(Gravity.CENTER);
        GradientDrawable on = new GradientDrawable();
        on.setColor(Color.WHITE);
        on.setCornerRadius(40 * u);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, on);
        sl.addState(new int[]{}, new GradientDrawable());
        t.setBackground(sl);
        t.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF101014, 0xE6FFFFFF}));
        return t;
    }

    /** Round icon button (Icons.*): white circle with a dark symbol when focused. */
    View iconButton(int icon, String description) {
        IconButton b = new IconButton(getContext(), u, icon);
        b.setContentDescription(description);
        return b;
    }

    private static final class IconButton extends View {
        private final float u;
        private final int icon;
        private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke;

        IconButton(Context c, float u, int icon) {
            super(c);
            this.u = u;
            this.icon = icon;
            on.setColor(Color.WHITE);
            stroke = Icons.stroke(3.5f * u);
            setFocusable(true);
            setClickable(true);
        }

        @Override
        protected void onMeasure(int w, int h) {
            int s = Math.round(56 * u);
            setMeasuredDimension(s, s);
        }

        @Override
        protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect prev) {
            super.onFocusChanged(gained, direction, prev);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            boolean f = isFocused();
            if (f) c.drawCircle(cx, cy, Math.min(cx, cy), on);
            stroke.setColor(f ? 0xFF101014 : 0xE6FFFFFF);
            Icons.draw(c, icon, cx, cy, getHeight() * 0.56f, stroke);
        }
    }
}
