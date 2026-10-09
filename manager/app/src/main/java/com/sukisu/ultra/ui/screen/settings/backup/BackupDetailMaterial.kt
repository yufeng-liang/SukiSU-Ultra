package com.sukisu.ultra.ui.screen.settings.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // 恢复和导出都作用在勾选上，没勾任何一项时它们按不动；
                                // 删除动的是整组，跟勾选无关。
                                OutlinedButton(
                                    onClick = actions.onShareSelected,
                                    enabled = !busy && selected > 0,
                                ) {
                                    Text(stringResource(R.string.backup_detail_share))
                                }
                                Button(
                                    onClick = actions.onRestoreSelected,
                                    enabled = !busy && selected > 0,
                                ) {
                                    Text(stringResource(R.string.backup_detail_restore))
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            OutlinedButton(
                                onClick = actions.onDeleteGroup,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = stringResource(R.string.backup_detail_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
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
                }
            }
            items(group.rows, key = { it.id }) { row ->
                SegmentedColumn {
                    item {
                        SegmentedCheckboxItem(
                            title = entryTitle(row, group),
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
