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
 * 本 fork 的 APK 形如 `SukiSU_mod_41151-release.apk`（versionName 是品牌标识 mod，
 * 版本号只在文件名末尾出现一次）；release 里同一批还躺着 8 个 `.ko`，以及可选
 * 的随机包名变体 `…-release-spoofed.apk`（不是给用户常规升级用的，排除掉）。
 * 认不出就返回 null，调用方按“没有更新”处理。
 */
internal fun matchSelfUpdateApk(name: String): Long? {
    if (!name.endsWith("-release.apk")) return null
    if (name.endsWith("-release-spoofed.apk")) return null
    // 上游用 SukiSU_v4.2.1_40994-release.apk 这套命名。本 fork 的 versionName 是
    // mod，绝不会以 v 开头；这里显式挡掉，是防「某次 release 误传了
    // 官方 APK」——那种情况下把用户引导去装它是覆盖安装不了的。
    if (name.startsWith("SukiSU_v")) return null
    val match = Regex("""^SukiSU_.+_(\d+)-release\.apk$""").find(name) ?: return null
    return match.groupValues[1].toLongOrNull()
}

/**
 * 检查管理器更新。
 *
 * 上游曾有一条独立的 CI 通道（manager-ci 滚动 release）与 `checkNewCiVersion`，
 * 但本 fork 不发布 manager-ci —— 两个入口最终都落到同一次 fork release 查询上，
 * 开着 CI 开关只是把同一个 latest 再查一遍，首页会同时弹出两张一模一样的更新卡。
 * 所以本 fork 只保留这一条通道，CI 开关与它的全部接线已一并移除。
 */
fun checkNewVersion(): LatestVersionInfo = checkLatestRelease()

private fun checkLatestRelease(): LatestVersionInfo {
    if (!isNetworkAvailable(ksuApp)) return LatestVersionInfo()
    val url = "https://api.github.com/repos/$SELF_UPDATE_REPO/releases/latest"
    return runCatching {
        ksuApp.okhttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w("UpdateCheck", "latest: HTTP ${response.code}")
                return@use LatestVersionInfo()
            }
            val json = org.json.JSONObject(response.body.string())
            val changelog = json.optString("body")
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val versionCode = matchSelfUpdateApk(asset.getString("name")) ?: continue
                return@use LatestVersionInfo(
                    versionCode,
                    asset.getString("browser_download_url"),
                    changelog,
                )
            }
            LatestVersionInfo()
        }
    }.onFailure {
        Log.w("UpdateCheck", "latest: update check failed", it)
    }.getOrDefault(LatestVersionInfo())
}
