package android.app;

import android.os.IBinder;

/**
 * 编译期桩。运行时解析到系统真实的 android.app.UiAutomationConnection
 * （隐藏类，配合 HiddenApiBypass 豁免调用）。
 */
public class UiAutomationConnection implements IUiAutomationConnection {
    public UiAutomationConnection() {
    }

    @Override
    public IBinder asBinder() {
        return null;
    }
}
