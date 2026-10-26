package com.andvin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Clock, date and network symbol for the status capsule; repaints on minute ticks and network changes. */
final class StatusView extends View {
    private final float u;
    private final Paint on, off;
    private final Paint time = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint date = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EE, d MMM", new Locale("ru"));
    private int level = -1;
    private boolean ethernet;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            refresh();
        }
    };

    StatusView(Context c, float u) {
        super(c);
        this.u = u;
        on = Icons.stroke(4f * u);
        off = Icons.stroke(4f * u);
        off.setColor(0x55FFFFFF);
        time.setColor(0xFFFFFFFF);
        time.setTextSize(32 * u);
        time.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        date.setColor(0xCCFFFFFF);
        date.setTextSize(26 * u);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        f.addAction(WifiManager.RSSI_CHANGED_ACTION);
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        getContext().registerReceiver(receiver, f);
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        getContext().unregisterReceiver(receiver);
        super.onDetachedFromWindow();
    }

    void refresh() {
        ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        ethernet = ni != null && ni.isConnected() && ni.getType() == ConnectivityManager.TYPE_ETHERNET;
        if (ni == null || !ni.isConnected()) {
            level = -1;
        } else if (!ethernet) {
            WifiManager wm = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int rssi = wm == null ? -100 : wm.getConnectionInfo().getRssi();
            level = WifiManager.calculateSignalLevel(rssi, 4) + 1;
        }
        requestLayout();
        invalidate();
    }

    // Layout: "12:34  пн, 26 окт.  (wifi)"
    @Override
    protected void onMeasure(int w, int h) {
        Date now = new Date();
        float width = time.measureText(fmt.format(now)) + 18 * u + date.measureText(dateFmt.format(now)) + 22 * u + 44 * u;
        setMeasuredDimension(Math.round(width), Math.round(48 * u));
    }

    @Override
    protected void onDraw(Canvas c) {
        Date now = new Date();
        float cy = getHeight() / 2f, x = 0;
        String t = fmt.format(now);
        c.drawText(t, x, cy + 11 * u, time);
        x += time.measureText(t) + 18 * u;
        String d = dateFmt.format(now);
        c.drawText(d, x, cy + 9 * u, date);
        x += date.measureText(d) + 22 * u;
        float s = 40 * u, cx = x + 22 * u;
        if (ethernet) Icons.ethernet(c, cx, cy, s, on);
        else Icons.wifi(c, cx, cy, s, level, on, off);
    }
}
