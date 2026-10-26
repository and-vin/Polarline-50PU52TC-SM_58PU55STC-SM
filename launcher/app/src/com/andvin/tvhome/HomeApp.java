package com.andvin.tvhome;

import android.app.Application;

public class HomeApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        TvBridge.init(this);
    }
}
