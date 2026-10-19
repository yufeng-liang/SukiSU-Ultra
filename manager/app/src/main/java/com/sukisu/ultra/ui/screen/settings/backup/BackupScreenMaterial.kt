package com.sukisu.ultra.ui.screen.settings.backup

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberSliderState
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.data.backup.AutoBackupOutcome
import com.sukisu.ultra.data.backup.BackupDefaults
import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin
import com.sukisu.ultra.data.backup.RetentionLimit
import com.sukisu.ultra.data.backup.WebDavPresets
import com.sukisu.ultra.ui.component.material.ExpressiveScaffold
import com.sukisu.ultra.ui.component.material.ExpressiveTabRow
import com.sukisu.ultra.ui.component.material.SegmentedCheckboxItem
import com.sukisu.ultra.ui.component.material.SegmentedColumn
import com.sukisu.ultra.ui.component.material.SegmentedColumnScope
import com.sukisu.ultra.ui.component.material.SegmentedDropdownItem
import com.sukisu.ultra.ui.component.material.SegmentedItemContainer
import com.sukisu.ultra.ui.component.material.SegmentedListItem
import com.sukisu.ultra.ui.component.material.SegmentedSwitchItem
import com.sukisu.ultra.ui.component.material.expressiveTopAppBarColors
import com.sukisu.ultra.ui.util.BackupText
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

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

    // 底色交给 ExpressiveScaffold（surfaceContainer），和设置页同一套：以前这里用默认的
    // Scaffold，页面底色是 colorScheme.background（纯白），而列表行是 surfaceBright（近白），
    // 白压白就看不出一行从哪儿开始；顶栏自己又刷一层白，跟下面 surfaceContainer 的分页栏正面
    // 撞色。底色统一之后，行自己"浮"了出来，顶栏和分页栏也连成了同一块。
    ExpressiveScaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                // 多选时顶栏换成"正在多选"的操作栏：标题说勾了几份，左边是取消、右边是删除。
                // 列表行上的勾选框已经在说"选了什么"，这里补上"选了多少、能干什么"。
                TopAppBar(
                    colors = expressiveTopAppBarColors(),
                    title = {
                        Text(
                            if (state.selecting) {
                                stringResource(R.string.backup_selection_title, state.selectedGroups.size)
                            } else {
                                stringResource(R.string.backup_title)
                            },
                        )
                    },
                    navigationIcon = {
                        if (state.selecting) {
                            IconButton(onClick = actions.onClearSelection) {
                                Icon(Icons.Filled.Close, contentDescription = null)
                            }
                        } else {
                            IconButton(onClick = actions.onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            }
                        }
                    },
                    actions = {
                        if (state.selecting) {
                            // 红色：删除是这一栏唯一的操作、也是唯一不可逆的，该在做之前就看得
                            // 出来，而不是等确认弹窗弹出来才说。
                            IconButton(
                                onClick = actions.onDeleteSelected,
                                enabled = state.selectedGroups.isNotEmpty() && !state.loading,
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.backup_selection_delete),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                )
                // 分页紧贴顶栏，跟着内容一起滚的话翻到下面想换页还得先滑回顶上。
                // 多选时收起：勾的是恢复页那份清单，把"备份/恢复"留在上面等于给了一个会把
                // 勾选清掉的按钮。
                if (!state.selecting) {
                    ExpressiveTabRow(
                        selectedTabIndex = state.tab.ordinal,
                        tabs = BackupTab.entries.map { tabLabel(it, state.groups.size) },
                        onTabClick = { actions.onSelectTab(BackupTab.entries[it]) },
                    )
                }
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

    BackupProgressDialogMaterial(
        state = state,
        onDismiss = actions.onDismissBackupRun,
        onCancel = actions.onCancelBackupRun,
    )
    RestoreProgressDialogMaterial(
        run = state.restoreRun,
        onDismiss = actions.onDismissRestoreRun,
        onCancel = actions.onCancelRestore,
    )
}

/**
 * 「这家填过」的小圆点。
 *
 * 挂在芯片的图标位上。切到别家再切回来时，用户得能看出哪几家是自己配过的——否则只能挨个点开
 * 看地址栏对不对，而"我到底配没配过这家"正是这一步最容易犯嘀咕的地方。用点不用字：芯片这一行
 * 本来就要折成两行，再给每家加两个字会把它挤成三行。
 *
 * 颜色跟主色走、和选中的勾同一个色系，但它只在**没选中**的芯片上出现（选中的位子留给勾）。
 */
@Composable
private fun RememberedDot() {
    val description = stringResource(R.string.backup_cloud_remembered)
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .semantics { contentDescription = description },
    )
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
                // 这一栏是整页最上面的一块，但字号要跟别的列表行一致：自己放大两号之后，
                // 它跟下面每一行的标题都不在一个尺度上，看着像另一个控件（用户一眼就看出来了）。
                // 层次靠位置和下面那行地址来立，不靠把标题撑大。
                headlineContent = {
                    Text(
                        stringResource(R.string.backup_cloud_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                // 地址整条给出来，最多两行：用户是照着它核对服务器的，截成域名就核对不了。
                // 这里说的是**已经生效**的那份（不是表单里正在编辑的草稿）：用户切到别家填了
                // 一半就收起这块时，这一行要是跟着草稿走，就成了"备份会写到那儿"的一句谎话。
                supportingContent = {
                    Text(
                        text = state.cloudSavedUrl.ifBlank {
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
            // 面板自己不带底色，得接着标题行的卡面往下画：不接的话标题行收在 4dp 的内圆角上、
            // 下面一截直接落在页面底色上，看着像一张被切掉下半截的卡片。
            SegmentedItemContainer { CloudPanelMaterial(state, actions) }
        }
    }
}

/** 云端那一块展开后的表单，装在 [SegmentedItemContainer] 里（见 [CloudCardMaterial]）。 */
@Composable
private fun CloudPanelMaterial(state: BackupUiState, actions: BackupActions) {
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
                val selected = preset.id == state.cloudSlot
                val remembered = preset.id in state.cloudRememberedSlots
                // 选中的那个带勾并换成主色底——不这样区分的话，点完预设
                // 用户只能从地址栏里的域名反推自己刚才点的是谁。
                // 预设是"填个模板"的快捷方式，字比正文小一档：它跟下面三个输入框
                // 是一组，和页面底部那个「立即备份」同样体量会把表单的层级顶乱。
                //
                // 没选中的那几个里，记着可用配置的加一个小圆点：切到别家再切回来时，
                // 用户得能看出哪几家是自己填过的，否则只能挨个点开试。
                FilterChip(
                    selected = selected,
                    onClick = { actions.onSelectPreset(preset) },
                    label = {
                        Text(preset.label, style = MaterialTheme.typography.labelMedium)
                    },
                    leadingIcon = when {
                        selected -> {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        }
                        remembered -> {
                            { RememberedDot() }
                        }
                        else -> null
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
        // 两个动作等宽铺满一行：一长一短看着像没对齐的标签，而且"保存"是这一步的
        // 落点，铺开之后它比"测试连接"更好按。层级靠描边/实心区分——两个都是实心时，
        // 用户得读一遍字才知道该按哪个。
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = actions.onTestCloud,
                enabled = !state.loading,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.backup_cloud_test))
            }
            Button(
                onClick = actions.onSaveCloud,
                enabled = !state.loading,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.backup_cloud_save))
            }
        }
    }
}

/**
 * 「本地」/「云端」的小标签。
 *
 * 恢复之前最该确认的就是"这一份在哪一侧"，所以它得比旁边那行元信息显眼一点。两侧的颜色必须
 * 不一样：同样一块蓝色小牌子写着两个不同的词，扫一眼只会看成"两个标签"，而这两侧点下去一个
 * 动本机文件、一个走网络——配色本身就是提示（本机=主题主色，云端=主题第三色，深浅主题各一套）。
 */
@Composable
private fun OriginTagMaterial(origin: BackupOrigin) {
    Text(
        text = stringResource(BackupLabels.origin(origin)),
        style = MaterialTheme.typography.labelSmall,
        color = BackupOriginColors.onContainer(origin),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(BackupOriginColors.container(origin))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * 「本机备份在 …」那一行。
 *
 * 只写本机：云端地址就写在上面那张 WebDAV 卡片的标题栏里（[CloudCardMaterial]），再写一遍是
 * 同一句话说两次。本机这个目录别处没有，所以留着——用户是照着它去文件管理器找文件的。
 *
 * 常显，不跟着「本地」的勾选走：跟着勾选走的话，取消勾选会把这一行抽掉，下面的内容跟着跳。
 *
 * 左边不留额外内边距：外层 LazyColumn 已经给了 16dp，再补一份就和上面那句「长按任意一行可多选」
 * 差出一格——两句小字同属一列，起点必须一样。
 */
@Composable
private fun LocationLineMaterial() {
    Text(
        text = stringResource(R.string.backup_location_local, BackupDefaults.LOCAL_DIR),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
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
 * 「模块」那一行：勾选框管"这次备不备模块 / 全不全选"，行本身点开是挑具体模块。
 *
 * 以前这里是两行——一行「模块」开关、一行「选择要备份的模块」入口，说的是同一件事，想看具体
 * 模块还得先知道要往下点第二行。合成一行之后：勾选框默认全选，再点一下就是全不选（顺带把
 * "模块"这一栏也关掉，见 `BackupViewModel.setAllModules`），点行展开子项。
 *
 * 展开挑过之后勾选框会变成半选：它此刻回答的不是"备不备模块"，而是"挑齐了没有"。
 */
@Composable
private fun ModuleRowMaterial(state: BackupUiState, actions: BackupActions) {
    val rotation by animateFloatAsState(
        targetValue = if (state.modulesExpanded) 180f else 0f,
        label = "modulesArrow",
    )
    // 列表读不到、或一个模块都没装时没有可全选的对象，这一格就只剩"要不要备模块"本身的意思；
    // 有列表时它表示的就是"是不是一个不落"。
    val selectedCount = state.modules.count { it.id in state.selectedModuleIds }
    val allSelected = state.modules.isEmpty() || selectedCount == state.modules.size
    // 只勾了一部分时画成半选。画成空的话，展开挑完再收起来，这一行看起来和"一个都没勾"完全
    // 一样——刚挑的那几个就像没被记住。
    val partial = selectedCount in 1 until state.modules.size
    // 没得挑的时候不给一个点了没反应的行：箭头和点击一起去掉，只留那一格开关。
    val expandable = state.modules.isNotEmpty()
    SegmentedListItem(
        onClick = if (expandable) actions.onToggleModules else null,
        leadingContent = {
            if (partial) {
                // 半选时点一下就是全选：从"挑了几个"再往前走一步，只剩"全都要"。
                TriStateCheckbox(
                    state = ToggleableState.Indeterminate,
                    onClick = { actions.onSetAllModules(true) },
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Checkbox(
                    checked = allSelected,
                    onCheckedChange = actions.onSetAllModules,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        headlineContent = { Text(stringResource(R.string.backup_kind_module)) },
        supportingContent = { Text(moduleSummary(state)) },
        trailingContent = {
            if (expandable) {
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = stringResource(R.string.expand),
                    modifier = Modifier.graphicsLayer { rotationZ = rotation },
                )
            }
        },
    )
}

/**
 * 「模块」那一行的副标题：已选几个 / 一共几个。
 *
 * 收起时这一行是唯一能看出"是不是全选"的地方，展开时它跟着每一行的勾实时变。
 */
@Composable
private fun moduleSummary(state: BackupUiState): String = when {
    state.modulesUnavailable -> stringResource(R.string.backup_module_unavailable)
    state.modules.isEmpty() -> stringResource(R.string.backup_module_none)
    else -> stringResource(
        R.string.backup_module_selected,
        state.selectedModuleIds.size,
        state.modules.size,
    )
}

/**
 * 模块清单 / 备份详情页里的一行细目（模块名 + 版本号，或备份条目名 + 大小）。
 *
 * 比这一页的主行小一号、矮一档：这是"挂在主行下面"的一层。以前用 [SegmentedCheckboxItem]
 * 铺，M3 的 ListItem 给两行内容留了 72dp 的最小高度（一行 200px），一屏只放得下五个模块，
 * 而且每个模块名和"模块"这一栏本身一样重——主次分不出来。这里自己搭一行：标题降一档到
 * bodyMedium，上下各留 6dp，行高落到 140px 上下，字号和间距一起收。
 *
 * 行得自己铺卡面：`SegmentedColumn` 只算圆角和行距，底色是行自己画的（见 [SegmentedItemContainer]）。
 * 勾选框只报状态、点击整行都算，所以 `Checkbox` 的 `onCheckedChange` 传 null。
 */
@Composable
internal fun SubEntryRowMaterial(
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SegmentedItemContainer {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.size(24.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    // 12dp：勾选框 24dp + 这一档，标题的左边缘才和上面主行（M3 ListItem 的
                    // 16dp 内边距 + 24dp 勾选框 + 16dp 间距）落在同一条竖线上。
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                summary?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * 「模块」那一行下面的勾选列表：每张勾选卡各占这一组的一行。
 *
 * 每行都得是这一组自己的一行，而不是塞进同一个条目里的一摞行——条目的四角是这一组按位置算给
 * 它的，一摞行共用同一副四角、行与行之间也没有那 2dp 的缝，铺出来是一摞边缘带缺口的圆角条。
 *
 * 「全选」不在这里：它已经搬到上面那一行的勾选框上（[ModuleRowMaterial]），再摆一行就是
 * 同一件事的第二遍。
 *
 * 调用方只在有模块可挑时才调用它。
 */
private fun SegmentedColumnScope.modulePickerRowsMaterial(
    state: BackupUiState,
    actions: BackupActions,
    disabledLabel: String,
) {
    state.modules.forEach { module ->
        item(key = "module-row-${module.id}", visible = state.modulesExpanded) {
            SubEntryRowMaterial(
                title = ModuleOptionText.title(module),
                summary = ModuleOptionText.summary(module, disabledLabel),
                checked = module.id in state.selectedModuleIds,
                onCheckedChange = { actions.onToggleModule(module.id) },
            )
        }
    }
}

/**
 * 保留额度那一块：两个滑块（模块 / boot）。
 *
 * 默认收起，理由与 Miuix 那一版相同（见 `RetentionCardMiuix`）。两个主题共用同一份取值与
 * 提交时机——滑块只在松手时提交，中途不落盘。
 *
 * 结构和云端那一块（[CloudCardMaterial]）一样：标题行是这一组的一行，展开的面板是下一行、
 * 用 [SegmentedItemContainer] 接着卡面往下画。以前这里是"TonalCard 里再套一层横向 16dp 内边距"，
 * 于是整块比上下都窄一圈，四角也就和别的行对不齐了。
 */
@Composable
private fun RetentionCardMaterial(state: BackupUiState, actions: BackupActions) {
    SegmentedColumn {
        item {
            val rotation by animateFloatAsState(
                targetValue = if (state.retentionExpanded) 180f else 0f,
                label = "retentionArrow",
            )
            SegmentedListItem(
                headlineContent = { Text(stringResource(R.string.backup_retention_title)) },
                supportingContent = { Text(retentionSummary(state)) },
                trailingContent = {
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = stringResource(R.string.expand),
                        modifier = Modifier.graphicsLayer { rotationZ = rotation },
                    )
                },
                onClick = actions.onToggleRetention,
            )
        }
        item(visible = state.retentionExpanded) {
            SegmentedItemContainer {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.backup_retention_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RetentionSliderMaterial(
                        title = stringResource(R.string.backup_retention_module),
                        value = state.moduleRetention,
                        max = RetentionLimit.MAX,
                        onCommit = actions.onSetModuleRetention,
                    )
                    RetentionSliderMaterial(
                        title = stringResource(R.string.backup_retention_partition),
                        summary = stringResource(R.string.backup_retention_partition_summary),
                        value = state.partitionRetention,
                        max = RetentionLimit.MAX_PARTITION,
                        onCommit = actions.onSetPartitionRetention,
                    )
                }
            }
        }
    }
}

/**
 * 一个额度滑块：标题、当前值、一条 1…[max] 的滑轨。
 *
 * 值跟在标题同一行、说明另起一行铺满宽度：说明长起来会折成两行，值要是和说明挤在一行里，
 * 折行之后它正好落在句子中间，读起来像句子的一部分。
 */
@Composable
private fun RetentionSliderMaterial(
    title: String,
    value: Int,
    max: Int,
    onCommit: (Int) -> Unit,
    summary: String? = null,
) {
    val sliderState = rememberSliderState(
        value = value.toFloat(),
        steps = max - RetentionLimit.MIN - 1,
        trackRange = RetentionLimit.MIN.toFloat()..max.toFloat(),
    )
    // 滑轨上没有刻度，手指滑过去既没有声音也没有阻力：只有每跨过一档震一下，用户才知道
    // "又过了一格、现在是第几格"（Miuix 那一版用的是它滑块自带的 Step 触感，这里是同一件事）。
    // 初始值不震：进页面时不该有反馈，那是"你动了它"才有的东西。
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(sliderState) {
        snapshotFlow { sliderState.value.roundToInt() }
            .distinctUntilChanged()
            .drop(1)
            .collect { haptic.performHapticFeedback(HapticFeedbackType.VirtualKey) }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.backup_retention_count, sliderState.value.roundToInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        summary?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Slider(
            state = sliderState,
            // 松手才落盘：拖动途中每个整数都写一次偏好项，等于把一次操作变成十几次磁盘写入。
            onValueChangeFinished = { onCommit(sliderState.value.roundToInt()) },
            modifier = Modifier.fillMaxWidth(),
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
        // 「备份位置」的三档（本地 / 云端 / 本地+云端）挤进一行下拉框，和设置页的「界面风格」
        // 同一副样子：以前是两个各自独立的勾选框，"两个都要"这件事得靠用户自己把两下点凑
        // 出来，而它恰恰是最常用的那一档。原来那行小字提示搬到了这一行的副标题上——写到哪
        // 已经写在行尾，不用再单占一行。
        SegmentedColumn {
            item {
                val localLabel = stringResource(R.string.backup_origin_local)
                val cloudLabel = stringResource(R.string.backup_origin_cloud)
                SegmentedDropdownItem(
                    title = stringResource(R.string.backup_origin_group),
                    // 这一行只写"这里是干什么的"：选中的是哪一档由右边那个值说了，副标题再复述
                    // 一遍（"备份写到 本地+云端"）就是同一句话在一行里出现两次。
                    summary = if (state.cloudConfigured) {
                        stringResource(R.string.backup_origin_hint)
                    } else {
                        // 没配地址时"云端"这一档根本不存在，得说清楚为什么少了它。
                        stringResource(R.string.backup_cloud_chip_locked)
                    },
                    // 第三档写成"本地+云端"，不用列表分隔符拼成"本地、云端"：这一档是一个**组合**
                    // （两处都要），顿号读起来像是在重复列前两项。
                    items = if (state.cloudConfigured) {
                        listOf(localLabel, cloudLabel, localLabel + "+" + cloudLabel)
                    } else {
                        listOf(localLabel)
                    },
                    selectedIndex = originOptionIndex(state.origins, state.cloudConfigured),
                    onItemSelected = { index ->
                        actions.onSelectOrigins(originsForOption(state.cloudConfigured, index))
                    },
                )
            }
        }
    }
    item {
        val disabledLabel = stringResource(R.string.backup_row_disabled)
        Column {
            GroupTitle(stringResource(R.string.backup_kind_group))
            SegmentedColumn {
                // 模块这一行自己就是入口：勾选框管全选，点行展开具体模块。展开的那几行紧跟在
                // 它下面——中间隔着分区快照和仓库源的话，"点开这一行"就不成立了。
                item { ModuleRowMaterial(state = state, actions = actions) }
                // 没得挑的时候不摆一个空列表出来：为什么没得挑写在上面那一行的副标题里。
                if (state.modules.isNotEmpty()) {
                    modulePickerRowsMaterial(
                        state = state,
                        actions = actions,
                        disabledLabel = disabledLabel,
                    )
                }
                item {
                    // 分区快照抓的是当前设备上 boot + init_boot 两个分区的原始内容，恢复是往块
                    // 设备写镜像（不可撤销）。这句话常显而不是选中才出现：选中时冒出来会把它下面
                    // 的东西整体推下去。
                    SegmentedCheckboxItem(
                        title = stringResource(R.string.backup_kind_partition),
                        summary = stringResource(R.string.backup_partition_explain),
                        checked = BackupKind.PARTITION in state.kinds,
                        onCheckedChange = { actions.onToggleKind(BackupKind.PARTITION) },
                    )
                }
                item {
                    // 压缩开关只影响分区快照这一栏，所以放在它下面而不是保留额度那张卡里。
                    // 默认开：boot 96MB 压完约 20MB，云端那一步能省好几倍时间。
                    SegmentedSwitchItem(
                        title = stringResource(R.string.backup_partition_compress_title),
                        summary = stringResource(R.string.backup_partition_compress_summary),
                        checked = state.partitionCompressed,
                        onCheckedChange = actions.onSetPartitionCompressed,
                    )
                }
                item {
                    // 仓库源配置整批备份、整批恢复：卸载重装管理器最容易丢的就是它，所以
                    // 默认勾上（见 BackupUiState.kinds），summary 说明备份的是哪些东西。
                    SegmentedCheckboxItem(
                        title = stringResource(R.string.backup_kind_repo),
                        summary = stringResource(R.string.backup_repo_explain),
                        checked = BackupKind.REPO_CONFIG in state.kinds,
                        onCheckedChange = { actions.onToggleKind(BackupKind.REPO_CONFIG) },
                    )
                }
            }
        }
    }
    item {
        // 这一页只剩一个动作了（导入搬去了恢复页，它不产生新备份，只是把别处的一份收进来），
        // 那就铺满整行——半个宽度的主按钮看着像还有别的选项没显示出来。
        //
        // 按钮常亮：它的灰/亮原来跟着"勾了哪些位置和内容"走，进出这一页、在上面改勾选时都会
        // 闪一下，看着像坏了；而"能不能备"这件事，上面那几行的勾选框已经写清楚了。真按下去却
        // 没东西可备时，由 viewmodel 把原因说出来（见 `BackupViewModel.backupNow`）。
        Button(
            onClick = actions.onBackup,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.backup_now))
        }
    }
    locationItems()
    // 保留额度是"设一次就不管"的东西，位置与 Miuix 那一版相同：比它更该先被看到的
    // （位置、内容、按钮）都在上面，比它更常用的自动备份开关在下面。
    item { RetentionCardMaterial(state, actions) }
    // 自动备份是"设一次就不管"的开关，属于页面末尾的收尾设置；放在列表上面会把
    // 用户真正要点的备份项挤下去。
    item {
        Column {
            // 总开关和它下面那一行目的地是同一组的两段。
            SegmentedColumn {
                item {
                    SegmentedSwitchItem(
                        title = stringResource(R.string.backup_auto_title),
                        summary = stringResource(R.string.backup_auto_summary),
                        checked = state.autoBackupEnabled,
                        onCheckedChange = actions.onSetAutoBackup,
                    )
                }
                // 目的地是自动备份的子选项：总开关关着时它没有意义，所以**灰着不响应**，但要看得见。
                // 藏起来的话，"自动备份还能挑地方"这件事就只有在打开开关的那一刻才会被发现；
                // 灰着至少把这一栏摆在那儿，也顺带说明它和上面那个开关是一组。
                item {
                    // 目的地做成和上面「备份位置」一模一样的下拉框：这两处选的都是"写到哪几
                    // 处"，同一件事在两个地方长出两种控件（一边下拉、一边开关）只会让人以为是
                    // 两套规则。原来那两个开关还各占半行，现在一行下拉就说完了。
                    val localLabel = stringResource(R.string.backup_origin_local)
                    val cloudLabel = stringResource(R.string.backup_origin_cloud)
                    SegmentedDropdownItem(
                        title = stringResource(R.string.backup_auto_origin_group),
                        // 副标题只说这一栏是干什么的；没配地址时换成锁住的理由（和上面那行同一个说法）。
                        summary = if (state.cloudConfigured) {
                            stringResource(R.string.backup_auto_origin_hint)
                        } else {
                            stringResource(R.string.backup_cloud_chip_locked)
                        },
                        items = if (state.cloudConfigured) {
                            listOf(localLabel, cloudLabel, localLabel + "+" + cloudLabel)
                        } else {
                            listOf(localLabel)
                        },
                        enabled = state.autoBackupEnabled,
                        selectedIndex = originOptionIndex(state.autoBackupOrigins, state.cloudConfigured),
                        onItemSelected = { index ->
                            actions.onSelectAutoBackupOrigins(
                                originsForOption(state.cloudConfigured, index)
                            )
                        },
                    )
                }
            }
            // 上次自动备份的结果留在卡片**外面**：它是"这个功能最近干了什么"的流水账，不是
            // 这一栏的设置项；压在卡片里会被当成「自动备份位置」那一行的注脚。
            // 这一行小字二选一：要么是"一个目的地都没勾"的警告，要么是上次自动备份的结果。
            // 两条都画的话，警告冒出来会把下面整体推下去——而它恰恰是勾选触发的。
            val writesNothing = state.autoBackupWritesNothing
            val lastFailed = state.autoBackupRecord?.outcome?.let { it != AutoBackupOutcome.OK } == true
            Text(
                text = if (writesNothing) {
                    stringResource(R.string.backup_auto_dest_none)
                } else {
                    BackupText.autoBackupLine(context, state.autoBackupRecord, state.autoBackupEnabled)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (writesNothing || lastFailed) {
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
    locationItems()
    if (state.loading) {
        item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    }
    state.emptyText?.takeUnless { state.loading }?.let { empty ->
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
    //
    // 每行自带「本地」/「云端」标签：以前靠分段标题（只在两侧都勾时才出现），于是只勾一侧时
    // 根本看不出这份备份在哪——而"这份在云端、那份在本机"正是恢复前最该确认的事。
    // 有了行内标签，分段标题就成了重复信息，去掉。
    //
    // 长按进多选：删除是"整份抹掉"，和"点进去看看"共用一个手势迟早会出事，所以多选另起一个
    // 手势。多选时点行是勾选，右边的箭头换成勾选框——那时候"进去看"已经不是这一页在做的事。
    if (state.groups.isNotEmpty() && !state.selecting) {
        item {
            Text(
                text = stringResource(R.string.backup_selection_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    items(state.groups, key = { it.id }) { group ->
        val selected = group.id in state.selectedGroupIds
        SegmentedColumn {
            item {
                SegmentedListItem(
                    onClick = {
                        if (state.selecting) {
                            actions.onToggleGroupSelection(group)
                        } else {
                            actions.onOpenGroup(group)
                        }
                    },
                    // 已经在多选里了再长按另一行不该把勾选清空重来，那时候长按没有别的意思。
                    onLongClick = { if (!state.selecting) actions.onStartSelection(group) },
                    enabled = !state.loading,
                    leadingContent = if (state.selecting) {
                        {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = null,
                                modifier = Modifier.padding(end = 12.dp),
                            )
                        }
                    } else {
                        null
                    },
                    headlineContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            // 牌子紧跟在时间文字后面：它标的是"这一次写到哪几侧"，属于这一行的属性，
                            // 掉到下面那行会跟"内容 · 项数"抢位置，也让人以为它只描述内容。右对齐
                            // 会让它跑到屏幕另一头，读的时候得横跨整行去对，像是另一个字段。
                            Text(group.label)
                            group.origins.forEach { OriginTagMaterial(it) }
                        }
                    },
                    supportingContent = { Text(groupSummary(group)) },
                    trailingContent = {
                        if (!state.selecting) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                            )
                        }
                    },
                )
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
 * 「备份在哪」那一行。
 *
 * 两个分页都挂：「备份」页说的是"这次会写到哪"，「恢复」页说的是"列出来的这些在本机哪"。
 */
private fun LazyListScope.locationItems() {
    item(key = "location-local") { LocationLineMaterial() }
}
