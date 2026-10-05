package android.os;

/**
 * 编译期桩。运行时解析到系统真实的 android.os.ServiceManager（隐藏类）。
 */
public class ServiceManager {
    public static IBinder getService(String name) {
        throw new UnsupportedOperationException();
    }
}
