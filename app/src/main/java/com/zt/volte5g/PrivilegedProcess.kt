package com.zt.volte5g

import android.Manifest
import android.app.IActivityManager
import android.app.Instrumentation
import android.content.Context
import android.content.SharedPreferences
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.PersistableBundle
import android.os.Process
import android.os.ServiceManager
import android.os.UserHandle
import android.system.Os
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.util.Log
import com.android.internal.telephony.ICarrierConfigLoader
import rikka.shizuku.ShizukuBinderWrapper

/**
 * Instrumentation 入口（vvb2060 v3.1 / Pixel IMS 同原理）。
 *
 * 由 ShizukuProvider 以 shell 身份通过 IActivityManager.startInstrumentation
 * 启动，运行在本应用自己的进程里，因此：
 *  - 实际调用 uid 是应用自身，而不是 shell —— 避开 CVE-2025-48617 对
 *    “shell 直接调用 overrideConfig” 的封堵；
 *  - 先通过 startDelegateShellPermissionIdentity 把 shell 的权限身份
 *    （MODIFY_PHONE_STATE 等）委托给应用 uid，使隐藏 API 的权限校验通过。
 */
class PrivilegedProcess : Instrumentation() {

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        Hidden.exemptAll()
        val context = getContext()

        val inSandbox = Build.VERSION.SDK_INT >= 34 && Process.isSdkSandbox()
        if (inSandbox) {
            // SDK 沙箱路径：把回调 binder 交回 ShizukuProvider，
            // 由 Provider 持有 shell 委托后回调 transact(1) 执行写入
            startSandboxCallback(context)
        } else if (arguments != null && arguments.getInt(ARG_PID, 0) == Process.myPid()) {
            // 普通路径：确认是自己的 Provider 启动的（pid 匹配）
            applyInPlace(context)
        } else {
            Log.w(TAG, "意外的启动来源，拒绝执行")
            finish(0, Bundle())
        }
    }

    /** 普通路径：在本进程内完成 shell 身份委托并写入（非持久化） */
    private fun applyInPlace(context: Context) {
        var delegated = false
        try {
            val am = IActivityManager.Stub.asInterface(
                ShizukuBinderWrapper(ServiceManager.getService(Context.ACTIVITY_SERVICE))
            )
            am.startDelegateShellPermissionIdentity(Os.getuid(), null)
            delegated = true
            try {
                grantReadPhoneState(context)
                overrideCarrierConfig(context, persistent = false)
            } finally {
                am.stopDelegateShellPermissionIdentity()
            }
        } catch (e: Throwable) {
            // 设备缺少 startDelegateShellPermissionIdentity（Android 12 及以下）或委托失败时，
            // 回退为以 shell 身份直调 ICarrierConfigLoader（旧版本没有 isShell 拦截）
            Log.w(TAG, "委托路径失败（delegated=$delegated），回退 shell 直调: ${e.message}")
        }
        if (!delegated) {
            try {
                grantReadPhoneState(context)
                overrideCarrierConfig(context, persistent = false, viaShell = shellCarrierConfigLoader())
            } catch (e: Throwable) {
                Log.e(TAG, "shell 直调路径失败", e)
            }
        }
        finish(0, Bundle())
    }

    /** 以 shell 身份（ShizukuBinderWrapper）直连 carrier_config 服务 */
    private fun shellCarrierConfigLoader(): ICarrierConfigLoader =
        ICarrierConfigLoader.Stub.asInterface(
            ShizukuBinderWrapper(ServiceManager.getService("carrier_config"))
        )

    /** SDK 沙箱路径：注册回调 binder，等待 Provider 的 shell 委托生效后触发 */
    private fun startSandboxCallback(context: Context) {
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == 1) {
                    try {
                        grantReadPhoneState(context)
                        overrideCarrierConfig(context, persistent = true)
                    } catch (e: Throwable) {
                        Log.e(TAG, "沙箱回调执行失败", e)
                    }
                    // 延迟 finish，确保覆写调用先完成
                    Handler(Looper.getMainLooper()).postDelayed({ finish(0, Bundle()) }, 1000)
                    return true
                }
                return super.onTransact(code, data, reply, flags)
            }
        }
        val extras = Bundle().apply { putBinder(SANDBOX_BINDER_KEY, callback) }
        try {
            context.contentResolver.call(
                BuildConfig.APPLICATION_ID + ".shizuku",
                rikka.shizuku.ShizukuProvider.METHOD_GET_BINDER,
                null,
                extras
            )
        } catch (e: Throwable) {
            Log.e(TAG, "沙箱回调投递失败", e)
            finish(0, Bundle())
        }
    }

    /** 借助已生效的 shell 委托给自己授予 READ_PHONE_STATE（尽力而为） */
    private fun grantReadPhoneState(context: Context) {
        try {
            // android.permission.PermissionManager 是隐藏类，经 getSystemService("permission") 获取
            val pm = context.getSystemService("permission") ?: return
            Hidden.call(
                pm, "grantRuntimePermission",
                arrayOf(String::class.java, String::class.java, UserHandle::class.java),
                arrayOf(BuildConfig.APPLICATION_ID, Manifest.permission.READ_PHONE_STATE, Process.myUserHandle())
            )
        } catch (e: Throwable) {
            Log.w(TAG, "自授权 READ_PHONE_STATE 失败（非致命）: ${e.message}")
        }
    }

    /** 对选定的 SIM 逐个写入运营商配置覆写，持久化失败时反向重试一次 */
    private fun overrideCarrierConfig(
        context: Context,
        persistent: Boolean,
        viaShell: ICarrierConfigLoader? = null,
    ) {
        val cm = context.getSystemService(CarrierConfigManager::class.java) ?: return
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return
        val allSubIds = activeSubscriptionIds(sm)
        if (allSubIds.isEmpty()) {
            Log.w(TAG, "没有活动的 SIM 卡")
            return
        }
        val slot = getPrefs(context).getInt(Prefs.KEY_SELECTED_SUB, -1)
        val subIds = when (slot) {
            // 按物理卡槽定位（界面上“SIM 1/2”即卡槽 1/2）；读不到订阅信息时回退列表顺序
            1 -> subIdsForSlot(sm, 0) ?: intArrayOf(allSubIds[0])
            2 -> subIdsForSlot(sm, 1) ?: (if (allSubIds.size >= 2) intArrayOf(allSubIds[1]) else allSubIds)
            else -> allSubIds
        }
        var okCount = 0
        for (subId in subIds) {
            if (applyTo(cm, subId, persistent, viaShell)) okCount++
        }
        Log.i(TAG, "覆写完成：$okCount/${subIds.size} 张 SIM 成功（persistent=$persistent）")
    }

    /** 按物理卡槽序号（0 起）查 subId；读不到订阅信息时返回 null 由调用方回退 */
    private fun subIdsForSlot(sm: SubscriptionManager, slotIndex: Int): IntArray? {
        return try {
            sm.activeSubscriptionInfoList
                ?.filter { it.simSlotIndex == slotIndex }
                ?.map { it.subscriptionId }
                ?.toIntArray()
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Throwable) {
            null
        }
    }

    private fun applyTo(
        cm: CarrierConfigManager,
        subId: Int,
        persistent: Boolean,
        viaShell: ICarrierConfigLoader?,
    ): Boolean {
        val values = buildConfigBundle()
        values.putInt(Keys.KEY_CONFIG_VERSION, BuildConfig.VERSION_CODE)
        val applied = try {
            if (viaShell != null) {
                viaShell.overrideConfig(subId, values, persistent)
            } else {
                Hidden.call(
                    cm, "overrideConfig",
                    arrayOf(Int::class.javaPrimitiveType!!, PersistableBundle::class.java, Boolean::class.javaPrimitiveType!!),
                    arrayOf(subId, values, persistent)
                )
            }
            true
        } catch (e: Throwable) {
            Log.w(TAG, "overrideConfig(persistent=$persistent) 被拒绝，反向重试: ${e.message}")
            try {
                Hidden.call(
                    cm, "overrideConfig",
                    arrayOf(Int::class.javaPrimitiveType!!, PersistableBundle::class.java, Boolean::class.javaPrimitiveType!!),
                    arrayOf(subId, values, !persistent)
                )
                true
            } catch (e2: Throwable) {
                Log.e(TAG, "overrideConfig 对 subId=$subId 完全失败", e2)
                false
            }
        }
        if (!applied) return false

        // 校验版本指纹是否已写入
        return try {
            val check = cm.getConfigForSubId(subId)
            val ok = check != null && check.getInt(Keys.KEY_CONFIG_VERSION, 0) == BuildConfig.VERSION_CODE
            Log.i(TAG, "subId=$subId 应用${if (ok) "成功" else "校验失败"}（persistent=$persistent）")
            ok
        } catch (e: Throwable) {
            Log.e(TAG, "subId=$subId 校验异常", e)
            false
        }
    }

    private fun activeSubscriptionIds(sm: SubscriptionManager): IntArray {
        return try {
            Hidden.call(sm, "getActiveSubscriptionIdList", arrayOf<Class<*>>(), arrayOf<Any?>()) as? IntArray
                ?: IntArray(0)
        } catch (e: Throwable) {
            try {
                sm.getActiveSubscriptionInfoList()
                    ?.map { it.subscriptionId }
                    ?.toIntArray()
                    ?: IntArray(0)
            } catch (e2: Throwable) {
                IntArray(0)
            }
        }
    }

    /** 沙箱进程中读取主应用偏好；失败时退回当前 context 的偏好 */
    private fun getPrefs(context: Context): SharedPreferences {
        return try {
            val appContext = context.createPackageContext(
                BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY
            )
            appContext.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        }
    }

    /** 根据用户开关组装覆写内容；开关关闭 = 恢复系统默认（不写入对应键） */
    private fun buildConfigBundle(): PersistableBundle {
        val context = getContext()
        val prefs = getPrefs(context)
        val volte = prefs.getBoolean(Prefs.VOLTE, true)
        val vowifi = prefs.getBoolean(Prefs.VOWIFI, true)
        val vt = prefs.getBoolean(Prefs.VT, true)
        val vonr = prefs.getBoolean(Prefs.VONR, true)
        val crossSim = prefs.getBoolean(Prefs.CROSS_SIM, true)
        val ut = prefs.getBoolean(Prefs.UT, true)
        val nr5g = prefs.getBoolean(Prefs.NR5G, true)

        val bundle = PersistableBundle()

        // 始终在设置中显示 IMS 注册状态，方便确认是否生效
        bundle.putBoolean(Keys.SHOW_IMS_REGISTRATION_STATUS, true)

        if (volte) {
            bundle.putBoolean(Keys.CARRIER_VOLTE_AVAILABLE, true)
            bundle.putBoolean(Keys.EDITABLE_ENHANCED_4G_LTE, true)
            bundle.putBoolean(Keys.HIDE_ENHANCED_4G_LTE, false)
            bundle.putBoolean(Keys.HIDE_LTE_PLUS_ICON, false)
        }
        if (vowifi) {
            bundle.putBoolean(Keys.CARRIER_WFC_IMS_AVAILABLE, true)
            bundle.putBoolean(Keys.CARRIER_WFC_SUPPORTS_WIFI_ONLY, true)
            bundle.putBoolean(Keys.EDITABLE_WFC_MODE, true)
            bundle.putBoolean(Keys.EDITABLE_WFC_ROAMING_MODE, true)
            bundle.putBoolean(Keys.SHOW_WFC_ICON, true)
            bundle.putInt(Keys.WFC_SPN_FORMAT_IDX, 6)
        }
        if (vt) {
            bundle.putBoolean(Keys.CARRIER_VT_AVAILABLE, true)
        }
        if (ut) {
            bundle.putBoolean(Keys.CARRIER_SS_OVER_UT, true)
        }
        if (crossSim) {
            bundle.putBoolean(Keys.CROSS_SIM_IMS_AVAILABLE, true)
            bundle.putBoolean(Keys.CROSS_SIM_ON_OPPORTUNISTIC, true)
        }
        if (vonr) {
            bundle.putBoolean(Keys.VONR_ENABLED, true)
            bundle.putBoolean(Keys.VONR_SETTING_VISIBILITY, true)
        }
        if (nr5g) {
            // 按用户选择的组网模式开放 NR；默认 NSA + SA 都开放
            val nrMode = prefs.getInt(Prefs.NR_MODE, Prefs.NR_MODE_BOTH)
            val availabilities = when (nrMode) {
                Prefs.NR_MODE_SA -> intArrayOf(Keys.NR_AVAILABILITY_SA)
                Prefs.NR_MODE_NSA -> intArrayOf(Keys.NR_AVAILABILITY_NSA)
                else -> intArrayOf(Keys.NR_AVAILABILITY_NSA, Keys.NR_AVAILABILITY_SA)
            }
            bundle.putIntArray(Keys.CARRIER_NR_AVAILABILITIES, availabilities)
            // 5G 信号强度显示阈值（SSRSRP，dBm），缺失时状态栏信号格可能异常
            bundle.putIntArray(
                Keys.NR_SSRSRP_THRESHOLDS,
                intArrayOf(-128, -118, -108, -98)
            )
        }
        return bundle
    }

    companion object {
        private const val TAG = "Volte5G.Privileged"
        private const val ARG_PID = "pid"
        private const val SANDBOX_BINDER_KEY = "binder"
    }
}
