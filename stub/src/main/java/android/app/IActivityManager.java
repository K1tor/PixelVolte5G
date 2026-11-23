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

    /**
     * Android 16 及更早的释放接口。
     * Android 17 起系统侧已改为 [stopDelegateShellPermissionIdentity(int)]，
     * 旧签名调用会抛 NoSuchMethodError，运行时按实际存在的方法二选一。
     */
    void stopDelegateShellPermissionIdentity() throws RemoteException;

    /** Android 17+ 的释放接口：释放 start 时授予的同一个 uid */
    void stopDelegateShellPermissionIdentity(int uid) throws RemoteException;

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
