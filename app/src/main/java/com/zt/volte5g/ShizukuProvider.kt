package com.zt.volte5g

import android.app.IActivityManager
import android.app.UiAutomationConnection
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.PersistableBundle
import android.os.Process
import android.os.ServiceManager
import android.os.UserHandle
import android.system.Os
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.util.Log
import com.android.internal.telephony.ITelephony
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

/**
 * Shizuku 集成层（vvb2060 v3.1 / Pixel IMS 同原理）。
 *
 * 工作流：
 *  1. Shizuku 服务器通过本 Provider 投递 binder（METHOD_SEND_BINDER）；
 *  2. 拿到 binder 且已授权后：先用 ITelephony 打开 IMS 语音开通位，
 *     再按版本指纹判断是否需要重新覆写；
 *  3. 需要时通过 IActivityManager.startInstrumentation（以 shell 身份发起，
 *     绕开 CVE-2025-48617 对 shell 直接调用 overrideConfig 的封堵）
 *     在本应用进程内启动 [PrivilegedProcess]；
 *  4. [PrivilegedProcess] 内先 startDelegateShellPermissionIdentity 把 shell
 *     权限身份委托给应用 uid，再调用 CarrierConfigManager.overrideConfig 完成写入。
 */
class ShizukuProvider : rikka.shizuku.ShizukuProvider() {

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // 仅在主用户（userId 0）下工作；UserHandle.getUserId 为隐藏方法，用 uid/100000 计算
        if (Os.getuid() / 100000 != 0) {
            return Bundle()
        }
        // Process.toSdkSandboxUid 是隐藏方法，反射调用
        val sdkUid = Hidden.call(
            Process::class.java, "toSdkSandboxUid",
            arrayOf(Int::class.javaPrimitiveType!!), arrayOf<Any?>(Os.getuid())
        ) as Int
        val callingUid = Binder.getCallingUid()
        if (callingUid != sdkUid && callingUid != Process.SHELL_UID && callingUid != Process.ROOT_UID) {
            return Bundle()
        }

        if (METHOD_SEND_BINDER == method) {
            Shizuku.addBinderReceivedListener {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    showVoLTE()
                    val context = getContext()
                    if (context != null && needOverride(context)) {
                        startInstrument(context, canPersistent(context))
                    }
                }
            }
        } else if (METHOD_GET_BINDER == method && callingUid == sdkUid && extras != null) {
            // SDK 沙箱路径：PrivilegedProcess 在沙箱中运行，回传一个回调 binder
            Shizuku.addBinderReceivedListener {
                val callback = extras.getBinder(SANDBOX_BINDER_KEY)
                if (callback != null && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    startSandboxDelegate(callback, sdkUid)
                }
            }
        }
        return super.call(method, arg, extras) ?: Bundle()
    }

    companion object {
        private const val TAG = "Volte5G.Provider"
        private const val SANDBOX_BINDER_KEY = "binder"
        private const val ARG_PID = "pid"

        /** 用户点击“应用配置”：强制重新覆写 */
        fun applyNow(context: Context) {
            showVoLTE()
            startInstrument(context, canPersistent(context))
        }

        /**
         * 以 shell 身份（ShizukuBinderWrapper）启动 Instrumentation。
         * persistentMode 为 true 时走 SDK 沙箱路径（该路径可持久化覆写），
         * 否则走普通路径做非持久化覆写。
         */
        internal fun startInstrument(context: Context, persistentMode: Boolean) {
            try {
                Hidden.exemptAll()
                val am = IActivityManager.Stub.asInterface(
                    ShizukuBinderWrapper(ServiceManager.getService(Context.ACTIVITY_SERVICE))
                )
                val name = ComponentName(context, PrivilegedProcess::class.java)
                // INSTR_FLAG_* 位定义随 Android 版本变化，必须运行时读取
                val flagDisableHiddenApiChecks = Hidden.staticInt(
                    "android.app.ActivityManager", "INSTR_FLAG_DISABLE_HIDDEN_API_CHECKS", 1
                )
                val flagNoRestart = Hidden.staticInt(
                    "android.app.ActivityManager", "INSTR_FLAG_NO_RESTART", 8
                )
                val flagSandbox = Hidden.staticInt(
                    "android.app.ActivityManager", "INSTR_FLAG_INSTRUMENT_SDK_SANDBOX", 32
                )
                val flags = flagDisableHiddenApiChecks or (if (persistentMode) flagSandbox else flagNoRestart)
                val args = Bundle().apply { putInt(ARG_PID, Process.myPid()) }
                val started = am.startInstrumentation(
                    name, null, flags, args, null, UiAutomationConnection(), 0, null
                )
                Log.i(TAG, "startInstrumentation ok=$started persistentMode=$persistentMode flags=0x${flags.toString(16)}")
            } catch (e: Throwable) {
                Log.e(TAG, "startInstrumentation 失败", e)
            }
        }

        /** 把 shell 权限身份委托给 SDK 沙箱 uid，然后触发沙箱内回调（transact code 1） */
        private fun startSandboxDelegate(callback: IBinder, sdkUid: Int) {
            try {
                val am = IActivityManager.Stub.asInterface(
                    ShizukuBinderWrapper(ServiceManager.getService(Context.ACTIVITY_SERVICE))
                )
                am.startDelegateShellPermissionIdentity(sdkUid, null)
                val data = Parcel.obtain()
                try {
                    callback.transact(1, data, null, 0)
                } finally {
                    data.recycle()
                }
                am.stopDelegateShellPermissionIdentity()
                Log.i(TAG, "沙箱委托完成")
            } catch (e: Throwable) {
                Log.e(TAG, "startSandboxDelegate 失败", e)
            }
        }

        /** 版本指纹：配置是否需要重新写入（false = 已是当前版本写入的） */
        fun needOverride(context: Context): Boolean {
            return try {
                val cm = context.getSystemService(CarrierConfigManager::class.java) ?: return true
                val sm = context.getSystemService(SubscriptionManager::class.java) ?: return true
                val list = sm.getActiveSubscriptionInfoList()
                if (list.isNullOrEmpty()) return true
                for (sub in list) {
                    val bundle = cm.getConfigForSubId(sub.subscriptionId)
                    if (bundle == null || bundle.getInt(Keys.KEY_CONFIG_VERSION, 0) != BuildConfig.VERSION_CODE) {
                        return true
                    }
                }
                Log.i(TAG, "配置已是当前版本写入，无需重写")
                false
            } catch (e: SecurityException) {
                true
            }
        }

        /**
         * 版本指纹：探测 com.android.phone.CarrierConfigLoader 的私有方法，
         * 判断该系统版本是否允许“持久化覆写”。
         */
        fun canPersistent(context: Context): Boolean {
            return try {
                val phone = context.createPackageContext(
                    "com.android.phone",
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
                )
                val clazz = phone.classLoader.loadClass("com.android.phone.CarrierConfigLoader")
                try {
                    clazz.getDeclaredMethod("isSystemApp")
                } catch (e: NoSuchMethodException) {
                    return true // 旧版本：无持久化限制
                }
                clazz.getDeclaredMethod(
                    "secureOverrideConfig", PersistableBundle::class.java, Boolean::class.javaPrimitiveType
                )
                try {
                    clazz.getDeclaredMethod("isSdkSandboxUidInternal", Int::class.javaPrimitiveType)
                    false // 最新补丁：持久化被封堵
                } catch (e: NoSuchMethodException) {
                    true // 中间版本：允许持久化
                }
            } catch (e: Throwable) {
                false // 无法判断时保守处理
            }
        }

        /** 通过 ITelephony 打开 IMS 语音开通位（部分运营商在开通层拦截 VoLTE） */
        private fun showVoLTE() {
            try {
                val subId = SubscriptionManager.getDefaultVoiceSubscriptionId()
                if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return
                val phone = ITelephony.Stub.asInterface(
                    ShizukuBinderWrapper(ServiceManager.getService(Context.TELEPHONY_SERVICE))
                )
                val current = phone.getImsProvisioningInt(subId, Keys.KEY_VOIMS_OPT_IN_STATUS)
                if (current == Keys.PROVISIONING_VALUE_ENABLED) {
                    Log.i(TAG, "IMS Voice Opt-In 已开启")
                    return
                }
                phone.setImsProvisioningInt(
                    subId, Keys.KEY_VOIMS_OPT_IN_STATUS, Keys.PROVISIONING_VALUE_ENABLED
                )
                Log.i(TAG, "IMS Voice Opt-In 已打开")
            } catch (e: Throwable) {
                Log.w(TAG, "showVoLTE 失败（非致命）: ${e.message}")
            }
        }
    }
}
