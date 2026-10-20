package com.sukisu.ultra.ui.util

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import com.sukisu.ultra.data.registry.ExpectedFile
import com.sukisu.ultra.ksuApp
import com.sukisu.ultra.ui.util.module.LatestVersionInfo
import okhttp3.Request

/**
 * @author weishu
 * @date 2023/6/22.
 *
 * [altUrl] is tried once when [url] fails, which lets a repository whose declared asset
 * host has moved still install: the index keeps the old host while the files follow the
 * project to its new one.
 *
 * [expected] is the size + SHA-256 a registry release declared; [onFailed] reports the last
 * failed state (including a verification failure) instead of returning silently.
 */
suspend fun download(
    url: String,
    fileName: String,
    altUrl: String? = null,
    onDownloaded: (Uri) -> Unit = {},
    onDownloading: () -> Unit = {},
    onProgress: (Int) -> Unit = {},
    expected: ExpectedFile? = null,
    onFailed: (DownloadManager.DownloadState) -> Unit = {},
) {
    onDownloading()

    val candidates = listOfNotNull(url, altUrl?.takeIf { it.isNotBlank() && it != url })
    var lastState: DownloadManager.DownloadState? = null
    for ((index, candidate) in candidates.withIndex()) {
        val downloadId = DownloadManager.enqueue(
            context = ksuApp,
            url = candidate,
            fileName = fileName,
            onCompleted = onDownloaded,
            expected = expected,
        )

        val state = DownloadManager.downloads
            .onEach { map -> map[downloadId]?.let { onProgress(it.progress) } }
            .first { map ->
                val status = map[downloadId]?.status
                status == DownloadManager.Status.COMPLETED ||
                    status == DownloadManager.Status.FAILED
            }[downloadId]

        if (state?.status == DownloadManager.Status.COMPLETED) return
        lastState = state
        if (index < candidates.lastIndex) {
            Log.w("Downloader", "download failed for $candidate, retrying ${candidates[index + 1]}")
        }
    }

    val failed = lastState
    if (failed != null) withContext(Dispatchers.Main) { onFailed(failed) }
}

internal suspend fun isDownloadAvailable(uri: Uri): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        ksuApp.contentResolver.openFileDescriptor(uri, "r").use { it != null }
    }.getOrDefault(false)
}

/**
 * 管理器自身更新的检查地址。
 *
 * 上游原实现写死的是官方仓库 SukiSU-Ultra/SukiSU-Ultra，但本机 APK 是 fork 版本：
 * versionCode 走 fork 自己的提交数（manager/build.gradle.kts 的 getVersionCode），
 * 与官方不同源，两者不可比。官方版本号一旦反超，用户会被引导去下载官方 APK——
 * 签名与本机不同，Android 不允许覆盖安装，只能卸载重装（仓库源配置会丢）。
 * 所以这里改成查本 fork 的最新 release。
 */
private const val SELF_UPDATE_REPO = "yufeng-liang/SukiSU-Ultra"

/**
 * 从 release 资产名里认出管理器 APK 并抽出 versionCode。
 *
 * 本 fork 的 APK 形如 `SukiSU_custom-repo.backup_41149-release.apk`（versionName 里
 * 带 `.`，不能假设分隔符全是下划线）；release 里同一批还躺着 8 个 `.ko`，以及可选
 * 的随机包名变体 `…-release-spoofed.apk`（不是给用户常规升级用的，排除掉）。
 * 认不出就返回 null，调用方按“没有更新”处理。
 */
internal fun matchSelfUpdateApk(name: String): Long? {
    if (!name.endsWith("-release.apk")) return null
    if (name.endsWith("-release-spoofed.apk")) return null
    // 上游用 SukiSU_v4.2.1_40994-release.apk 这套命名。本 fork 的 versionName 是
    // custom-repo&backup，绝不会以 v 开头；这里显式挡掉，是防「某次 release 误传了
    // 官方 APK」——那种情况下把用户引导去装它是覆盖安装不了的。
    if (name.startsWith("SukiSU_v")) return null
    val match = Regex("""^SukiSU_.+_(\d+)-release\.apk$""").find(name) ?: return null
    return match.groupValues[1].toLongOrNull()
}

fun checkNewVersion(): LatestVersionInfo {
    if (!isNetworkAvailable(ksuApp)) return LatestVersionInfo()
    val url = "https://api.github.com/repos/$SELF_UPDATE_REPO/releases/latest"
    // default null value if failed
    val defaultValue = LatestVersionInfo()
    runCatching {
        ksuApp.okhttpClient.newCall(Request.Builder().url(url).build()).execute()
            .use { response ->
                if (!response.isSuccessful) {
                    return defaultValue
                }
                val body = response.body.string()
                val json = org.json.JSONObject(body)
                val changelog = json.optString("body")

                val assets = json.getJSONArray("assets")
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")

                    val versionCode = matchSelfUpdateApk(name) ?: continue
                    val downloadUrl = asset.getString("browser_download_url")

                    return LatestVersionInfo(
                        versionCode,
                        downloadUrl,
                        changelog
                    )
                }

            }
    }
    return defaultValue
}
