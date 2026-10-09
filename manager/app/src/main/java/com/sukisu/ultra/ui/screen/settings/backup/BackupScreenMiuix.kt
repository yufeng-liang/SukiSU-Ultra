package com.sukisu.ultra.ui.screen.settings.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sukisu.ultra.R
import com.sukisu.ultra.data.backup.AutoBackupOutcome
import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin
import com.sukisu.ultra.data.backup.WebDavPresets
import com.sukisu.ultra.ui.theme.LocalEnableBlur
import com.sukisu.ultra.ui.util.BackupText
import com.sukisu.ultra.ui.util.BlurredBar
import com.sukisu.ultra.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupMiuix(
    state: BackupUiState,
    actions: BackupActions,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.backup_title),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = actions.onBack) {
                            Icon(imageVector = MiuixIcons.Back, contentDescription = null)
                        }
                    }
                )
            }
        },
        popupHost = { },
        snackbarHost = {
            SnackbarHost(
                state = snackbarHostState,
                modifier = Modifier.padding(bottom = 20.dp),
            )
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
            overscrollEffect = null,
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(text = stringResource(R.string.backup_cloud_title))
                        FlowRow(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            WebDavPresets.ALL.forEach { preset ->
                                Button(onClick = { actions.onUrlChange(preset.urlTemplate) }) {
                                    Text(text = preset.label)
                                }
                            }
                        }
                        TextField(
                            value = state.cloudUrl,
                            onValueChange = actions.onUrlChange,
                            label = stringResource(R.string.backup_cloud_url),
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        TextField(
                            value = state.cloudUser,
                            onValueChange = actions.onUserChange,
                            label = stringResource(R.string.backup_cloud_user),
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        TextField(
                            value = state.cloudPass,
                            onValueChange = actions.onPassChange,
                            label = stringResource(R.string.backup_cloud_password),
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        FlowRow(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = actions.onTestCloud, enabled = !state.loading) {
                                Text(text = stringResource(R.string.backup_cloud_test))
                            }
                            Button(onClick = actions.onSaveCloud, enabled = !state.loading) {
                                Text(text = stringResource(R.string.backup_cloud_save))
                            }
                        }
                        Text(
                            text = stringResource(R.string.backup_cloud_hint),
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            item {
                Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    ArrowPreference(
                        title = stringResource(R.string.backup_origin_local),
                        summary = if (state.origin == BackupOrigin.LOCAL) "✓" else "",
                        onClick = { actions.onSelectOrigin(BackupOrigin.LOCAL) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.backup_origin_cloud),
                        summary = if (state.origin == BackupOrigin.CLOUD) "✓" else "",
                        enabled = state.cloudConfigured,
                        onClick = { actions.onSelectOrigin(BackupOrigin.CLOUD) },
                    )
                }
            }
            item {
                Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    ArrowPreference(
                        title = stringResource(R.string.backup_kind_module),
                        summary = if (state.kind == BackupKind.MODULE) "✓" else "",
                        onClick = { actions.onSelectKind(BackupKind.MODULE) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.backup_kind_boot),
                        summary = if (state.kind == BackupKind.BOOT) "✓" else "",
                        onClick = { actions.onSelectKind(BackupKind.BOOT) },
                    )
                }
            }
            item {
                Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    ArrowPreference(
                        title = stringResource(R.string.backup_now),
                        enabled = !state.loading,
                        onClick = actions.onBackup,
                    )
                    ArrowPreference(
                        title = stringResource(R.string.backup_import),
                        enabled = !state.loading,
                        onClick = actions.onImport,
                    )
                }
            }
            item {
                Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    SwitchPreference(
                        title = stringResource(R.string.backup_auto_title),
                        summary = stringResource(R.string.backup_auto_summary),
                        checked = state.autoBackupEnabled,
                        onCheckedChange = actions.onSetAutoBackup,
                    )
                    Text(
                        text = BackupText.autoBackupLine(context, state.autoBackupRecord, state.autoBackupEnabled),
                        fontSize = 12.sp,
                        color = if (state.autoBackupRecord?.outcome?.let { it != AutoBackupOutcome.OK } == true) {
                            colorScheme.error
                        } else {
                            colorScheme.onSurfaceVariantSummary
                        },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }
            items(state.rows, key = { it.id }) { row ->
                val title = if (row.isRollback) {
                    row.title + BackupListFormatter.SEPARATOR + stringResource(R.string.backup_rollback_badge)
                } else {
                    row.title
                }
                Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    ArrowPreference(
                        title = title,
                        summary = row.subtitle,
                        enabled = !state.loading,
                        onClick = { actions.onRestore(row.fileName) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.backup_export),
                        summary = title,
                        enabled = !state.loading,
                        onClick = { actions.onExport(row.fileName) },
                    )
                }
            }
        }
    }
}
