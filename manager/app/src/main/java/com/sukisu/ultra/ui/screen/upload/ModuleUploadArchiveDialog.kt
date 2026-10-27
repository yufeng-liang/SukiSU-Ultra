package com.sukisu.ultra.ui.screen.upload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.LocalUiMode
import com.sukisu.ultra.ui.UiMode
import com.sukisu.ultra.ui.component.material.IconTile
import com.sukisu.ultra.ui.component.material.SegmentedColumn
import com.sukisu.ultra.ui.component.material.SegmentedListItem
import com.sukisu.ultra.ui.component.material.SegmentedSwitchItem
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
    onClearOrphans: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val summary = archivedSummary(state)
    // 挑包途中不许清：那一行正在把留存包复制到 staging，清空会把它删掉。
    val clearEnabled = state.archivedCount > 0 && state.pickingFor == null
    // 只有真存在「已卸载模块的包」时才出现这一项：平时列表里少一行，比一直摆着一个
    // 点不动的入口更干净。
    val orphanEnabled = state.orphanCount > 0 && state.pickingFor == null
    val orphanSummary = stringResource(R.string.module_upload_clear_orphan_zip_summary, state.orphanCount)

    if (LocalUiMode.current == UiMode.Miuix) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.module_upload_more),
            onDismissRequest = onDismissRequest,
            // 纵向留 24dp（内容区只补左右）：否则表单和底部按钮会贴住对话框上下边框。
            // 不传的话默认 insideMargin 是 24dp，会和下面 Column 自带的 24dp 叠加成左右各 48dp。
            insideMargin = DpSize(0.dp, 24.dp),
            content = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState())
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
                    if (state.orphanCount > 0) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            ArrowPreference(
                                title = stringResource(R.string.module_upload_clear_orphan_zip),
                                summary = orphanSummary,
                                startAction = {
                                    Icon(
                                        imageVector = Icons.Rounded.CleaningServices,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 6.dp),
                                        tint = colorScheme.onBackground,
                                    )
                                },
                                enabled = orphanEnabled,
                                onClick = onClearOrphans,
                            )
                        }
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
                    content = buildList {
                        add {
                            SegmentedSwitchItem(
                                icon = Icons.Rounded.FolderZip,
                                title = stringResource(R.string.module_upload_keep_zip),
                                summary = stringResource(R.string.module_upload_keep_zip_summary),
                                checked = state.keepZip,
                                onCheckedChange = onKeepZipChange,
                            )
                        }
                        add {
                            SegmentedListItem(
                                onClick = if (clearEnabled) onClearArchived else null,
                                enabled = clearEnabled,
                                headlineContent = {
                                    Text(stringResource(R.string.module_upload_clear_archived_zip))
                                },
                                supportingContent = { Text(summary) },
                                leadingContent = {
                                    IconTile(Icons.Rounded.DeleteOutline, null, enabled = clearEnabled)
                                },
                            )
                        }
                        if (state.orphanCount > 0) {
                            add {
                                SegmentedListItem(
                                    onClick = if (orphanEnabled) onClearOrphans else null,
                                    enabled = orphanEnabled,
                                    headlineContent = {
                                        Text(stringResource(R.string.module_upload_clear_orphan_zip))
                                    },
                                    supportingContent = { Text(orphanSummary) },
                                    leadingContent = {
                                        IconTile(Icons.Rounded.CleaningServices, null, enabled = orphanEnabled)
                                    },
                                )
                            }
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.close)) }
            },
        )
    }
}
