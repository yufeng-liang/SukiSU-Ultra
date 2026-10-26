package com.sukisu.ultra.ui.screen.upload

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.util.formatFileSize

/**
 * 「清理留存的包」那一行右边/下面显示的文字：留了几个、占多少。
 *
 * 两套界面共用一份，避免 Material 与 Miuix 对同一个数字给出不同措辞；
 * 一个都没留时走单独的文案，而不是显示「已留存 0 个，占用 0 B」。
 */
@Composable
internal fun archivedSummary(state: ModuleUploadListState): String {
    if (state.archivedCount <= 0) {
        return stringResource(R.string.module_upload_clear_archived_zip_summary_empty)
    }
    return stringResource(
        R.string.module_upload_clear_archived_zip_summary,
        state.archivedCount,
        formatFileSize(state.archivedBytes),
    )
}
