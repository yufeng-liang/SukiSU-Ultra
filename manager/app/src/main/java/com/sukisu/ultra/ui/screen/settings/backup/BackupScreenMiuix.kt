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
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
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
                    // 收起时只占一行，标题右边就是当前填的地址——配置是一次性的事，
                    // 铺开那块表单（说明 + 预设 + 三个输入框 + 两个按钮）会把列表挤到屏幕外。
                    ArrowPreference(
                        title = stringResource(R.string.backup_cloud_title),
                        summary = CloudSummary.of(
                            url = state.cloudUrl,
                            configured = state.cloudConfigured,
                            unset = stringResource(R.string.backup_cloud_summary_unset),
                        ),
                        onClick = actions.onToggleCloud,
                    )
                    if (state.cloudExpanded) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                            Text(
                                text = stringResource(R.string.backup_cloud_intro),
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                            FlowRow(
                                modifier = Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                WebDavPresets.ALL.forEach { preset ->
                                    Button(onClick = { actions.onSelectPreset(preset) }) {
                                        Text(text = preset.label)
                                    }
                                }
                            }
                            // 点预设只是把地址填好，"去哪生成应用密码/要先开什么"才是真正会卡住人的
                            // 那一步，所以那句话必须跟着显示出来（内容来自 WebDavPresets 的 hintRes）。
                            state.cloudPresetHintRes?.let { hintRes ->
                                Text(
                                    text = stringResource(hintRes),
                                    fontSize = 12.sp,
                                    color = colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
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
            }
            item {
                Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    CheckboxPreference(
                        title = stringResource(R.string.backup_origin_local),
                        checked = state.origin == BackupOrigin.LOCAL,
                        onCheckedChange = { actions.onSelectOrigin(BackupOrigin.LOCAL) },
                    )
                    // 灰掉的那一项不解释为什么点不动，等于让人对着一个死按钮猜；把原因
                    // 挂在它自己的副标题上，就不用再单独占一行小字。
                    CheckboxPreference(
                        title = stringResource(R.string.backup_origin_cloud),
                        summary = if (state.cloudConfigured) {
                            null
                        } else {
                            stringResource(R.string.backup_cloud_chip_locked)
                        },
                        checked = state.origin == BackupOrigin.CLOUD,
                        enabled = state.cloudConfigured,
                        onCheckedChange = { actions.onSelectOrigin(BackupOrigin.CLOUD) },
                    )
                }
            }
            item {
                Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    CheckboxPreference(
                        title = stringResource(R.string.backup_kind_module),
                        checked = state.kind == BackupKind.MODULE,
                        onCheckedChange = { actions.onSelectKind(BackupKind.MODULE) },
                    )
                    // boot 那一栏备份的始终是原厂（未打补丁）镜像，恢复它等于回到未 root
                    // 状态——这句话只在选中它时出现，平时不占版面。
                    CheckboxPreference(
                        title = stringResource(R.string.backup_kind_boot),
                        summary = if (state.kind == BackupKind.BOOT) {
                            stringResource(R.string.backup_boot_explain)
                        } else {
                            null
                        },
                        checked = state.kind == BackupKind.BOOT,
                        onCheckedChange = { actions.onSelectKind(BackupKind.BOOT) },
                    )
                }
            }
            // 主操作铺满一行、用主色，和 Install 页的「下一步」一致：放在 Card 里当普通设置项
            // 时，它和下面的「从文件导入」长得一模一样，用户得读字才知道该点哪个。
            item {
                TextButton(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    text = stringResource(R.string.backup_now),
                    enabled = !state.loading,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = actions.onBackup,
                )
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(R.string.backup_import),
                    enabled = !state.loading,
                    onClick = actions.onImport,
                )
            }
            // 空列表必须给一句话：什么都不显示的话，用户分不清"没有备份"和"这一页坏了"。
            state.emptyText?.let { empty ->
                item {
                    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                        Text(
                            text = empty,
                            fontSize = 14.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
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
            // 自动备份是"设一次就不管"的开关，属于页面末尾的收尾设置；放在列表上面会把
            // 用户真正要点的备份项挤下去。
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
        }
    }
}
