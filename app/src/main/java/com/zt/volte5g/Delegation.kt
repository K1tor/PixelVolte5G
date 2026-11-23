package com.zt.volte5g

/**
 * 壳权限委托（shell permission identity）的运行时兼容层。
 *
 * Android 17 把 `IActivityManager.stopDelegateShellPermissionIdentity()` 换成了带 UID 的签名
 * （参考 pixel-volte-patch #472 实机堆栈、PR #487 的修法）。旧实现直接调用无参版本，
 * 会在覆写**已经写入成功之后**抛 NoSuchMethodError，让界面误判为失败。
 *
 * 这里两个签名都声明在编译桩里，运行时先试带 UID 的、再回退无参的，
 * 调用失败只记录，不影响已经拿到的写入结果。
 */
object Delegation {

    /** 本次 start 使用的 uid，stop 时传回去 */
    @Volatile
    private var delegatedUid: Int = -1

    fun start(am: android.app.IActivityManager, uid: Int): Boolean {
        return try {
            am.startDelegateShellPermissionIdentity(uid, null)
            delegatedUid = uid
            true
        } catch (e: Throwable) {
            Logs.w("startDelegateShellPermissionIdentity 失败(uid=$uid): ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    /**
     * 释放委托。返回是否成功释放（失败不影响写入结果，仅用于诊断）。
     * 优先使用 Android 17+ 的 `stopDelegateShellPermissionIdentity(uid)`。
     */
    fun stop(am: android.app.IActivityManager): Boolean {
        val uid = if (delegatedUid >= 0) delegatedUid else android.os.Process.myUid()
        // 1) Android 17 及以上：带 uid
        try {
            am.stopDelegateShellPermissionIdentity(uid)
            delegatedUid = -1
            return true
        } catch (e: Throwable) {
            // 方法不存在（旧系统）或服务端拒绝，继续尝试旧签名
            Logs.w("stop(uid=$uid) 不可用: ${e.javaClass.simpleName}")
        }
        // 2) 旧系统：无参
        return try {
            am.stopDelegateShellPermissionIdentity()
            delegatedUid = -1
            true
        } catch (e: Throwable) {
            Logs.e("两种 stopDelegateShellPermissionIdentity 都失败: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }
}
