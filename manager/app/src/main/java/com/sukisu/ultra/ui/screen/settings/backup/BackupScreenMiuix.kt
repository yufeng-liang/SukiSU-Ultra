package com.sukisu.ultra.ui.screen.settings.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.res.pluralStringResource
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
import top.yukonga.miuix.kmp.basic.HorizontalDivider
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
                        // 一条分隔线把"标题行"和"表单"分开：没有它，第一句说明紧贴着标题，
                        // 看着像标题的续行。
                        HorizontalDivider()
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(
                                text = stringResource(R.string.backup_cloud_intro),
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                            FlowRow(
                                modifier = Modifier.padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                // 四个预设放不下一行会折成两行，而 FlowRow 的行间距默认是 0：
                                // 折起来的第二行边框直接贴在上一行上，看着像重叠。
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                WebDavPresets.ALL.forEach { preset ->
                                    // 选中的那个用主色，其余保持灰底——不这样区分的话，点完预设
                                    // 用户只能从地址栏里的域名反推自己刚才点的是谁。
                                    Button(
                                        onClick = { actions.onSelectPreset(preset) },
                                        colors = if (preset == state.selectedPreset) {
                                            ButtonDefaults.buttonColorsPrimary()
                                        } else {
                                            ButtonDefaults.buttonColors()
                                        },
                                    ) {
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
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                            TextField(
                                value = state.cloudUrl,
                                onValueChange = actions.onUrlChange,
                                label = stringResource(R.string.backup_cloud_url),
                                useLabelAsPlaceholder = true,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            )
                            TextField(
                                value = state.cloudUser,
                                onValueChange = actions.onUserChange,
                                label = stringResource(R.string.backup_cloud_user),
                                useLabelAsPlaceholder = true,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            )
                            TextField(
                                value = state.cloudPass,
                                onValueChange = actions.onPassChange,
                                label = stringResource(R.string.backup_cloud_password),
                                useLabelAsPlaceholder = true,
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            )
                            // 这句话说的就是上面那个密码框，贴在它下面才读得通；放到表单最底下
                            // 会跟"测试连接/保存"挤在一起，看着像按钮的说明。
                            Text(
                                text = stringResource(R.string.backup_cloud_hint),
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            FlowRow(
                                modifier = Modifier.padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(onClick = actions.onTestCloud, enabled = !state.loading) {
                                    Text(text = stringResource(R.string.backup_cloud_test))
                                }
                                Button(onClick = actions.onSaveCloud, enabled = !state.loading) {
                                    Text(text = stringResource(R.string.backup_cloud_save))
                                }
                            }
                        }
                    }
                }
            }
            item {
                Column {
                    GroupTitle(stringResource(R.string.backup_origin_group))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        CheckboxPreference(
                            title = stringResource(R.string.backup_origin_local),
                            checked = BackupOrigin.LOCAL in state.origins,
                            onCheckedChange = { actions.onToggleOrigin(BackupOrigin.LOCAL) },
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
                            checked = BackupOrigin.CLOUD in state.origins,
                            enabled = state.cloudConfigured,
                            onCheckedChange = { actions.onToggleOrigin(BackupOrigin.CLOUD) },
                        )
                    }
                    // 两个都勾上才出现：一次备份会写两份，这件事不说出来就像个 bug。
                    if (state.showsOriginBadge) {
                        Text(
                            text = stringResource(R.string.backup_origin_hint),
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                        )
                    }
                }
            }
            item {
                Column {
                    GroupTitle(stringResource(R.string.backup_kind_group))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        // 模块这一行直接写数量：不写的话，"选模块"那一步看不出到底有几个模块。
                        // 读不到列表、或一个模块都没装时，这里就是唯一能说明白的地方。
                        CheckboxPreference(
                            title = stringResource(R.string.backup_kind_module),
                            summary = when {
                                state.modulesUnavailable -> stringResource(R.string.backup_module_unavailable)
                                state.modules.isEmpty() -> stringResource(R.string.backup_module_none)
                                else -> pluralStringResource(R.plurals.backup_module_count, state.modules.size, state.modules.size)
                            },
                            checked = BackupKind.MODULE in state.kinds,
                            onCheckedChange = { actions.onToggleKind(BackupKind.MODULE) },
                        )
                        // boot 那一栏备份的始终是原厂（未打补丁）镜像，恢复它等于回到未 root
                        // 状态。这句话常显而不是选中才出现：选中时冒出来会把下面的东西整体推下去。
                        CheckboxPreference(
                            title = stringResource(R.string.backup_kind_boot),
                            summary = stringResource(R.string.backup_boot_explain),
                            checked = BackupKind.BOOT in state.kinds,
                            onCheckedChange = { actions.onToggleKind(BackupKind.BOOT) },
                        )
                        // 没得挑的时候不摆一个空列表出来：为什么没得挑写在上面那一行的副标题里。
                        if (BackupKind.MODULE in state.kinds && state.modules.isNotEmpty()) {
                            ModulePickerMiuix(state = state, actions = actions)
                        }
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 导入是次要的，放左边；立即备份是这一步的落点，放右边并用主色。
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = stringResource(R.string.backup_import),
                        enabled = !state.loading,
                        onClick = actions.onImport,
                    )
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = stringResource(R.string.backup_now),
                        enabled = !state.loading && state.canBackUp,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = actions.onBackup,
                    )
                }
            }
            // 备份到底存在哪：snackbar 几秒就没了，而"文件在哪"是过几天才会想起来问的问题，
            // 所以它得一直挂在这儿。两边都勾上时两行都写。
            items(state.orderedOrigins, key = { "location-${it.name}" }) { origin ->
                LocationLineMiuix(origin = origin, cloudUrl = state.cloudSavedUrl)
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
            // 一次备份一行：11 个模块铺成 11 行会把"我最近备了什么"这个问题埋掉。点进去才看
            // 具体条目（BackupDetailMiuix），恢复也只在那里按按钮才会发生。
            items(state.groups, key = { it.id }) { group ->
                Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    ArrowPreference(
                        title = group.label,
                        summary = groupSummary(group, state.showsOriginBadge),
                        enabled = !state.loading,
                        onClick = { actions.onOpenGroup(group) },
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
            // 这一页的 contentWindowInsets 只要了水平方向（顶栏自己吃状态栏），所以底部 inset
            // 不在 innerPadding 里。不自己留出这段高度，滚到底时最后一行就贴在屏幕边缘、
            // 被手势条压掉一半。
            item {
                Spacer(
                    Modifier.height(
                        12.dp +
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                            WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
                    )
                )
            }
        }
    }
}

/**
 * 「备份存在哪」那一行。
 *
 * 完整路径/地址照原样给出来，不省略：用户是照着它去文件管理器或 NAS 上找文件的，
 * 中间截掉一段就等于没给。
 */
@Composable
private fun LocationLineMiuix(origin: BackupOrigin, cloudUrl: String) {
    val location = BackupLocation.full(origin, cloudUrl)
    if (location.isBlank()) return
    Text(
        text = stringResource(
            if (origin == BackupOrigin.CLOUD) R.string.backup_location_cloud else R.string.backup_location_local,
            location,
        ),
        fontSize = 12.sp,
        color = colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

/**
 * 一组选项上方的小标题。
 *
 * 左内边距 16dp：外层 LazyColumn 已经有 12dp，加上 Card 自己的 16dp 内边距，标题就和
 * 卡片里的文字对齐了。
 */
@Composable
private fun GroupTitle(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        color = colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

/**
 * 「模块」那一行下面的勾选列表。
 *
 * 默认全选、整块收起：多数人是要全部，把十几个模块铺出来只会把下面的按钮和列表顶走。
 * 调用方只在有模块可挑时才调用它。
 */
@Composable
private fun ModulePickerMiuix(state: BackupUiState, actions: BackupActions) {
    ArrowPreference(
        title = stringResource(R.string.backup_module_select),
        summary = stringResource(
            R.string.backup_module_selected,
            state.selectedModuleIds.size,
            state.modules.size,
        ),
        onClick = actions.onToggleModules,
    )
    if (!state.modulesExpanded) return
    val disabledLabel = stringResource(R.string.backup_row_disabled)
    CheckboxPreference(
        title = stringResource(R.string.backup_module_select_all),
        checked = state.selectedModuleIds.size == state.modules.size,
        onCheckedChange = actions.onSetAllModules,
    )
    state.modules.forEach { module ->
        CheckboxPreference(
            title = ModuleOptionText.title(module),
            summary = ModuleOptionText.summary(module, disabledLabel),
            checked = module.id in state.selectedModuleIds,
            onCheckedChange = { actions.onToggleModule(module.id) },
        )
    }
}
