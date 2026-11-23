package com.zt.volte5g

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 应用内更新。
 *
 * 数据源：GitHub Releases（`/releases/latest`），只读取公开信息：
 *  - `tag_name` 用作版本号（形如 v1.3）
 *  - `body` 作为更新说明展示
 *  - 附件里的 `.apk` 作为下载目标，若 release 附带 `digest` 则下载后校验 SHA-256
 *
 * 更新说明与下载地址都来自外部，只当文本显示、只按固定域名下载，不执行其中任何内容。
 * 下载完成后用 FileProvider 交给系统安装器；安装前会检查“允许安装未知应用”权限。
 */
object Updater {

    private const val OWNER = "K1tor"
    private const val REPO = "PixelVolte5G"
    private const val API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val USER_AGENT = "PixelVolte5G-Updater"
    private const val APK_MIME = "application/vnd.android.package-archive"

    data class Release(
        val tag: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val size: Long,
        val sha256: String?,
        val assetId: Long = 0L,
    ) {
        /** 从 v1.3.1 这类 tag 解析出可比较的版本段 */
        val version: List<Int>
            get() = tag.trim().removePrefix("v").removePrefix("V")
                .split('.', '-', '+')
                .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }

        /**
         * 下载地址候选，按优先级尝试。
         *
         * 国内网络常见情况：`github.com` 连不通，但 `api.github.com` 可用。
         * 因此优先用 API 的附件端点（Accept: application/octet-stream，会 302 到 CDN），
         * 再用 release 里的 browser_download_url 兜底。
         */
        fun downloadUrls(): List<String> = buildList {
            if (assetId > 0) add("https://api.github.com/repos/$OWNER/$REPO/releases/assets/$assetId")
            if (apkUrl.isNotBlank()) add(apkUrl)
        }
    }

    data class Downloaded(val file: File, val release: Release)

    /** 当前安装版本的可比较段 */
    fun currentVersion(): List<Int> =
        BuildConfig.VERSION_NAME.split('.', '-', '+').mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }

    /** 语义化比较：返回 true 表示 candidate 比 current 新 */
    fun isNewer(candidate: List<Int>, current: List<Int>): Boolean {
        val size = maxOf(candidate.size, current.size)
        for (i in 0 until size) {
            val a = candidate.getOrElse(i) { 0 }
            val b = current.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** 查询最新 release；调用方负责放到后台线程 */
    @Throws(Exception::class)
    fun fetchLatest(): Release {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("GitHub 返回 HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name").ifBlank {
                throw IllegalStateException("release 缺少 tag_name")
            }
            val assets = json.optJSONArray("assets") ?: throw IllegalStateException("release 没有附件")
            var apk: JSONObject? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apk = a
                    break
                }
            }
            if (apk == null) throw IllegalStateException("release 里没有 APK 附件")

            val digest = apk.optString("digest").takeIf { it.startsWith("sha256:") }
                ?.removePrefix("sha256:")?.lowercase()
            return Release(
                tag = tag,
                title = json.optString("name").ifBlank { tag },
                notes = json.optString("body").trim(),
                apkUrl = apk.optString("browser_download_url"),
                apkName = apk.optString("name"),
                size = apk.optLong("size"),
                sha256 = digest,
                assetId = apk.optLong("id"),
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 下载 APK 到 cacheDir；带进度回调（0..100）与取消检查。
     *
     * 依次尝试 [Release.downloadUrls]：API 附件端点优先（国内 `api.github.com` 常可通，
     * 而 `github.com` 不通），失败再换 browser_download_url，每个地址重试一次。
     * 若 release 给出 sha256，则下载后校验，不一致直接删除并报错。
     */
    @Throws(Exception::class)
    fun download(
        context: Context,
        release: Release,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): Downloaded {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val target = File(dir, release.apkName.ifBlank { "update.apk" })

        val urls = release.downloadUrls()
        if (urls.isEmpty()) throw IllegalStateException("release 没有可用的下载地址")

        var lastError: Throwable? = null
        for ((index, url) in urls.withIndex()) {
            for (attempt in 1..2) {
                if (isCancelled()) throw InterruptedException("已取消")
                try {
                    downloadFrom(url, target, release, onProgress, isCancelled)
                    verify(target, release)
                    return Downloaded(target, release)
                } catch (e: InterruptedException) {
                    runCatching { target.delete() }
                    throw e
                } catch (e: Throwable) {
                    runCatching { if (target.exists()) target.delete() }
                    lastError = e
                    Logs.w(
                        "下载尝试失败（地址 ${index + 1}/${urls.size}，第 $attempt 次）: " +
                            "${e.javaClass.simpleName} ${e.message}"
                    )
                    if (e is java.net.UnknownHostException) break // 域名不通，换下一个地址
                }
            }
        }
        throw lastError ?: IllegalStateException("下载失败")
    }

    private fun downloadFrom(
        initialUrl: String,
        target: File,
        release: Release,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        var conn: HttpURLConnection? = null
        var redirects = 0
        var url = initialUrl
        try {
            // 手动跟随重定向：GitHub 会 302 到 release-assets.githubusercontent.com
            while (true) {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/octet-stream")
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }
                conn = c
                val code = c.responseCode
                if (code in 300..399) {
                    val next = c.getHeaderField("Location")
                        ?: throw IllegalStateException("重定向缺少 Location")
                    c.disconnect()
                    if (++redirects > 5) throw IllegalStateException("重定向次数过多")
                    url = if (next.startsWith("http")) next else URL(URL(url), next).toString()
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("下载失败 HTTP $code")
                }
                break
            }

            val total = conn!!.contentLengthLong.takeIf { it > 0 } ?: release.size
            var written = 0L
            var lastPercent = -1
            FileOutputStream(target).use { out ->
                conn!!.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw InterruptedException("已取消")
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        if (total > 0) {
                            val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (total > 0 && written < total) {
                throw IllegalStateException("下载不完整（$written/$total 字节）")
            }
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun verify(target: File, release: Release) {
        val expected = release.sha256 ?: return
        val actual = sha256(target)
        if (!actual.equals(expected, ignoreCase = true)) {
            target.delete()
            throw IllegalStateException("安装包校验失败，已删除下载文件")
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 是否已允许安装未知来源应用 */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** 跳转到本应用的“安装未知应用”授权页 */
    fun openInstallPermission(context: Context) {
        runCatching {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        }.onFailure {
            runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) }
        }
    }

    /** 交给系统安装器 */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
