package com.sukisu.ultra.ui.screen.upload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.LocalUiMode
import com.sukisu.ultra.ui.UiMode
import com.sukisu.ultra.ui.component.material.IconTile
import com.sukisu.ultra.ui.component.material.SegmentedColumn
import com.sukisu.ultra.ui.component.material.SegmentedListItem
import com.sukisu.ultra.ui.component.material.SegmentedSwitchItem
import com.sukisu.ultra.ui.util.formatFileSize
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 顶栏「更多」背后的留存设置面板：一个开关 + 一个清理入口。
 *
 * 为什么是面板而不是下拉菜单：两套主题都要能放「带副标题的开关」，而 Miuix 的
 * 下拉项只有一行文字，塞进去会跟 Material 侧长得完全不像；菜单里嵌开关也会让
 * 「点行」和「点开关」变成两个含义不同的手势。
 *
 * 清理按钮不在这里做二次确认：调用方（[ModuleUploadScreen]）走系统确认对话框，
 * 那里能显示「几个包、占多少」的具体数字，而面板里再弹一层对话框太重。
 */
@Composable
fun ModuleUploadArchiveDialog(
    state: ModuleUploadListState,
    onKeepZipChange: (Boolean) -> Unit,
    onClearArchived: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val summary = archivedSummary(state)
    val clearEnabled = state.archivedCount > 0

    if (LocalUiMode.current == UiMode.Miuix) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.module_upload_more),
            onDismissRequest = onDismissRequest,
            content = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        SwitchPreference(
                            title = stringResource(R.string.module_upload_keep_zip),
                            summary = stringResource(R.string.module_upload_keep_zip_summary),
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.FolderZip,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp),
                                    tint = colorScheme.onBackground,
                                )
                            },
                            checked = state.keepZip,
                            onCheckedChange = onKeepZipChange,
                        )
                    }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        ArrowPreference(
                            title = stringResource(R.string.module_upload_clear_archived_zip),
                            summary = summary,
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp),
                                    tint = colorScheme.onBackground,
                                )
                            },
                            enabled = clearEnabled,
                            onClick = onClearArchived,
                        )
                    }
                    MiuixTextButton(
                        text = stringResource(R.string.close),
                        onClick = onDismissRequest,
                        modifier = Modifier.fillMaxWidth(),
                        colors = MiuixButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(stringResource(R.string.module_upload_more)) },
            text = {
                SegmentedColumn(
                    modifier = Modifier.fillMaxWidth(),
                    content = listOf(
                        {
                            SegmentedSwitchItem(
                                icon = Icons.Rounded.FolderZip,
                                title = stringResource(R.string.module_upload_keep_zip),
                                summary = stringResource(R.string.module_upload_keep_zip_summary),
                                checked = state.keepZip,
                                onCheckedChange = onKeepZipChange,
                            )
                        },
                        {
                            SegmentedListItem(
                                onClick = if (clearEnabled) onClearArchived else null,
                                enabled = clearEnabled,
                                headlineContent = {
                                    Text(stringResource(R.string.module_upload_clear_archived_zip))
                                },
                                supportingContent = { Text(summary) },
                                leadingContent = {
                                    IconTile(Icons.Rounded.DeleteOutline, null)
                                },
                            )
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.close)) }
            },
        )
    }
}
