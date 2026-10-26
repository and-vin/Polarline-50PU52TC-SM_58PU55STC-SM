package com.andvin.tvhome;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Centered frosted panel with a title and a list of actions, replacing the stock AlertDialog look. */
final class GlassMenu {
    interface OnPick {
        void onPick(int index);
    }

    private GlassMenu() {
    }

    static void show(Context ctx, float u, String title, List<String> items, OnPick pick) {
        final Dialog d = new Dialog(ctx);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Panel panel = new Panel(ctx, u);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * u);
        panel.setPadding(pad, pad, pad, pad);

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextColor(0x99FFFFFF);
        t.setTextSize(0, 26 * u);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(Math.round(20 * u), 0, 0, Math.round(14 * u));
        panel.addView(t);

        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            TextView it = new TextView(ctx);
            it.setText(items.get(i));
            it.setTextSize(0, 32 * u);
            it.setPadding(Math.round(26 * u), Math.round(16 * u), Math.round(26 * u), Math.round(16 * u));
            it.setFocusable(true);
            it.setClickable(true);
            GradientDrawable on = new GradientDrawable();
            on.setColor(Color.WHITE);
            on.setCornerRadius(18 * u);
            StateListDrawable sl = new StateListDrawable();
            sl.addState(new int[]{android.R.attr.state_focused}, on);
            sl.addState(new int[]{}, new GradientDrawable());
            it.setBackground(sl);
            it.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                    new int[]{0xFF101014, Color.WHITE}));
            it.setOnClickListener(v -> {
                d.dismiss();
                pick.onPick(idx);
            });
            panel.addView(it, new LinearLayout.LayoutParams(-1, -2));
        }

        d.setContentView(panel, new LinearLayout.LayoutParams(Math.round(640 * u), -2));
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setGravity(Gravity.CENTER);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.55f);
            w.setWindowAnimations(0);
        }
        d.show();
        if (panel.getChildCount() > 1) panel.getChildAt(1).requestFocus();
    }

    /** Dark frosted panel: the dialog window cannot sample the launcher backdrop, so it is a tinted slab. */
    private static final class Panel extends LinearLayout {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float u;

        Panel(Context c, float u) {
            super(c);
            this.u = u;
            setWillNotDraw(false);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(1.6f * u);
        }

        @Override
        protected void onDraw(Canvas c) {
            r.set(0, 0, getWidth(), getHeight());
            float rad = 30 * u;
            fill.setShader(new LinearGradient(0, 0, 0, getHeight(), 0xEE2A2C36, 0xEE16171D, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, rad, rad, fill);
            rim.setShader(new LinearGradient(0, 0, 0, getHeight(), 0x99FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
            float i = rim.getStrokeWidth() / 2;
            c.drawRoundRect(i, i, getWidth() - i, getHeight() - i, rad, rad, rim);
        }
    }
}
