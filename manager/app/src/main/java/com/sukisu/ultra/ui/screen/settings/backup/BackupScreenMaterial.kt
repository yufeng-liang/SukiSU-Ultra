package com.sukisu.ultra.ui.screen.settings.backup

import android.content.Context
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.data.backup.AutoBackupOutcome
import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin
import com.sukisu.ultra.data.backup.WebDavPresets
import com.sukisu.ultra.ui.component.material.ExpressiveTabRow
import com.sukisu.ultra.ui.component.material.SegmentedCheckboxItem
import com.sukisu.ultra.ui.component.material.SegmentedColumn
import com.sukisu.ultra.ui.component.material.SegmentedListItem
import com.sukisu.ultra.ui.component.material.SegmentedSwitchItem
import com.sukisu.ultra.ui.util.BackupText

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BackupMaterial(
    state: BackupUiState,
    actions: BackupActions,
    snackbarHostState: SnackbarHostState,
    backupListState: LazyListState,
    restoreListState: LazyListState,
) {
    val context = LocalContext.current
    // 两个分页各有一份滚动位置：表单和清单的长度差得远，共用一份的话换页会停在
    // 对方那页的偏移上。
    val listState = if (state.tab == BackupTab.BACKUP) backupListState else restoreListState

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.backup_title)) },
                    navigationIcon = {
                        IconButton(onClick = actions.onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                )
                // 分页紧贴顶栏，跟着内容一起滚的话翻到下面想换页还得先滑回顶上。
                ExpressiveTabRow(
                    selectedTabIndex = state.tab.ordinal,
                    tabs = BackupTab.entries.map { tabLabel(it, state.groups.size) },
                    onTabClick = { actions.onSelectTab(BackupTab.entries[it]) },
                )
            }
        },
    ) { innerPadding ->
        // 整页只有一个滚动容器。以前这里是不滚动的 Column 套 LazyColumn，云端表单展开后
        // 下面那截会被顶出屏幕而且拉不回来——列表和表单抢同一块高度，谁在后面谁消失。
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.tab) {
                BackupTab.BACKUP -> backupTabItems(state, actions, context)
                BackupTab.RESTORE -> restoreTabItems(state, actions)
            }
        }
    }

    BackupProgressDialogMaterial(state = state, onDismiss = actions.onDismissBackupRun)
}

/**
 * 云端（WebDAV）那一块：收起时只占一行，展开是配置表单。
 *
 * 两个分页都挂。备份的人要在这儿填地址；**从云端恢复的人也是先想到恢复页**——填完保存就会自动
 * 去列一次云端并把云端勾上（见 BackupViewModel.saveCloudFromState），不用再切回备份页。
 */
@Composable
private fun CloudCardMaterial(state: BackupUiState, actions: BackupActions) {
    SegmentedColumn {
        item {
            val rotation by animateFloatAsState(
                targetValue = if (state.cloudExpanded) 180f else 0f,
                label = "cloudArrow",
            )
            SegmentedListItem(
                headlineContent = { Text(stringResource(R.string.backup_cloud_title)) },
                // 地址整条给出来，最多两行：用户是照着它核对服务器的，截成域名就核对不了。
                supportingContent = {
                    Text(
                        text = state.cloudUrl.ifBlank {
                            stringResource(R.string.backup_cloud_summary_unset)
                        },
                        maxLines = 2,
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
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    // 四个预设放不下一行会折成两行，而 FlowRow 的行间距默认是 0：
                    // 折起来的第二行边框直接贴在上一行上，看着像重叠。
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WebDavPresets.ALL.forEach { preset ->
                        val selected = preset == state.selectedPreset
                        // 选中的那个带勾并换成主色底——不这样区分的话，点完预设
                        // 用户只能从地址栏里的域名反推自己刚才点的是谁。
                        FilterChip(
                            selected = selected,
                            onClick = { actions.onSelectPreset(preset) },
                            label = { Text(preset.label) },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Filled.Check, contentDescription = null) }
                            } else {
                                null
                            },
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
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = state.cloudUrl,
                    onValueChange = actions.onUrlChange,
                    label = { Text(stringResource(R.string.backup_cloud_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = state.cloudUser,
                    onValueChange = actions.onUserChange,
                    label = { Text(stringResource(R.string.backup_cloud_user)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                OutlinedTextField(
                    value = state.cloudPass,
                    onValueChange = actions.onPassChange,
                    label = { Text(stringResource(R.string.backup_cloud_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                // 这句话说的就是上面那个密码框，贴在它下面才读得通；放到表单最底下
                // 会跟"测试连接/保存"挤在一起，看着像按钮的说明。
                Text(
                    stringResource(R.string.backup_cloud_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(
                    Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 保存是这一步的落点，用实心按钮；测试连接是试一下，描边就够。
                    OutlinedButton(onClick = actions.onTestCloud, enabled = !state.loading) {
                        Text(stringResource(R.string.backup_cloud_test))
                    }
                    Button(onClick = actions.onSaveCloud, enabled = !state.loading) {
                        Text(stringResource(R.string.backup_cloud_save))
                    }
                }
            }
        }
    }
}

/**
 * 「备份存在哪」那一行。
 *
 * snackbar 几秒就没了，而"文件在哪"是过几天才会想起来问的问题，所以它常驻在按钮下面。
 * 地址还没填的云端不显示——写一行空的地址等于什么都没说。
 */
@Composable
private fun LocationLineMaterial(origin: BackupOrigin, cloudUrl: String) {
    val location = BackupLocation.full(origin, cloudUrl)
    if (location.isBlank()) return
    Text(
        text = stringResource(
            if (origin == BackupOrigin.CLOUD) R.string.backup_location_cloud else R.string.backup_location_local,
            location,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
    )
}

/** 一组选项上方的小标题，样式与 [SegmentedColumn] 自带的分组标题一致。 */
@Composable
private fun GroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
    )
}

/**
 * 「模块」那一行下面的勾选入口。
 *
 * 默认全选、整块收起：多数人是要全部，把十几个模块铺出来只会把下面的按钮和列表顶走。
 */
@Composable
private fun ModulePickerHeaderMaterial(state: BackupUiState, actions: BackupActions) {
    val rotation by animateFloatAsState(
        targetValue = if (state.modulesExpanded) 180f else 0f,
        label = "modulesArrow",
    )
    SegmentedListItem(
        headlineContent = { Text(stringResource(R.string.backup_module_select)) },
        supportingContent = {
            Text(
                stringResource(
                    R.string.backup_module_selected,
                    state.selectedModuleIds.size,
                    state.modules.size,
                ),
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = stringResource(R.string.expand),
                modifier = Modifier.graphicsLayer { rotationZ = rotation },
            )
        },
        onClick = actions.onToggleModules,
    )
}

/** 调用方只在有模块可挑时才调用它。 */
@Composable
private fun ModulePickerListMaterial(state: BackupUiState, actions: BackupActions) {
    val disabledLabel = stringResource(R.string.backup_row_disabled)
    SegmentedCheckboxItem(
        title = stringResource(R.string.backup_module_select_all),
        checked = state.selectedModuleIds.size == state.modules.size,
        onCheckedChange = actions.onSetAllModules,
    )
    state.modules.forEach { module ->
        SegmentedCheckboxItem(
            title = ModuleOptionText.title(module),
            summary = ModuleOptionText.summary(module, disabledLabel),
            checked = module.id in state.selectedModuleIds,
            onCheckedChange = { actions.onToggleModule(module.id) },
        )
    }
}

/**
 * 「备份」分页：把选项配好，再按「立即备份」。
 *
 * 列表搬到「恢复」分页之后，这一页就只剩一张表单——它需要的只是一个能滚的容器，
 * 所以云端表单、位置、内容、按钮仍然是同一串 lazy item。
 */
private fun LazyListScope.backupTabItems(
    state: BackupUiState,
    actions: BackupActions,
    context: Context,
) {
    item { CloudCardMaterial(state, actions) }
    item {
        Column {
            GroupTitle(stringResource(R.string.backup_origin_group))
            SegmentedColumn {
                item {
                    SegmentedCheckboxItem(
                        title = stringResource(R.string.backup_origin_local),
                        checked = BackupOrigin.LOCAL in state.origins,
                        onCheckedChange = { actions.onToggleOrigin(BackupOrigin.LOCAL) },
                    )
                }
                item {
                    // 灰掉的那一项不解释为什么点不动，等于让人对着一个死按钮猜；把原因
                    // 挂在它自己的副标题上，就不用再单独占一行小字。
                    SegmentedCheckboxItem(
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
            }
            // 两个都勾上才出现：一次备份会写两份，这件事不说出来就像个 bug。
            if (state.showsOriginBadge) {
                Text(
                    text = stringResource(R.string.backup_origin_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
            }
        }
    }
    item {
        Column {
            GroupTitle(stringResource(R.string.backup_kind_group))
            SegmentedColumn {
                item {
                    // 模块这一行直接写数量：不写的话，"选模块"那一步看不出到底有几个模块。
                    // 读不到列表、或一个模块都没装时，这里就是唯一能说明白的地方。
                    SegmentedCheckboxItem(
                        title = stringResource(R.string.backup_kind_module),
                        summary = when {
                            state.modulesUnavailable -> stringResource(R.string.backup_module_unavailable)
                            state.modules.isEmpty() -> stringResource(R.string.backup_module_none)
                            else -> pluralStringResource(R.plurals.backup_module_count, state.modules.size, state.modules.size)
                        },
                        checked = BackupKind.MODULE in state.kinds,
                        onCheckedChange = { actions.onToggleKind(BackupKind.MODULE) },
                    )
                }
                item {
                    // boot 那一栏备份的始终是原厂（未打补丁）镜像，恢复它等于回到未 root
                    // 状态。这句话常显而不是选中才出现：选中时冒出来会把下面的东西整体推下去。
                    SegmentedCheckboxItem(
                        title = stringResource(R.string.backup_kind_boot),
                        summary = stringResource(R.string.backup_boot_explain),
                        checked = BackupKind.BOOT in state.kinds,
                        onCheckedChange = { actions.onToggleKind(BackupKind.BOOT) },
                    )
                }
                // 没得挑的时候不摆一个空列表出来：为什么没得挑写在上面那一行的副标题里。
                if (BackupKind.MODULE in state.kinds && state.modules.isNotEmpty()) {
                    item { ModulePickerHeaderMaterial(state = state, actions = actions) }
                    item(visible = state.modulesExpanded) {
                        ModulePickerListMaterial(state = state, actions = actions)
                    }
                }
            }
        }
    }
    item {
        // 这一页只剩一个动作了（导入搬去了恢复页，它不产生新备份，只是把别处的一份收进来），
        // 那就铺满整行——半个宽度的主按钮看着像还有别的选项没显示出来。
        Button(
            onClick = actions.onBackup,
            enabled = !state.loading && state.canBackUp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.backup_now))
        }
    }
    locationItems(state)
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
            // 目的地是自动备份的子选项：总开关关着时它们没有意义，灰掉而不是藏起来——
            // 藏起来用户就不知道"自动备份还能挑地方"，灰着至少看得见，也知道要先开总开关。
            SegmentedColumn {
                item {
                    SegmentedCheckboxItem(
                        title = stringResource(R.string.backup_origin_local),
                        checked = state.autoBackupLocal,
                        enabled = state.autoBackupEnabled,
                        onCheckedChange = actions.onSetAutoBackupLocal,
                    )
                }
                item {
                    // 这里**不**像「备份位置」那样锁死：勾的是"以后配好了就传"，与是否配置做与
                    // 运算，勾了也不会写出去。先备好设置、以后再填地址是很正常的顺序。
                    SegmentedCheckboxItem(
                        title = stringResource(R.string.backup_origin_cloud),
                        summary = if (state.cloudConfigured) null else stringResource(R.string.backup_auto_cloud_unset),
                        checked = state.autoBackupCloud,
                        enabled = state.autoBackupEnabled,
                        onCheckedChange = actions.onSetAutoBackupCloud,
                    )
                }
            }
            if (state.autoBackupEnabled && !state.autoBackupLocal && !state.autoBackupCloud) {
                Text(
                    text = stringResource(R.string.backup_auto_dest_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
            }
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

/** 「恢复」分页：已备份的清单。点一行进详情，恢复/导出/删除都只在那里发生。 */
private fun LazyListScope.restoreTabItems(state: BackupUiState, actions: BackupActions) {
    item { CloudCardMaterial(state, actions) }
    locationItems(state)
    if (state.loading) {
        item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    }
    state.emptyText?.let { empty ->
        item {
            Text(
                empty,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    // 一次备份一行：11 个模块铺成 11 行会把"我最近备了什么"这个问题埋掉。点进去才看
    // 具体条目（BackupDetailMaterial），恢复也只在那里按按钮才会发生。
    itemsIndexed(state.groups, key = { _, group -> group.id }) { index, group ->
        // 两侧都勾上时按来源分段：同一次备份在本地和云端各有一条，混着排会让人以为
        // 那是同一条被列了两遍。只勾一侧时不加标题——每段前面挂一个"本机"没有信息量。
        val startsSection = state.showsOriginBadge &&
            (index == 0 || state.groups[index - 1].origin != group.origin)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (startsSection) {
                Text(
                    text = stringResource(BackupLabels.origin(group.origin)),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            SegmentedColumn {
                item {
                    SegmentedListItem(
                        onClick = { actions.onOpenGroup(group) },
                        enabled = !state.loading,
                        headlineContent = { Text(group.label) },
                        supportingContent = { Text(groupSummary(group, showsOrigin = false)) },
                        trailingContent = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                            )
                        },
                    )
                }
            }
        }
    }
    // 导入放在清单末尾：它不产生新备份，只是把别处的一份收进来、让它出现在上面这个列表里，
    // 所以属于恢复这一侧。列表为空时它紧跟在空状态下面，一眼就能看到。
    item {
        OutlinedButton(
            onClick = actions.onImport,
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.backup_import))
        }
    }
}

/**
 * 「备份存在哪」那几行。
 *
 * 两个分页都挂：「备份」页说的是"这次会写到哪"，「恢复」页说的是"列出来的这些在哪"。
 * 地址还没填的云端不显示——写一行空的地址等于什么都没说（见 [LocationLineMaterial]）。
 */
private fun LazyListScope.locationItems(state: BackupUiState) {
    items(state.orderedOrigins, key = { "location-${it.name}" }) { origin ->
        LocationLineMaterial(origin = origin, cloudUrl = state.cloudSavedUrl)
    }
}
