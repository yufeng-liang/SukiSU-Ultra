package com.sukisu.ultra.ui.screen.settings.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.data.backup.AutoBackupOutcome
import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin
import com.sukisu.ultra.data.backup.WebDavPresets
import com.sukisu.ultra.ui.component.material.SegmentedListItem
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
        Column(Modifier.padding(innerPadding).padding(16.dp)) {
            Text(stringResource(R.string.backup_cloud_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.backup_cloud_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WebDavPresets.ALL.forEach { preset ->
                    AssistChip(
                        onClick = { actions.onSelectPreset(preset) },
                        label = { Text(preset.label) },
                    )
                }
            }
            // 点预设只是把地址填好，"去哪生成应用密码/要先开什么"才是真正会卡住人的那一步，
            // 所以那句话必须跟着显示出来（内容来自 WebDavPresets 里的 hintRes）。
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
                Button(onClick = actions.onTestCloud, enabled = !state.loading) { Text(stringResource(R.string.backup_cloud_test)) }
                OutlinedButton(onClick = actions.onSaveCloud, enabled = !state.loading) { Text(stringResource(R.string.backup_cloud_save)) }
            }
            Text(
                stringResource(R.string.backup_cloud_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.origin == BackupOrigin.LOCAL,
                    onClick = { actions.onSelectOrigin(BackupOrigin.LOCAL) },
                    label = { Text(stringResource(R.string.backup_origin_local)) },
                )
                FilterChip(
                    selected = state.origin == BackupOrigin.CLOUD,
                    onClick = { actions.onSelectOrigin(BackupOrigin.CLOUD) },
                    enabled = state.cloudConfigured,
                    label = { Text(stringResource(R.string.backup_origin_cloud)) },
                )
            }
            // 禁用的 chip 不解释为什么点不动，等于让人对着一个死按钮猜。
            if (!state.cloudConfigured) {
                Text(
                    stringResource(R.string.backup_cloud_chip_locked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.kind == BackupKind.MODULE,
                    onClick = { actions.onSelectKind(BackupKind.MODULE) },
                    label = { Text(stringResource(R.string.backup_kind_module)) },
                )
                FilterChip(
                    selected = state.kind == BackupKind.BOOT,
                    onClick = { actions.onSelectKind(BackupKind.BOOT) },
                    label = { Text(stringResource(R.string.backup_kind_boot)) },
                )
            }
            // boot 那一栏备份的始终是原厂（未打补丁）镜像，恢复它等于回到未 root 状态——
            // 这两件事不说清，用户会以为自己在备份"当前系统"。
            if (state.kind == BackupKind.BOOT) {
                Text(
                    stringResource(R.string.backup_boot_explain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.onBackup, enabled = !state.loading) { Text(stringResource(R.string.backup_now)) }
                OutlinedButton(onClick = actions.onImport, enabled = !state.loading) { Text(stringResource(R.string.backup_import)) }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
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
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
            // 空列表必须给一句话：什么都不显示的话，用户分不清"没有备份"和"这一页坏了"。
            state.emptyText?.let { empty ->
                Text(
                    empty,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            LazyColumn {
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
            }
        }
    }
}
