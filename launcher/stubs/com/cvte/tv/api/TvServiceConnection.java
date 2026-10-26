package com.cvte.tv.api;

import com.cvte.tv.api.aidl.ITvApiManager;

/** Compile-only stub. */
public interface TvServiceConnection {
    void onServiceConnected(ITvApiManager manager);

    void onServiceDisconnected();
}
