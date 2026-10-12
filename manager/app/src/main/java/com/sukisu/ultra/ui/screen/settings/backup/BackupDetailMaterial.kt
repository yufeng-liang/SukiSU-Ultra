package com.sukisu.ultra.ui.screen.settings.backup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.data.backup.BackupOrigin
import com.sukisu.ultra.ui.component.material.SegmentedCheckboxItem
import com.sukisu.ultra.ui.component.material.SegmentedColumn
import com.sukisu.ultra.ui.component.material.SegmentedListItem

/** 删除按钮那条描边的透明度：全实心红边会把这颗按钮抬到和主按钮一样重。 */
private const val DELETED_BORDER_ALPHA = 0.5f

/**
 * 一次备份的详情：这一组里到底有哪些条目。
 *
 * 列表上不铺条目，是因为一次备份 11 个模块就是 11 行。勾选之后批量恢复/导出：一次备份本来
 * 就是"一整份"，逐个点按钮反而慢；而"点一下行就恢复"在 root 工具里等于给误触点了火，
 * 所以恢复只发生在按按钮的时候。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupDetailMaterial(
    group: BackupGroup,
    state: BackupUiState,
    actions: BackupActions,
    snackbarHostState: SnackbarHostState,
) {
    val selected = state.openGroupSelection.size
    val busy = state.loading

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(group.label) },
                navigationIcon = {
                    IconButton(onClick = actions.onCloseGroup) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SegmentedColumn {
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(group.label) },
                            supportingContent = {
                                // 这一组存在哪：恢复/删除之前该知道动的是哪些地方的文件。
                                // 本机和云端都写过就是两行（两处地址各不相同）。
                                Text(groupHeaderText(group, state.cloudSavedUrl))
                            },
                        )
                    }
                    item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            // 两个按钮等宽：宽度跟着文字走的话，一行里一长一短看着像没对齐的
                            // 标签，而这两个动作是一对——"导出"和"恢复"都对勾选项整体生效。
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // 恢复和导出都作用在勾选上，没勾任何一项时它们按不动；
                                // 删除动的是整组，跟勾选无关。
                                OutlinedButton(
                                    onClick = actions.onShareSelected,
                                    enabled = !busy && selected > 0,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(R.string.backup_detail_share))
                                }
                                Button(
                                    onClick = actions.onRestoreSelected,
                                    enabled = !busy && selected > 0,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(R.string.backup_detail_restore))
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                            // 删除是这一页最重的一件事，但它不是主按钮。
                            //
                            // 不填底色：这套配色里的 errorContainer 是一块偏暖的珊瑚色，铺满整行
                            // 之后成了整页最大的一块颜色，压在冷色调的页面上就是那句"配色有点丑"。
                            // 走描边——红字 + 半透明红边，形态和上面那个「导出/分享」一致，既看得出
                            // 是个按钮（纯红字会被读成说明文字），又不像一整块警告色。
                            OutlinedButton(
                                onClick = actions.onDeleteGroup,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.error.copy(alpha = DELETED_BORDER_ALPHA),
                                ),
                            ) {
                                Text(stringResource(R.string.backup_detail_delete))
                            }
                        }
                    }
                }
            }
            // 全选和条目放在同一张卡里（和主列表的模块勾选一样）：一条一张卡在 11 条时
            // 就是一堵卡片墙，而它们本来就是同一份备份里的东西。
            item {
                SegmentedColumn {
                    item {
                        SegmentedCheckboxItem(
                            title = stringResource(R.string.backup_detail_select_all),
                            // 两侧都有时"已选 2/2"看着像要恢复两遍：同一句话在恢复结果里也会出现，
                            // 用户在按下去之前和之后看到的是同一句。
                            summary = buildList {
                                add(
                                    stringResource(
                                        R.string.backup_module_selected,
                                        selected,
                                        group.rows.size,
                                    ),
                                )
                                if (group.origins.size > 1) {
                                    add(stringResource(R.string.backup_restore_deduped))
                                }
                            }.joinToString(BackupListFormatter.SEPARATOR),
                            checked = state.allGroupEntriesSelected,
                            onCheckedChange = actions.onSetAllGroupEntries,
                        )
                    }
                    // 两侧合起来列之后，同名条目在本机和云端各有一条：来源得跟着行走，
                    // 否则"恢复/删除这一条"动的是哪一侧就看不出来。
                    val showOrigin = group.origins.size > 1
                    group.rows.forEach { row ->
                        item {
                            SegmentedCheckboxItem(
                                title = entryTitle(row),
                                summary = listOfNotNull(
                                    row.originLabel.takeIf { showOrigin },
                                    row.subtitle.takeIf { it.isNotBlank() },
                                ).joinToString(BackupListFormatter.SEPARATOR).takeIf { it.isNotEmpty() },
                                checked = row.id in state.openGroupSelected,
                                onCheckedChange = { actions.onToggleGroupEntry(row.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}
