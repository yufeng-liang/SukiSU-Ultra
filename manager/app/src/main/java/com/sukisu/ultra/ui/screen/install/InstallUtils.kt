package com.sukisu.ultra.ui.screen.install

import android.content.Context
import android.net.Uri
import android.os.Parcelable
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import com.sukisu.ultra.R

@Parcelize
sealed class InstallMethod : Parcelable {
    data class SelectFile(
        val uri: Uri? = null,
        @get:StringRes override val label: Int = R.string.select_file,
        override val summary: String?
    ) : InstallMethod()

    data class DownloadFile(
        val url: String? = null,
        val partition: String? = null,
        @get:StringRes override val label: Int = R.string.download_file,
        override val summary: String?
    ) : InstallMethod()

    data object DirectInstall : InstallMethod() {
        override val label: Int
            get() = R.string.direct_install
    }

    data object DirectInstallToInactiveSlot : InstallMethod() {
        override val label: Int
            get() = R.string.install_inactive_slot
    }

    data class HorizonKernel(
        val uri: Uri? = null,
        val slot: String? = null,
        @get:StringRes override val label: Int = R.string.horizon_kernel,
        override val summary: String? = null
    ) : InstallMethod()

    abstract val label: Int

    @IgnoredOnParcel
    open val summary: String? = null
}

/**
 * The file name a picked document should be shown as.
 *
 * DocumentsUI hands back `content://` URIs whose last path segment is usually
 * the provider's document id (a bare number like `378`), not the file name, so
 * ask the provider first and only fall back to the URI itself.
 */
fun displayNameOf(context: Context, uri: Uri): String? {
    val fromProvider = try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx != -1 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (_: Throwable) {
        null
    }

    return fromProvider?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment
}

fun isKoFile(context: Context, uri: Uri): Boolean =
    displayNameOf(context, uri)?.endsWith(".ko", ignoreCase = true) == true
