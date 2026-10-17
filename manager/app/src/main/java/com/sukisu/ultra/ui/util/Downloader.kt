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

fun checkNewVersion(): LatestVersionInfo {
    if (!isNetworkAvailable(ksuApp)) return LatestVersionInfo()
    val url = "https://api.github.com/repos/SukiSU-Ultra/SukiSU-Ultra/releases/latest"
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

                val releaseApkRegex =
                    Regex("""^SukiSU_v(.+?)_(\d+)-release\.apk$""")

                val assets = json.getJSONArray("assets")
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")

                    val matchResult = releaseApkRegex.find(name) ?: continue
                    val versionName = matchResult.groupValues[1]
                    val versionCode = matchResult.groupValues[2].toLong()
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
