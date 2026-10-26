package com.andvin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * Card in three flavours:
 * APP   - 16:9 app banner; on focus it grows, gets a specular sheen and a glass rim. Apps without a
 *         banner show their icon with the name inside the card.
 * GLASS - frosted glass tile with a line icon and caption (system actions).
 * LIVE  - glass rim around a transparent hole where the TV picture is shown.
 * No clipPath (Mali-450 ignores the canvas transform for path clips): rounded corners come from
 * drawRoundRect with a BitmapShader; focus growth is canvas.scale in onDraw, not setScaleX.
 */
final class Tile extends View {
    static final int APP = 0, GLASS = 1, LIVE = 2;

    interface Listener {
        void onClick(Tile t);

        void onMenu(Tile t);
    }

    final float u;
    final int cardW, cardH;
    final int kind;
    final float radius;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint sub = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint;
    private final RectF card = new RectF();
    private final LinearGradient sheen;
    private final Glass glass;

    Object tag;
    Backdrop backdrop;
    View root;
    private Listener listener;
    private BitmapShader artShader;
    private Bitmap ambient;
    private int body = 0xFF26272D;
    private String label = "";
    private String centerTitle;
    private String centerSub;
    private int icon;
    private boolean live;
    private boolean iconArt;
    private boolean longPressed;

    Tile(Context c, float u, int cardW, int cardH, int kind) {
        super(c);
        this.u = u;
        this.cardW = cardW;
        this.cardH = cardH;
        this.kind = kind;
        // Rounded almost to a pill, like tvOS app cards.
        this.radius = cardH * 0.24f;
        glass = new Glass(u);
        setFocusable(true);
        setClickable(true);
        card.set(0, 0, cardW, cardH);
        sheen = new LinearGradient(0, 0, cardW * 0.6f, cardH, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(3f * u);
        rim.setShader(new LinearGradient(0, 0, 0, cardH, 0xFFFFFFFF, 0x55FFFFFF, Shader.TileMode.CLAMP));
        text.setColor(Color.WHITE);
        text.setTextSize(27 * u);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        sub.setColor(0xB3FFFFFF);
        sub.setTextSize(23 * u);
        sub.setTextAlign(Paint.Align.CENTER);
        iconPaint = Icons.stroke(4.5f * u);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                int dx = grows() && isFocused() ? Math.round(Tile.this.cardW * 0.05f) : 0;
                int dy = grows() && isFocused() ? Math.round(Tile.this.cardH * 0.05f) : 0;
                outline.setRoundRect(-dx, -dy, Tile.this.cardW + dx, Tile.this.cardH + dy, radius * 1.1f);
                outline.setAlpha(kind == APP ? 0.9f : 0.5f);
            }
        });
    }

    private boolean grows() {
        return kind != LIVE;
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setLabel(String s) {
        label = s == null ? "" : s;
        setContentDescription(label);
    }

    String getLabel() {
        return label;
    }

    /** Ambient field for the backdrop when this card is focused (null: keep current). */
    Bitmap getAmbient() {
        return ambient;
    }

    void setIcon(int kind) {
        icon = kind;
        invalidate();
    }

    /**
     * Renders the artwork once into a card-sized bitmap. Banners from other apps may be state lists or
     * insets that change with focus; baking them keeps the card filled and makes redraws cheap.
     */
    void setArt(Drawable d, boolean isIcon) {
        if (isIcon && d != null) body = tintFrom(d);
        iconArt = isIcon;
        artShader = null;
        ambient = null;
        if (d == null) return;
        try {
            Bitmap baked = Bitmap.createBitmap(cardW, cardH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(baked);
            c.drawColor(body);
            if (isIcon) {
                // Icon above centre, leaving room for the name drawn under it in onDraw.
                int s = Math.round(cardH * 0.44f);
                int l = (cardW - s) / 2, t = Math.round(cardH * 0.40f) - s / 2;
                d.setBounds(l, t, l + s, t + s);
            } else {
                d.setBounds(0, 0, cardW, cardH);
            }
            d.setState(new int[0]);
            d.draw(c);
            artShader = new BitmapShader(baked, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            ambient = Backdrop.fieldFrom(baked);
        } catch (Throwable t) {
            artShader = null;
        }
        invalidate();
    }

    void setCenterText(String title, String subtitle) {
        centerTitle = title;
        centerSub = subtitle;
        invalidate();
    }

    void setLive(boolean v) {
        live = v;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(cardW, cardH);
    }

    @Override
    protected void onDraw(Canvas c) {
        boolean focused = isFocused();
        if (focused && grows()) c.scale(1.10f, 1.10f, cardW / 2f, cardH / 2f);

        if (kind == APP) {
            if (artShader != null) {
                paint.setShader(artShader);
            } else {
                paint.setShader(null);
                paint.setColor(body);
            }
            c.drawRoundRect(card, radius, radius, paint);
            paint.setShader(null);
            if (iconArt && !TextUtils.isEmpty(label)) {
                String s = TextUtils.ellipsize(label, text, cardW - 2 * radius, TextUtils.TruncateAt.END).toString();
                c.drawText(s, cardW / 2f, cardH * 0.84f, text);
            }
            if (focused) {
                paint.setShader(sheen);
                c.drawRoundRect(card, radius, radius, paint);
                paint.setShader(null);
                float i = rim.getStrokeWidth() / 2;
                c.drawRoundRect(i, i, cardW - i, cardH - i, radius, radius, rim);
            }
        } else {
            if (!(kind == LIVE && live)) glass.draw(c, this, root, backdrop, card, radius, focused);
            else {
                float i = rim.getStrokeWidth() / 2;
                rim.setAlpha(focused ? 255 : 140);
                c.drawRoundRect(i, i, cardW - i, cardH - i, radius, radius, rim);
                rim.setAlpha(255);
            }
            if (icon != 0) {
                float s = cardH * 0.36f;
                Icons.draw(c, icon, cardW / 2f, cardH * 0.40f, s, iconPaint);
                if (centerTitle != null) c.drawText(centerTitle, cardW / 2f, cardH * 0.82f, text);
            } else if (centerTitle != null && !(kind == LIVE && live)) {
                text.setTextSize(40 * u);
                float y = cardH / 2f + (centerSub != null ? -2 * u : 14 * u);
                c.drawText(centerTitle, cardW / 2f, y, text);
                text.setTextSize(27 * u);
                if (centerSub != null) c.drawText(centerSub, cardW / 2f, y + 40 * u, sub);
            }
        }
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect prev) {
        super.onFocusChanged(gained, direction, prev);
        // Lift above neighbours so the grown card is not overdrawn by the next one.
        setTranslationZ(gained && grows() ? 30 * u : 0);
        invalidateOutline();
        invalidate();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (listener != null) listener.onMenu(this);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (e.getRepeatCount() == 0) longPressed = false;
            e.startTracking();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            longPressed = true;
            if (listener != null) listener.onMenu(this);
            return true;
        }
        return super.onKeyLongPress(keyCode, e);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (!longPressed && !e.isCanceled() && listener != null) listener.onClick(this);
            longPressed = false;
            return true;
        }
        return super.onKeyUp(keyCode, e);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (listener != null) listener.onClick(this);
        return true;
    }

    /** Darkened average colour of an icon, used as the card background behind it. */
    private static int tintFrom(Drawable d) {
        try {
            Bitmap b = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, 12, 12);
            d.draw(c);
            long r = 0, g = 0, bl = 0, n = 0;
            for (int x = 0; x < 12; x++) {
                for (int y = 0; y < 12; y++) {
                    int p = b.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    r += Color.red(p);
                    g += Color.green(p);
                    bl += Color.blue(p);
                    n++;
                }
            }
            b.recycle();
            if (n == 0) return 0xFF26272D;
            float k = 0.45f;
            return Color.rgb((int) (r / n * k) + 18, (int) (g / n * k) + 18, (int) (bl / n * k) + 22);
        } catch (Throwable t) {
            return 0xFF26272D;
        }
    }
}
