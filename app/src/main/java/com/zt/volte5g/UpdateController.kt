package com.zt.volte5g

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用内更新控制器：把网络/文件操作放到单线程池，结果回主线程。
 *
 * 状态机：Idle → Checking → (UpToDate | Available) → Downloading → Downloaded → Installing
 * 任何一步失败都回到可重试状态，并保留失败原因给界面展示。
 */
class UpdateController(
    private val context: Context,
    private val listener: Listener,
) {

    interface Listener {
        fun onUpdateState(state: State)
    }

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data class UpToDate(val version: String) : State()
        data class Available(val release: Updater.Release) : State()
        data class Downloading(val release: Updater.Release, val percent: Int) : State()
        data class Downloaded(val file: java.io.File, val release: Updater.Release) : State()
        data class Failed(val message: String, val release: Updater.Release? = null) : State()
        data object NeedsPermission : State()
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val cancelFlag = AtomicBoolean(false)

    /** 最近一次查到的可用版本，供“下载/安装”按钮使用 */
    var latest: Updater.Release? = null
        private set
    private var downloaded: Updater.Downloaded? = null

    fun shutdown() {
        cancelFlag.set(true)
        worker.shutdownNow()
    }

    fun cancelDownload() {
        cancelFlag.set(true)
    }

    private fun post(state: State) {
        main.post { listener.onUpdateState(state) }
    }

    /** 检查更新；silent=true 时不把“已是最新”当成打扰 */
    fun check(silent: Boolean = false) {
        if (!silent) post(State.Checking)
        worker.execute {
            try {
                val release = Updater.fetchLatest()
                latest = release
                val newer = Updater.isNewer(release.version, Updater.currentVersion())
                post(
                    if (newer) State.Available(release)
                    else State.UpToDate(release.tag)
                )
            } catch (e: Throwable) {
                Logs.w("检查更新失败: ${e.javaClass.simpleName} ${e.message}")
                if (!silent) post(State.Failed(friendly(e), latest))
            }
        }
    }

    /** 下载当前 latest；若之前已下载过则直接复用 */
    fun download() {
        val release = latest ?: run {
            check()
            return
        }
        downloaded?.let {
            post(State.Downloaded(it.file, it.release))
            return
        }
        cancelFlag.set(false)
        post(State.Downloading(release, 0))
        worker.execute {
            try {
                val result = Updater.download(
                    context = context,
                    release = release,
                    onProgress = { percent -> post(State.Downloading(release, percent)) },
                    isCancelled = { cancelFlag.get() },
                )
                downloaded = result
                post(State.Downloaded(result.file, result.release))
            } catch (e: InterruptedException) {
                post(State.Failed(context.getString(R.string.update_cancelled), release))
            } catch (e: Throwable) {
                Logs.w("下载更新失败: ${e.javaClass.simpleName} ${e.message}")
                post(State.Failed(friendly(e), release))
            }
        }
    }

    /** 申请安装：已授权则直接交给系统安装器，否则先引导授权 */
    fun install() {
        val d = downloaded ?: run {
            post(State.Failed(context.getString(R.string.update_no_network), latest))
            return
        }
        if (!Updater.canInstall(context)) {
            post(State.NeedsPermission)
            return
        }
        try {
            Updater.install(context, d.file)
        } catch (e: Throwable) {
            Logs.e("拉起安装器失败: ${e.message}")
            post(State.Failed(e.message ?: "无法打开安装器", d.release))
        }
    }

    /** 用户从系统设置返回后调用：若已授权则继续安装 */
    fun installIfPermitted() {
        if (downloaded != null && Updater.canInstall(context)) install()
    }

    private fun friendly(e: Throwable): String = when {
        e is java.net.UnknownHostException -> context.getString(R.string.update_no_network)
        e is java.net.SocketTimeoutException -> "连接超时，请稍后重试"
        e is javax.net.ssl.SSLException -> "安全连接失败，请稍后重试"
        e.message != null -> e.message!!
        else -> e.javaClass.simpleName
    }
}
