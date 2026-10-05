package android.app;

import android.content.ComponentName;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/**
 * 编译期桩（compileOnly，不打入 APK）。
 * 运行时解析到系统真实 IActivityManager，方法签名与 AOSP
 * core/java/android/app/IActivityManager.aidl 保持一致。
 */
public interface IActivityManager extends IInterface {

    void startDelegateShellPermissionIdentity(int uid, String[] permissions) throws RemoteException;

    void stopDelegateShellPermissionIdentity() throws RemoteException;

    boolean startInstrumentation(ComponentName className, String profileFile,
                                 int flags, Bundle arguments, IInstrumentationWatcher watcher,
                                 IUiAutomationConnection connection, int userId,
                                 String abiOverride) throws RemoteException;

    abstract class Stub extends Binder implements IActivityManager {
        public static IActivityManager asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
