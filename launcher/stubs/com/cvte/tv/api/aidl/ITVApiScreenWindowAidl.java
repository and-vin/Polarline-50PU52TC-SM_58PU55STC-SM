package com.cvte.tv.api.aidl;

import android.os.RemoteException;

/** Compile-only stub (subset of the AIDL interface). */
public interface ITVApiScreenWindowAidl extends android.os.IInterface {
    boolean eventScreenWindowMute(boolean mute) throws RemoteException;

    boolean eventScreenWindowSetFull() throws RemoteException;

    boolean eventScreenWindowSetPipValue(int x, int y, int w, int h) throws RemoteException;
}
