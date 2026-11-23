package com.zt.volte5g

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 系统版本兼容判定。
 *
 * 机制本身依赖隐藏 API（CarrierConfigLoader / IActivityManager），与 compileSdk 无关；
 * 真正会随补丁变化的是**权限委托的释放接口签名**，以及持久化覆写是否仍被允许。
 * 因此这里只做两件事：
 *  1. 识别当前构建（用于界面展示"是否已适配"）；
 *  2. 运行时探测 IActivityManager 上实际存在的 stopDelegateShellPermissionIdentity 变体。
 */
object SystemCompat {

    /** 已实机/上游验证过的构建前缀 → 展示名 */
    private val knownBuilds = listOf(
        "CP4A." to "Android 17 QPR2",
        "CP3A." to "Android 17 QPR1",
        "BP4A." to "Android 16 QPR2",
        "BP3A." to "Android 16 QPR1",
    )

    /** 2026-10 起的补丁级别（CVE-2025-48617 之后的持续演进） */
    private const val PATCH_2026_10 = 202610

    data class Info(
        val buildId: String,
        val incremental: String,
        val securityPatch: String,
        val apiLevel: Int,
        val release: String,
        val qprLabel: String?,
        val delegateVariant: DelegateVariant,
        val tier: Tier,
        val note: String,
    )

    enum class DelegateVariant(val label: String) {
        WITH_UID("带 UID 签名"),
        LEGACY("旧无参签名"),
        UNKNOWN("未知"),
    }

    enum class Tier { VERIFIED, EXPECTED, LEGACY }

    fun describe(context: Context): Info {
        val buildId = Build.ID.orEmpty()
        val qpr = knownBuilds.firstOrNull { buildId.startsWith(it.first) }?.second
        val variant = probeDelegateVariant()
        val patch = Build.VERSION.SECURITY_PATCH.orEmpty()

        val tier = when {
            // 已知构建前缀：明确标注过适配情况
            qpr == "Android 17 QPR2" -> Tier.VERIFIED
            qpr != null -> Tier.EXPECTED
            // 方法表显示已换成带 uid 的清理签名 → 属于 Android 17 代系统
            variant == DelegateVariant.WITH_UID -> Tier.EXPECTED
            // 2026-10 之后的补丁级别视为新一代
            patchYearMonth() >= PATCH_2026_10 -> Tier.EXPECTED
            else -> Tier.LEGACY
        }

        val note = when (tier) {
            Tier.VERIFIED -> "已按 Android 17 QPR2 适配：委托清理同时兼容新旧签名，写入结果按 SIM 回读校验。"
            Tier.EXPECTED -> "较新的系统构建：机制按委托链路工作，如异常请附系统构建号反馈。"
            Tier.LEGACY -> "较旧的系统构建：走兼容路径，能否持久化取决于该补丁级别。"
        }

        return Info(
            buildId = buildId,
            incremental = Build.VERSION.INCREMENTAL.orEmpty(),
            securityPatch = patch,
            apiLevel = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE.orEmpty(),
            qprLabel = qpr,
            delegateVariant = variant,
            tier = tier,
            note = note,
        )
    }

    /**
     * 探测 IActivityManager 上的 stopDelegateShellPermissionIdentity 签名。
     * 只读取方法表，不做任何调用，因此不会影响真实运行。
     *
     * 注意：Android 17 的 SDK_INT 仍是 36，因此不能用 SDK 版本区分系统代际，
     * 只能看实际存在的方法签名 —— 这正好也是运行时真正关心的事实。
     */
    private fun probeDelegateVariant(): DelegateVariant = runCatching {
        // 读取隐藏接口的方法表前先豁免限制，否则部分系统会直接抛 SecurityException
        Hidden.exemptAll()
        val clazz = Class.forName("android.app.IActivityManager")
        // AIDL 生成的接口把方法声明为 public abstract，用 declaredMethods 覆盖到继承链上的声明
        val methods = clazz.declaredMethods.filter { it.name == "stopDelegateShellPermissionIdentity" }
            .ifEmpty { clazz.methods.filter { it.name == "stopDelegateShellPermissionIdentity" } }
        val hasUid = methods.any { it.parameterCount == 1 }
        val hasLegacy = methods.any { it.parameterCount == 0 }
        when {
            // 新系统可能同时保留两者；真实运行时优先带 uid，这里如实反映
            hasUid && hasLegacy -> DelegateVariant.WITH_UID
            hasUid -> DelegateVariant.WITH_UID
            hasLegacy -> DelegateVariant.LEGACY
            else -> DelegateVariant.UNKNOWN
        }
    }.getOrDefault(DelegateVariant.UNKNOWN)

    /** 安全补丁日期 → 年月整数（如 2026-11 → 202611）；解析失败返回 0 */
    private fun patchYearMonth(): Int = runCatching {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(Build.VERSION.SECURITY_PATCH)
            ?: return 0
        SimpleDateFormat("yyyyMM", Locale.US).format(date).toIntOrNull() ?: 0
    }.getOrDefault(0)
}
