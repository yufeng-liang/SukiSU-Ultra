package com.sukisu.ultra.ui.screen.settings.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.sukisu.ultra.ui.component.material.SegmentedColumn
import com.sukisu.ultra.ui.component.material.SegmentedListItem

/**
 * 一次备份的详情：这一组里到底有哪些条目。
 *
 * 列表上不铺条目，是因为一次备份 11 个模块就是 11 行；而恢复只在这里、只由按钮触发——
 * 列表行点一下就恢复，在 root 工具里误触一次就是把设备拉回上一个状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupDetailMaterial(
    group: BackupGroup,
    state: BackupUiState,
    actions: BackupActions,
    snackbarHostState: SnackbarHostState,
) {
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
                                // 这一组存在哪：恢复之前该知道文件是从哪儿读的，尤其是勾了两侧之后。
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
                }
            }
            items(group.rows, key = { it.id }) { row ->
                SegmentedColumn {
                    item {
                        SegmentedListItem(
                            // 这一行不恢复任何东西：恢复要按右边那个按钮。
                            onClick = {},
                            enabled = !state.loading,
                            headlineContent = {
                                Text(
                                    if (row.isRollback && !group.isRollback) {
                                        row.title + BackupListFormatter.SEPARATOR +
                                            stringResource(R.string.backup_rollback_badge)
                                    } else {
                                        row.title
                                    },
                                )
                            },
                            supportingContent = { if (row.subtitle.isNotBlank()) Text(row.subtitle) },
                            trailingContent = {
                                Row {
                                    // 恢复/导出要挡住并发：两次操作的结果都写同一个 message，
                                    // 谁后落地谁覆盖，用户可能永远看不到先失败的那一次。
                                    TextButton(onClick = { actions.onRestore(row) }, enabled = !state.loading) {
                                        Text(stringResource(R.string.backup_restore))
                                    }
                                    TextButton(onClick = { actions.onExport(row) }, enabled = !state.loading) {
                                        Text(stringResource(R.string.backup_export))
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
