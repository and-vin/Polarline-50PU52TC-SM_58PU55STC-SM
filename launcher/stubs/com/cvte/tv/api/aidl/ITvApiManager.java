package com.cvte.tv.api.aidl;

import android.os.RemoteException;

/** Compile-only stub (subset of the AIDL interface). */
public interface ITvApiManager extends android.os.IInterface {
    ITVApiSystemInputSourceAidl getTVApiSystemInputSource() throws RemoteException;

    ITVApiScreenWindowAidl getTVApiScreenWindow() throws RemoteException;
}
