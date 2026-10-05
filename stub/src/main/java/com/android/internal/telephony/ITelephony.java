package com.android.internal.telephony;

import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;

/**
 * 编译期桩（compileOnly，不打入 APK）。
 * 方法签名与 AOSP telephony/java/com/android/internal/telephony/ITelephony.aidl 一致，
 * 运行时通过真实 ITelephony 代理分发。
 */
public interface ITelephony extends android.os.IInterface {

    int setImsProvisioningInt(int subId, int key, int value) throws RemoteException;

    int getImsProvisioningInt(int subId, int key) throws RemoteException;

    abstract class Stub extends Binder implements ITelephony {
        public static ITelephony asInterface(IBinder binder) {
            throw new UnsupportedOperationException();
        }
    }
}
