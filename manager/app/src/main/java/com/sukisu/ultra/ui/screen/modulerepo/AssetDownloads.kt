package com.sukisu.ultra.ui.screen.modulerepo

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.sukisu.ultra.ui.util.download
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** One asset's download, as the version list has to show it. */
@Immutable
data class AssetDownload(
    val isDownloading: Boolean = false,
    val progress: Int = 0,
    val uri: Uri? = null,
)

/**
 * The download state of every asset in the version list, keyed by download address and owned by the
 * detail page rather than by the row that started it.
 *
 * A row is a `LazyColumn` item: scroll it far enough and it is disposed, taking a `remember`ed
 * progress and "already downloaded" with it — while the download itself keeps running in
 * `DownloadService` and never stops. Coming back to a row that says "Download" again, with a
 * progress ring that starts over, is what that costs; the page this lives on survives scrolling.
 *
 * [onCompleted] is supplied by the page for the same reason: the completion callback belongs to no
 * composition — it arrives even after the row was recycled or the user left the page — so the page
 * has to be the one deciding what happens next, and losing it has to take that decision with it.
 */
@Stable
class AssetDownloads internal constructor(
    private val scope: CoroutineScope,
    private val onCompleted: (Uri) -> Unit,
) {
    private val states = mutableStateMapOf<String, AssetDownload>()

    fun of(url: String): AssetDownload = states[url] ?: AssetDownload()

    /** Starts downloading [asset], unless it already is. */
    fun start(asset: ReleaseAssetArg) {
        val url = asset.downloadUrl
        if (of(url).isDownloading) return
        states[url] = AssetDownload(isDownloading = true)
        scope.launch(Dispatchers.IO) {
            download(
                url = url,
                fileName = asset.name,
                altUrl = asset.downloadUrlFallback,
                onDownloaded = { uri ->
                    mutate(url) { it.copy(isDownloading = false, progress = 100, uri = uri) }
                    onCompleted(uri)
                },
                onProgress = { percent -> mutate(url) { it.copy(progress = percent) } },
            )
            // A failed download returns without calling anything back, so the "downloading" flag has
            // to be cleared here or the button would sit on a progress ring forever. On success this
            // only settles what the callback above already wrote, in either order.
            mutate(url) { it.copy(isDownloading = false) }
        }
    }

    /** The downloaded file is gone; the row goes back to offering a download. */
    fun forget(url: String) {
        states.remove(url)
    }

    /**
     * Applies [transform] to one entry on the main thread. Progress arrives on the download's IO
     * coroutine and completion on a main-thread handler, so they meet here and the read-modify-write
     * stays whole.
     */
    private fun mutate(url: String, transform: (AssetDownload) -> AssetDownload) {
        scope.launch(Dispatchers.Main) {
            states[url] = transform(states[url] ?: AssetDownload())
        }
    }
}

/** An [AssetDownloads] bound to this composition's lifetime. */
@Composable
fun rememberAssetDownloads(onCompleted: (Uri) -> Unit): AssetDownloads {
    val scope = rememberCoroutineScope()
    val callback = rememberUpdatedState(onCompleted)
    return remember(scope) { AssetDownloads(scope) { uri -> callback.value(uri) } }
}
