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
 *
 * 结果分级（v1.3 起）：写入结果、回读校验结果、委托清理结果彼此独立，
 * 清理失败不会再把已经成功的写入误报为失败。
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
            Logs.w("意外的启动来源，拒绝执行")
            finish(0, Bundle())
        }
    }

    /** 普通路径：在本进程内完成 shell 身份委托并写入（非持久化） */
    private fun applyInPlace(context: Context) {
        var report = ApplyReport.start()
        var delegated = false
        try {
            val am = IActivityManager.Stub.asInterface(
                ShizukuBinderWrapper(ServiceManager.getService(Context.ACTIVITY_SERVICE))
            )
            delegated = Delegation.start(am, Os.getuid())
            if (delegated) {
                try {
                    grantReadPhoneState(context)
                    report = runOverrides(context, persistentRequested = false)
                } finally {
                    val cleanupOk = Delegation.stop(am)
                    report = report.copy(cleanupOk = cleanupOk)
                    if (!cleanupOk) Logs.w("委托清理失败（写入结果不受影响）")
                }
            }
        } catch (e: Throwable) {
            // 设备缺少 startDelegateShellPermissionIdentity（Android 12 及以下）或委托失败时，
            // 回退为以 shell 身份直调 ICarrierConfigLoader（旧版本没有 isShell 拦截）
            Logs.w("委托路径失败（delegated=$delegated），回退 shell 直调: ${e.message}")
            if (report.failure == null) {
                report = report.copy(failure = e.javaClass.simpleName + ": " + e.message)
            }
        }
        if (!delegated) {
            try {
                grantReadPhoneState(context)
                report = runOverrides(context, persistentRequested = false, viaShell = shellCarrierConfigLoader())
            } catch (e: Throwable) {
                Logs.e("shell 直调路径失败: ${e.message}")
                report = report.copy(failure = e.javaClass.simpleName + ": " + e.message)
            }
        }
        if (report.failure == null && !report.ok) {
            report = report.copy(failure = "未能向任何 SIM 写入配置")
        }
        report.save(getPrefs(context))
        Logs.i(report.summary())
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
                        val report = runOverrides(context, persistentRequested = true)
                        report.save(getPrefs(context))
                        Logs.i("沙箱路径 " + report.summary())
                    } catch (e: Throwable) {
                        Logs.e("沙箱回调执行失败: ${e.message}")
                        ApplyReport.start().copy(failure = e.javaClass.simpleName + ": " + e.message)
                            .save(getPrefs(context))
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
            Logs.e("沙箱回调投递失败: ${e.message}")
            finish(0, Bundle())
        }
    }

    /** 借助已生效的 shell 委托给自己授予 READ_PHONE_STATE（尽力而为） */
    private fun grantReadPhoneState(context: Context) {
        try {
            val pm = context.getSystemService("permission") ?: return
            Hidden.call(
                pm, "grantRuntimePermission",
                arrayOf(String::class.java, String::class.java, UserHandle::class.java),
                arrayOf(BuildConfig.APPLICATION_ID, Manifest.permission.READ_PHONE_STATE, Process.myUserHandle())
            )
        } catch (e: Throwable) {
            Logs.w("自授权 READ_PHONE_STATE 失败（非致命）: ${e.message}")
        }
    }

    /**
     * 对选定的 SIM 逐个写入（或清除）运营商配置覆写。
     * 清除时传 null bundle 让系统丢弃整份覆写，避免“关掉开关但旧值仍在”。
     */
    private fun runOverrides(
        context: Context,
        persistentRequested: Boolean,
        viaShell: ICarrierConfigLoader? = null,
    ): ApplyReport {
        var report = ApplyReport.start()
        val cm = context.getSystemService(CarrierConfigManager::class.java)
        val sm = context.getSystemService(SubscriptionManager::class.java)
        if (cm == null || sm == null) {
            return report.copy(failure = "无法获取 CarrierConfig/Subscription 服务")
        }
        val allSubIds = activeSubscriptionIds(sm)
        if (allSubIds.isEmpty()) {
            return report.copy(failure = "没有活动的 SIM 卡")
        }
        val prefs = getPrefs(context)
        val clearing = prefs.getBoolean(Prefs.PENDING_CLEAR, false)
        val slot = prefs.getInt(Prefs.KEY_SELECTED_SUB, -1)
        val subIds = when (slot) {
            // 按物理卡槽定位（界面上“SIM 1/2”即卡槽 1/2）；读不到订阅信息时回退列表顺序
            1 -> subIdsForSlot(sm, 0) ?: intArrayOf(allSubIds[0])
            2 -> subIdsForSlot(sm, 1) ?: (if (allSubIds.size >= 2) intArrayOf(allSubIds[1]) else allSubIds)
            else -> allSubIds
        }
        report = report.copy(total = subIds.size, clearing = clearing)

        for (subId in subIds) {
            val outcome = if (clearing) {
                clearTo(cm, subId, persistentRequested, viaShell)
            } else {
                applyTo(cm, subId, persistentRequested, viaShell)
            }
            if (outcome.ok) {
                report = report.copy(succeeded = report.succeeded + 1)
            } else if (report.failure == null) {
                report = report.copy(failure = outcome.error)
            }
            if (outcome.persistentUsed) report = report.copy(persistentUsed = true)
        }
        if (clearing && report.succeeded > 0) {
            getPrefs(context).edit().putBoolean(Prefs.PENDING_CLEAR, false).apply()
        }
        return report
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

    private data class Outcome(val ok: Boolean, val persistentUsed: Boolean = false, val error: String? = null)

    /** 写入一份覆写；持久化被拒时反向重试一次，最后回读版本指纹确认 */
    private fun applyTo(
        cm: CarrierConfigManager,
        subId: Int,
        persistent: Boolean,
        viaShell: ICarrierConfigLoader?,
    ): Outcome {
        val values = buildConfigBundle().apply {
            putInt(Keys.KEY_CONFIG_VERSION, BuildConfig.VERSION_CODE)
        }
        val primary = try {
            override(cm, subId, values, persistent, viaShell)
            Outcome(true, persistent)
        } catch (e: Throwable) {
            Logs.w("overrideConfig(subId=$subId, persistent=$persistent) 被拒绝: ${e.message}")
            null
        }
        val used = primary ?: try {
            override(cm, subId, values, !persistent, viaShell)
            Outcome(true, !persistent)
        } catch (e2: Throwable) {
            Logs.e("overrideConfig 对 subId=$subId 完全失败: ${e2.message}")
            return Outcome(false, error = e2.javaClass.simpleName + ": " + e2.message)
        }

        // 回读校验：版本指纹 + 关键键值必须真的落到该 subId 的配置里
        val verified = try {
            val check = cm.getConfigForSubId(subId)
            val versionOk = check != null && check.getInt(Keys.KEY_CONFIG_VERSION, 0) == BuildConfig.VERSION_CODE
            val volteOk = check == null || !values.containsKey(Keys.CARRIER_VOLTE_AVAILABLE) ||
                check.getBoolean(Keys.CARRIER_VOLTE_AVAILABLE, false) ==
                values.getBoolean(Keys.CARRIER_VOLTE_AVAILABLE, false)
            Logs.i("subId=$subId 回读：version=$versionOk volte=$volteOk persistent=${used.persistentUsed}")
            versionOk && volteOk
        } catch (e: Throwable) {
            Logs.e("subId=$subId 回读校验异常: ${e.message}")
            false
        }
        return used.copy(ok = verified, error = if (verified) null else "写入后回读校验未通过")
    }

    /** 清除覆写：传 null 让系统丢弃该 subId 的整份覆写 */
    private fun clearTo(
        cm: CarrierConfigManager,
        subId: Int,
        persistent: Boolean,
        viaShell: ICarrierConfigLoader?,
    ): Outcome {
        return try {
            override(cm, subId, null, persistent, viaShell)
            Outcome(true, persistent)
        } catch (e: Throwable) {
            try {
                override(cm, subId, null, !persistent, viaShell)
                Outcome(true, !persistent)
            } catch (e2: Throwable) {
                Logs.e("清除覆写 subId=$subId 失败: ${e2.message}")
                Outcome(false, error = e2.javaClass.simpleName + ": " + e2.message)
            }
        }
    }

    private fun override(
        cm: CarrierConfigManager,
        subId: Int,
        values: PersistableBundle?,
        persistent: Boolean,
        viaShell: ICarrierConfigLoader?,
    ) {
        if (viaShell != null) {
            viaShell.overrideConfig(subId, values, persistent)
        } else {
            Hidden.call(
                cm, "overrideConfig",
                arrayOf(Int::class.javaPrimitiveType!!, PersistableBundle::class.java, Boolean::class.javaPrimitiveType!!),
                arrayOf(subId, values, persistent)
            )
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

    /**
     * 根据用户开关组装覆写内容。
     *
     * v1.3 起**每个开关都写入显式值**（关闭 = false），因为 overrideConfig 是合并语义：
     * 旧实现只写入“打开”的键，关闭开关后上一次的 true 仍留在覆写里，界面看起来关了但实际没生效。
     */
    private fun buildConfigBundle(): PersistableBundle {
        val prefs = getPrefs(getContext())
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

        bundle.putBoolean(Keys.CARRIER_VOLTE_AVAILABLE, volte)
        bundle.putBoolean(Keys.EDITABLE_ENHANCED_4G_LTE, volte)
        bundle.putBoolean(Keys.HIDE_ENHANCED_4G_LTE, false)
        bundle.putBoolean(Keys.HIDE_LTE_PLUS_ICON, false)

        bundle.putBoolean(Keys.CARRIER_WFC_IMS_AVAILABLE, vowifi)
        bundle.putBoolean(Keys.CARRIER_WFC_SUPPORTS_WIFI_ONLY, vowifi)
        bundle.putBoolean(Keys.EDITABLE_WFC_MODE, vowifi)
        bundle.putBoolean(Keys.EDITABLE_WFC_ROAMING_MODE, vowifi)
        bundle.putBoolean(Keys.SHOW_WFC_ICON, vowifi)
        bundle.putInt(Keys.WFC_SPN_FORMAT_IDX, 6)

        bundle.putBoolean(Keys.CARRIER_VT_AVAILABLE, vt)
        bundle.putBoolean(Keys.CARRIER_SS_OVER_UT, ut)

        bundle.putBoolean(Keys.CROSS_SIM_IMS_AVAILABLE, crossSim)
        bundle.putBoolean(Keys.CROSS_SIM_ON_OPPORTUNISTIC, crossSim)

        bundle.putBoolean(Keys.VONR_ENABLED, vonr)
        bundle.putBoolean(Keys.VONR_SETTING_VISIBILITY, vonr)

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
            bundle.putIntArray(Keys.NR_SSRSRP_THRESHOLDS, intArrayOf(-128, -118, -108, -98))
        } else {
            // 空数组 = 不开放任何 NR 能力
            bundle.putIntArray(Keys.CARRIER_NR_AVAILABILITIES, intArrayOf())
        }
        return bundle
    }

    /** 一次应用的结果，写入 SharedPreferences 供界面展示真实状态 */
    private data class ApplyReport(
        val total: Int = 0,
        val succeeded: Int = 0,
        val persistentUsed: Boolean = false,
        val cleanupOk: Boolean = true,
        val clearing: Boolean = false,
        val failure: String? = null,
    ) {
        val ok: Boolean get() = total > 0 && succeeded == total

        fun summary(): String =
            "结果：$succeeded/$total 成功 · 持久化=$persistentUsed · 清理=$cleanupOk" +
                (failure?.let { " · 失败原因=$it" } ?: "")

        fun save(prefs: SharedPreferences) {
            prefs.edit()
                .putLong(Prefs.LAST_APPLY_AT, System.currentTimeMillis())
                .putBoolean(Prefs.LAST_APPLY_OK, ok)
                .putInt(Prefs.LAST_APPLY_TOTAL, total)
                .putInt(Prefs.LAST_APPLY_SUCCEEDED, succeeded)
                .putBoolean(Prefs.LAST_APPLY_PERSISTENT, persistentUsed)
                .putBoolean(Prefs.LAST_CLEANUP_OK, cleanupOk)
                .putBoolean(Prefs.LAST_APPLY_CLEAR, clearing)
                .putString(Prefs.LAST_APPLY_ERROR, failure)
                .apply()
        }

        companion object {
            fun start() = ApplyReport()
        }
    }

    companion object {
        private const val ARG_PID = "pid"
        private const val SANDBOX_BINDER_KEY = "binder"
    }
}
