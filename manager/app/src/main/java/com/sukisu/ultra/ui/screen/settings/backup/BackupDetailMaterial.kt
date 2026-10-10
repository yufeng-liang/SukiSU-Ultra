package com.sukisu.ultra.ui.screen.settings.backup

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
import androidx.compose.material3.TextButton
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
                                val summary = groupSummary(group, state.showsOriginBadge)
                                // 这一组存在哪：恢复/删除之前该知道动的是哪一侧的文件。
                                val location = BackupLocation.full(group.origin, state.cloudSavedUrl)
                                Text(
                                    if (location.isBlank()) {
                                        summary
                                    } else {
                                        summary + BackupListFormatter.SEPARATOR + stringResource(
                                            if (group.origin == BackupOrigin.CLOUD) {
                                                R.string.backup_location_cloud
                                            } else {
                                                R.string.backup_location_local
                                            },
                                            location,
                                        )
                                    },
                                )
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
                            // 删除是这一页最重的一件事，但它不是主按钮：淡红底配正红字，实心大红
                            // 会盖过上面那两个真正要按的按钮；只写红字又容易被当成说明文字。
                            TextButton(
                                onClick = actions.onDeleteGroup,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.error,
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
                            summary = stringResource(R.string.backup_module_selected, selected, group.rows.size),
                            checked = state.allGroupEntriesSelected,
                            onCheckedChange = actions.onSetAllGroupEntries,
                        )
                    }
                    group.rows.forEach { row ->
                        item {
                            SegmentedCheckboxItem(
                                title = entryTitle(row),
                                summary = row.subtitle.takeIf { it.isNotBlank() },
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
