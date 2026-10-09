package com.sukisu.ultra.ui.screen.settings.backup

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.data.backup.AutoBackupOutcome
import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin
import com.sukisu.ultra.data.backup.WebDavPresets
import com.sukisu.ultra.ui.component.material.SegmentedColumn
import com.sukisu.ultra.ui.component.material.SegmentedListItem
import com.sukisu.ultra.ui.component.material.SegmentedRadioItem
import com.sukisu.ultra.ui.component.material.SegmentedSwitchItem
import com.sukisu.ultra.ui.util.BackupText

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BackupMaterial(
    state: BackupUiState,
    actions: BackupActions,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        // 整页只有一个滚动容器。以前这里是不滚动的 Column 套 LazyColumn，云端表单展开后
        // 下面那截会被顶出屏幕而且拉不回来——列表和表单抢同一块高度，谁在后面谁消失。
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SegmentedColumn {
                    item {
                        val rotation by animateFloatAsState(
                            targetValue = if (state.cloudExpanded) 180f else 0f,
                            label = "cloudArrow",
                        )
                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.backup_cloud_title)) },
                            supportingContent = {
                                Text(
                                    text = CloudSummary.of(
                                        url = state.cloudUrl,
                                        configured = state.cloudConfigured,
                                        unset = stringResource(R.string.backup_cloud_summary_unset),
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                Icon(
                                    imageVector = Icons.Filled.ExpandMore,
                                    contentDescription = stringResource(R.string.expand),
                                    modifier = Modifier.graphicsLayer { rotationZ = rotation },
                                )
                            },
                            onClick = actions.onToggleCloud,
                        )
                    }
                    item(visible = state.cloudExpanded) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(
                                stringResource(R.string.backup_cloud_intro),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                WebDavPresets.ALL.forEach { preset ->
                                    AssistChip(
                                        onClick = { actions.onSelectPreset(preset) },
                                        label = { Text(preset.label) },
                                    )
                                }
                            }
                            // 点预设只是把地址填好，"去哪生成应用密码/要先开什么"才是真正会卡住人的
                            // 那一步，所以那句话必须跟着显示出来（内容来自 WebDavPresets 里的 hintRes）。
                            state.cloudPresetHintRes?.let { hintRes ->
                                Text(
                                    stringResource(hintRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            OutlinedTextField(
                                value = state.cloudUrl,
                                onValueChange = actions.onUrlChange,
                                label = { Text(stringResource(R.string.backup_cloud_url)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                            OutlinedTextField(
                                value = state.cloudUser,
                                onValueChange = actions.onUserChange,
                                label = { Text(stringResource(R.string.backup_cloud_user)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                            OutlinedTextField(
                                value = state.cloudPass,
                                onValueChange = actions.onPassChange,
                                label = { Text(stringResource(R.string.backup_cloud_password)) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                            Row(
                                Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(onClick = actions.onTestCloud, enabled = !state.loading) {
                                    Text(stringResource(R.string.backup_cloud_test))
                                }
                                OutlinedButton(onClick = actions.onSaveCloud, enabled = !state.loading) {
                                    Text(stringResource(R.string.backup_cloud_save))
                                }
                            }
                            Text(
                                stringResource(R.string.backup_cloud_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            item {
                SegmentedColumn {
                    item {
                        SegmentedRadioItem(
                            title = stringResource(R.string.backup_origin_local),
                            selected = state.origin == BackupOrigin.LOCAL,
                            onClick = { actions.onSelectOrigin(BackupOrigin.LOCAL) },
                        )
                    }
                    item {
                        // 灰掉的那一项不解释为什么点不动，等于让人对着一个死按钮猜；把原因
                        // 挂在它自己的副标题上，就不用再单独占一行小字。
                        SegmentedRadioItem(
                            title = stringResource(R.string.backup_origin_cloud),
                            summary = if (state.cloudConfigured) {
                                null
                            } else {
                                stringResource(R.string.backup_cloud_chip_locked)
                            },
                            selected = state.origin == BackupOrigin.CLOUD,
                            enabled = state.cloudConfigured,
                            onClick = { actions.onSelectOrigin(BackupOrigin.CLOUD) },
                        )
                    }
                }
            }
            item {
                SegmentedColumn {
                    item {
                        SegmentedRadioItem(
                            title = stringResource(R.string.backup_kind_module),
                            selected = state.kind == BackupKind.MODULE,
                            onClick = { actions.onSelectKind(BackupKind.MODULE) },
                        )
                    }
                    item {
                        // boot 那一栏备份的始终是原厂（未打补丁）镜像，恢复它等于回到未 root
                        // 状态——这句话只在选中它时出现，平时不占版面。
                        SegmentedRadioItem(
                            title = stringResource(R.string.backup_kind_boot),
                            summary = if (state.kind == BackupKind.BOOT) {
                                stringResource(R.string.backup_boot_explain)
                            } else {
                                null
                            },
                            selected = state.kind == BackupKind.BOOT,
                            onClick = { actions.onSelectKind(BackupKind.BOOT) },
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = actions.onBackup,
                        enabled = !state.loading,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.backup_now))
                    }
                    OutlinedButton(
                        onClick = actions.onImport,
                        enabled = !state.loading,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.backup_import))
                    }
                }
            }
            if (state.loading) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            // 空列表必须给一句话：什么都不显示的话，用户分不清"没有备份"和"这一页坏了"。
            state.emptyText?.let { empty ->
                item {
                    Text(
                        empty,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.rows, key = { it.id }) { row ->
                SegmentedListItem(
                    onClick = { actions.onRestore(row.fileName) },
                    enabled = !state.loading,
                    headlineContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(row.title)
                            if (row.isRollback) {
                                Text(
                                    stringResource(R.string.backup_rollback_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    supportingContent = { Text(row.subtitle) },
                    trailingContent = {
                        Row {
                            // 恢复/导出也要挡住并发：两次操作的结果都写同一个 message，
                            // 谁后落地谁覆盖，用户可能永远看不到先失败的那一次。
                            TextButton(onClick = { actions.onRestore(row.fileName) }, enabled = !state.loading) {
                                Text(stringResource(R.string.backup_restore))
                            }
                            TextButton(onClick = { actions.onExport(row.fileName) }, enabled = !state.loading) {
                                Text(stringResource(R.string.backup_export))
                            }
                        }
                    },
                )
            }
            // 自动备份是"设一次就不管"的开关，属于页面末尾的收尾设置；放在列表上面会把
            // 用户真正要点的备份项挤下去。
            item {
                Column {
                    SegmentedSwitchItem(
                        title = stringResource(R.string.backup_auto_title),
                        summary = stringResource(R.string.backup_auto_summary),
                        checked = state.autoBackupEnabled,
                        onCheckedChange = actions.onSetAutoBackup,
                    )
                    Text(
                        text = BackupText.autoBackupLine(context, state.autoBackupRecord, state.autoBackupEnabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.autoBackupRecord?.outcome?.let { it != AutoBackupOutcome.OK } == true) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
