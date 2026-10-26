package com.cvte.tv.api.aidl;

import android.os.RemoteException;
import java.util.List;

/** Compile-only stub (subset of the AIDL interface). */
public interface ITVApiSystemInputSourceAidl extends android.os.IInterface {
    EntityInputSource eventSystemInputSourceGetInputSource() throws RemoteException;

    List<EntityInputSource> eventSystemInputSourceGetList() throws RemoteException;

    boolean eventSystemInputSourceSetInputSource(int id) throws RemoteException;
}
