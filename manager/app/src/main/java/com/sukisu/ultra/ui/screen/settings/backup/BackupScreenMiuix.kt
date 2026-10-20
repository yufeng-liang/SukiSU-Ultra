package com.sukisu.ultra.ui.screen.settings.backup

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.Icons
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sukisu.ultra.R
import com.sukisu.ultra.data.backup.AutoBackupOutcome
import com.sukisu.ultra.data.backup.BackupDefaults
import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin
import com.sukisu.ultra.data.backup.RetentionLimit
import com.sukisu.ultra.data.backup.WebDavPresets
import com.sukisu.ultra.ui.theme.LocalEnableBlur
import com.sukisu.ultra.ui.util.BackupText
import com.sukisu.ultra.ui.util.BlurredBar
import com.sukisu.ultra.ui.util.blockTaps
import com.sukisu.ultra.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import kotlin.math.roundToInt

/** 不可用的操作降到这个不透明度：还是那个颜色，但一眼看得出点不动。 */
private const val DISABLED_ALPHA = 0.38f

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupMiuix(
    state: BackupUiState,
    actions: BackupActions,
    snackbarHostState: SnackbarHostState,
    backupListState: LazyListState,
    restoreListState: LazyListState,
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    // 两个分页各有一份滚动位置：表单和清单的长度差得远，共用一份的话换页会停在
    // 对方那页的偏移上。
    val listState = if (state.tab == BackupTab.BACKUP) backupListState else restoreListState

    Scaffold(
        topBar = {
            // 顶栏是半透明的、列表从它下面滚过去（Miuix 那套模糊栏就是这么用的），所以这一层
            // 必须自己接住点击：不然点在两个分页标签中间会穿到下面那一行上（实测会把
            // 「选择要备份的模块」折叠掉）。见 blockTaps。
            Box(Modifier.blockTaps()) {
                BlurredBar(backdrop) {
                    TopAppBar(
                        color = barColor,
                        // 多选时顶栏换成"正在多选"的操作栏：标题说勾了几份，左边是取消、
                        // 右边是删除。列表行上的勾选框已经在说"选了什么"，这里补上"选了多少、
                        // 能干什么"。
                        title = if (state.selecting) {
                            stringResource(R.string.backup_selection_title, state.selectedGroups.size)
                        } else {
                            stringResource(R.string.backup_title)
                        },
                        scrollBehavior = scrollBehavior,
                        navigationIcon = {
                            if (state.selecting) {
                                IconButton(onClick = actions.onClearSelection) {
                                    Icon(imageVector = MiuixIcons.Close, contentDescription = null)
                                }
                            } else {
                                IconButton(onClick = actions.onBack) {
                                    Icon(imageVector = MiuixIcons.Back, contentDescription = null)
                                }
                            }
                        },
                        actions = {
                            if (state.selecting) {
                                // 删除是这一栏唯一的操作，也是唯一不可逆的：用误差色而不是默认
                                // 的主题色，让"按下去东西就没了"这件事在做之前就看得出来。
                                //
                                // 自己画而不是用 TextButton：这个版本的 Miuix 只给了 Primary 那套
                                // textButtonColors，没有 error 变体，所以这里拿 Text 直接染成误差色。
                                IconButton(
                                    onClick = actions.onDeleteSelected,
                                    enabled = state.selectedGroups.isNotEmpty() && !state.loading,
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Delete,
                                        contentDescription = stringResource(R.string.backup_selection_delete),
                                        tint = colorScheme.error,
                                    )
                                }
                            }
                        },
                        bottomContent = {
                            // 多选时收起分页标签：勾的是恢复页那份清单，把"备份/恢复"留在上面
                            // 等于给了一个会把勾选清掉的按钮。
                            if (!state.selecting) {
                                Column(
                                    modifier = Modifier
                                        .padding(horizontal = 12.dp)
                                        .padding(bottom = 6.dp),
                                ) {
                                    TabRow(
                                        tabs = BackupTab.entries.map { tabLabel(it, state.groups.size) },
                                        selectedTabIndex = state.tab.ordinal,
                                        onTabSelected = { actions.onSelectTab(BackupTab.entries[it]) },
                                        colors = TabRowDefaults.tabRowColors(backgroundColor = barColor),
                                    )
                                }
                            }
                        },
                    )
                }
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
            state = listState,
            contentPadding = innerPadding,
            overscrollEffect = null,
        ) {
            when (state.tab) {
                BackupTab.BACKUP -> backupTabItems(state, actions, context)
                BackupTab.RESTORE -> restoreTabItems(state, actions)
            }
            bottomSpacerItem()
        }
    }

    BackupProgressDialogMiuix(
        state = state,
        onDismiss = actions.onDismissBackupRun,
        onCancel = actions.onCancelBackupRun,
    )
    RestoreProgressDialogMiuix(
        run = state.restoreRun,
        onDismiss = actions.onDismissRestoreRun,
        onCancel = actions.onCancelRestore,
    )
}

/**
 * 「这家填过」的小圆点（Miuix 版，与 Material 的 `RememberedDot` 同一件事）。
 *
 * 颜色跟主色走：在没选中的灰底芯片上，它是这一行里唯一的彩色，一眼就能挑出哪几家配过。
 * 选中的芯片不用它——那家整块都是主色，点上去看不见，位子也留给"当前在这家"这件事本身。
 */
@Composable
private fun RememberedDotMiuix() {
    val description = stringResource(R.string.backup_cloud_remembered)
    Box(
        modifier = Modifier
            .padding(end = 6.dp)
            .size(8.dp)
            .background(colorScheme.primary, CircleShape)
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
private fun CloudCardMiuix(state: BackupUiState, actions: BackupActions) {
    Card(modifier = Modifier.fillMaxWidth()) {
        // 收起时只占一行多一点，标题右边就是当前填的地址——配置是一次性的事，
        // 铺开那块表单（说明 + 预设 + 三个输入框 + 两个按钮）会把列表挤到屏幕外。
        //
        // 这里不用 ArrowPreference：它自带的是朝右的箭头，而这一行是"展开/收起"，
        // 箭头必须朝下、展开后翻过来。
        //
        // 整块（标题行 + 下面那行地址）一起当点击区：地址也是"这一栏现在是什么状态"的一部分，
        // 只让标题行能点的话，手指落在地址上会像点空了。
        //
        // 箭头挂在整块外面、跟两行文字垂直居中（和「模块」那一行同一个结构）：留在标题行里的话
        // 它只对第一行居中，看着像往上跑了半行。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = actions.onToggleCloud)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                // 字号跟这一页绝大多数行取同一个来源：那些行是 Miuix 的
                // CheckboxPreference / SwitchPreference / OverlayDropdownPreference，
                // 标题用的就是 headline1。这里手写的几行原来写死 16sp，比旁边那几行小
                // 一档，一页里就出现了两个尺度；自己放大到 18sp 更糟，索性交给主题。
                Text(
                    text = stringResource(R.string.backup_cloud_title),
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                )
                // 地址整条给出来，最多两行：用户是照着它核对服务器的，截成域名就核对不了。
                // 这里说的是**已经生效**的那份（不是表单里正在编辑的草稿）：用户切到别家填了
                // 一半就收起这块时，这一行要是跟着草稿走，就成了"备份会写到那儿"的一句谎话。
                Text(
                    text = state.cloudSavedUrl.ifBlank {
                        stringResource(R.string.backup_cloud_summary_unset)
                    },
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            ExpandChevron(expanded = state.cloudExpanded)
        }
        // 展开要有过程：硬切时表单在标题行下面"跳"出来，看着像点错了。
        AnimatedVisibility(
            visible = state.cloudExpanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(tween(EXPAND_MS)),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(tween(EXPAND_MS)),
        ) {
            Column {
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
                            //
                            // 没选中的那几个里，记着可用配置的前面加一个小圆点：切到别家再切回来
                            // 时，用户得能看出哪几家是自己填过的（见 RememberedDotMiuix）。
                            //
                            // 比标准按钮小一圈：它跟下面三个输入框是一组"填表"的东西，
                            // 和页面底部那个「立即备份」同样体量会把表单的层级顶乱。
                            val remembered = preset.id in state.cloudRememberedSlots && preset.id != state.cloudSlot
                            Button(
                                onClick = { actions.onSelectPreset(preset) },
                                colors = if (preset.id == state.cloudSlot) {
                                    ButtonDefaults.buttonColorsPrimary()
                                } else {
                                    ButtonDefaults.buttonColors()
                                },
                                minWidth = 0.dp,
                                minHeight = 34.dp,
                                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (remembered) RememberedDotMiuix()
                                    Text(text = preset.label, fontSize = 13.sp)
                                }
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
                    // 两个动作等宽铺满一行：一长一短看着像没对齐的标签，而且"保存"是这一步的
                    // 落点，铺开之后它比"测试连接"更好按。测试连接是试一下，保持次一级的灰底；
                    // 保存用主色——两个都用灰底时，用户得读一遍字才知道该按哪个。
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = actions.onTestCloud,
                            modifier = Modifier.weight(1f),
                            enabled = !state.loading,
                        ) {
                            Text(text = stringResource(R.string.backup_cloud_test))
                        }
                        Button(
                            onClick = actions.onSaveCloud,
                            modifier = Modifier.weight(1f),
                            enabled = !state.loading,
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text(text = stringResource(R.string.backup_cloud_save))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 展开/收起用的箭头：朝下，展开后翻过来。
 *
 * 用 Material 的图标而不是 Miuix 的 `ExpandMore`——后者在这套图标里画的是一对直角括号加一个点，
 * 看不出"展开"的意思。详情页那几段也用同一个箭头，所以是 internal 而不是 private。
 */
@Composable
internal fun ExpandChevron(expanded: Boolean) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevron",
    )
    Icon(
        imageVector = Icons.Filled.ExpandMore,
        contentDescription = null,
        modifier = Modifier.graphicsLayer { rotationZ = rotation },
    )
}

/**
 * 「本地」/「云端」的小标签。
 *
 * 恢复之前最该确认的就是"这一份在哪一侧"，所以它得比旁边那行元信息显眼一点。两侧的颜色必须
 * 不一样：同样一块蓝色小牌子写着两个不同的词，扫一眼只会看成"两个标签"，而这两侧点下去一个
 * 动本机文件、一个走网络——配色本身就是提示（本机=主题主色，云端=主题第三色，深浅主题各一套）。
 */
@Composable
private fun OriginTagMiuix(origin: BackupOrigin) {
    Text(
        text = stringResource(BackupLabels.origin(origin)),
        fontSize = 11.sp,
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
 * 只写本机：云端地址就写在上面那张 WebDAV 卡片的标题栏里（[CloudCardMiuix]），再写一遍是
 * 同一句话说两次。本机这个目录别处没有，所以留着——用户是照着它去文件管理器找文件的。
 *
 * 常显，不跟着「本地」的勾选走：跟着勾选走的话，取消勾选会把这一行抽掉，下面的内容跟着跳。
 */
@Composable
private fun LocationLineMiuix() {
    Text(
        text = stringResource(R.string.backup_location_local, BackupDefaults.LOCAL_DIR),
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
 * 「模块」那一行。
 *
 * 它同时承担两件事，和 Material 那一版一致：勾选框管"全选 / 全不选"，行本身展开细目。
 * 所以不能直接用 CheckboxPreference——那个整行点下去都会翻转勾选框，展开就没地方点了。
 * 只选了其中几个（而不是全部）时勾选框要画成半选，不然"部分选中"和"一个都没选"长得一样。
 */
@Composable
private fun ModuleRowMiuix(state: BackupUiState, actions: BackupActions) {
    val selectedCount = state.modules.count { it.id in state.selectedModuleIds }
    val allSelected = state.modules.isEmpty() || selectedCount == state.modules.size
    val partial = selectedCount in 1 until state.modules.size
    val expandable = state.modules.isNotEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = expandable, onClick = actions.onToggleModules)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            state = when {
                allSelected -> ToggleableState.On
                partial -> ToggleableState.Indeterminate
                else -> ToggleableState.Off
            },
            // 半选时点一下就是"补全到全选"，和 Material 那边一致。
            onClick = { actions.onSetAllModules(!allSelected) },
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = stringResource(R.string.backup_kind_module),
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
            )
            Text(
                text = moduleSummaryMiuix(state),
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (expandable) {
            ExpandChevron(expanded = state.modulesExpanded)
        }
    }
}

/** 「模块」那一行的副标题：读不到列表 / 一个都没装 / 正常，三种情况各说各的。 */
@Composable
private fun moduleSummaryMiuix(state: BackupUiState): String = when {
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
 * 比这一页的主行小一号、矮一档：这是"挂在主行下面"的一层。以前用 [CheckboxPreference] 铺，
 * 它按 headline1 加默认内边距算高度，一行 200px——一屏只放得下五个模块，字号又和"模块"这一栏
 * 本身一样大，主次分不出来。这里自己搭一行：标题降一档到 body1，上下各留 6dp，行高落到
 * 140px 上下，字号和间距一起收。
 *
 * 勾选框只报状态、点整行都算，所以用 `Checkbox` 的 state + onClick 那一版。
 */
@Composable
internal fun SubEntryRowMiuix(
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    /** false 时整行点不动、勾选框置灰——恢复跑着的时候勾选不该再动。 */
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = { onCheckedChange(!checked) },
            enabled = enabled,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = title,
                fontSize = MiuixTheme.textStyles.body1.fontSize,
            )
            summary?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * 「模块」那一行下面的勾选列表。
 *
 * 默认全选、整块收起：多数人是要全部，把十几个模块铺出来只会把下面的按钮和列表顶走。
 * 调用方只在有模块可挑时才调用它。这里的"全选"那一行已经并到上面的行里去了，不再重复摆一个。
 */
@Composable
private fun ModulePickerMiuix(state: BackupUiState, actions: BackupActions) {
    // 和云端那块一样给个展开过程：箭头转着、列表却"跳"出来，两件事对不上。
    AnimatedVisibility(
        visible = state.modulesExpanded,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(tween(EXPAND_MS)),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(tween(EXPAND_MS)),
    ) {
        Column {
            val disabledLabel = stringResource(R.string.backup_row_disabled)
            state.modules.forEach { module ->
                SubEntryRowMiuix(
                    title = ModuleOptionText.title(module),
                    summary = ModuleOptionText.summary(module, disabledLabel),
                    checked = module.id in state.selectedModuleIds,
                    onCheckedChange = { actions.onToggleModule(module.id) },
                )
            }
        }
    }
}

/**
 * 保留额度那一块：两个滑块（模块 / boot）。
 *
 * 默认收起：它是"设一次就不管"的东西，三条滑块常驻会把上面真正要用的备份选项顶到屏幕外。
 * 收起时标题右边写着当前三个值，不改也能看见现在是多少。
 *
 * 收起时显示的是**读回来的值**，不是滑块拖到的中间值——滑块的 `onValueChange` 只在松手时
 * 提交，中间过程不落盘也不进状态，免得一次拖动写出十几个偏好项。
 */
@Composable
private fun RetentionCardMiuix(state: BackupUiState, actions: BackupActions) {
    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = actions.onToggleRetention)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.backup_retention_title),
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                )
                Text(
                    text = retentionSummary(state),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            ExpandChevron(expanded = state.retentionExpanded)
        }
        AnimatedVisibility(
            visible = state.retentionExpanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(tween(EXPAND_MS)),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(tween(EXPAND_MS)),
        ) {
            Column {
                Text(
                    text = stringResource(R.string.backup_retention_summary),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
                RetentionSliderMiuix(
                    title = stringResource(R.string.backup_retention_module),
                    value = state.moduleRetention,
                    max = RetentionLimit.MAX,
                    onCommit = actions.onSetModuleRetention,
                )
                RetentionSliderMiuix(
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

/** 一个额度滑块：标题、当前值、一条 1…[max] 的滑轨。 */
@Composable
private fun RetentionSliderMiuix(
    title: String,
    value: Int,
    max: Int,
    onCommit: (Int) -> Unit,
    summary: String? = null,
) {
    val dragging = remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(text = title, fontSize = 15.sp)
                summary?.let {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Text(
                text = stringResource(R.string.backup_retention_count, dragging.floatValue.roundToInt()),
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
        Slider(
            value = dragging.floatValue,
            onValueChange = { dragging.floatValue = it.roundToInt().toFloat() },
            // 松手才落盘：拖动途中每个整数都写一次偏好项，等于把一次操作变成十几次磁盘写入。
            onValueChangeFinished = { onCommit(dragging.floatValue.roundToInt()) },
            valueRange = RetentionLimit.MIN.toFloat()..max.toFloat(),
            hapticEffect = SliderDefaults.SliderHapticEffect.Step,
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
    item { CloudCardMiuix(state, actions) }
    item {
        Column {
            GroupTitle(stringResource(R.string.backup_origin_group))
            Card(modifier = Modifier.fillMaxWidth()) {
                // 和 Material 那一版同一套选项、同一套默认值：能挑的就是"本地 / 云端 /
                // 两处都写"这三档，不再用两个勾选框去拼——拼出来的组合里"两个都不勾"是一个
                // 没法执行的状态，下拉框里根本不会有它。
                val localLabel = stringResource(R.string.backup_origin_local)
                val cloudLabel = stringResource(R.string.backup_origin_cloud)
                OverlayDropdownPreference(
                    title = stringResource(R.string.backup_origin_group),
                    // 右边那列已经写着当前这一档了，副标题再写一遍"写到哪"是重复的；
                    // 这里说的是"这个框是干嘛的"，位置本身就由右边的值回答。
                    summary = if (state.cloudConfigured) {
                        stringResource(R.string.backup_origin_hint)
                    } else {
                        // 没配地址时"云端"那一档不存在，得说清楚为什么少了它。
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
                    onSelectedIndexChange = { index ->
                        actions.onSelectOrigins(originsForOption(state.cloudConfigured, index))
                    },
                )
            }
        }
    }
    item {
        Column {
            GroupTitle(stringResource(R.string.backup_kind_group))
            Card(modifier = Modifier.fillMaxWidth()) {
                // 模块这一行既是"备不备模块"也是"备哪些"：勾选框管全选/全不选，行本身展开
                // 细目。所以它不用 CheckboxPreference（那个整行点击都会翻转勾选框），
                // 而是和 Material 一样自己拼一行。
                ModuleRowMiuix(state = state, actions = actions)
                // 展开的细目必须紧跟在"模块"这一行下面：摆到分区快照 / 仓库源后面，看着就像
                // 那两栏的子项，勾的到底是谁全靠猜。
                // 没得挑的时候不摆一个空列表出来：为什么没得挑写在上面那一行的副标题里。
                // 列表不再跟着模块那个勾选框走：勾选框现在管全选/全不选，取消全选之后
                // 还得能一个个勾回来，列表要是跟着消失就没有回头路了。
                if (state.modules.isNotEmpty()) {
                    ModulePickerMiuix(state = state, actions = actions)
                }
                // 分区快照抓的是当前设备上 boot / init_boot / vendor_boot 里存在的那些，
                // 恢复是往块设备写镜像（不可撤销）。这句话常显而不是选中才出现：选中时冒出来会
                // 把它下面的东西整体推下去。
                CheckboxPreference(
                    title = stringResource(R.string.backup_kind_partition),
                    summary = stringResource(R.string.backup_partition_explain),
                    checked = BackupKind.PARTITION in state.kinds,
                    onCheckedChange = { actions.onToggleKind(BackupKind.PARTITION) },
                )
                // 压缩开关只影响分区快照这一栏，所以紧跟着它，而不是塞进保留额度那张卡。
                // 默认开：boot 96MB 压完约 20MB，云端那一步能省好几倍时间。
                // 未勾选分区快照时禁用：它是这一栏的附属开关，勾选框没勾时改它没有着落。
                SwitchPreference(
                    title = stringResource(R.string.backup_partition_compress_title),
                    summary = stringResource(R.string.backup_partition_compress_summary),
                    checked = state.partitionCompressed,
                    enabled = BackupKind.PARTITION in state.kinds,
                    onCheckedChange = actions.onSetPartitionCompressed,
                )
                // 仓库源配置整批备份、整批恢复：卸载重装管理器最容易丢的就是它，所以
                // 默认勾上（见 BackupUiState.kinds），summary 说明备份的是哪些东西。
                CheckboxPreference(
                    title = stringResource(R.string.backup_kind_repo),
                    summary = stringResource(R.string.backup_repo_explain),
                    checked = BackupKind.REPO_CONFIG in state.kinds,
                    onCheckedChange = { actions.onToggleKind(BackupKind.REPO_CONFIG) },
                )
            }
        }
    }
    item {
        // 这一页只剩一个动作了（导入搬去了恢复页，它不产生新备份，只是把别处的一份收进来），
        // 那就铺满整行——半个宽度的主按钮看着像还有别的选项没显示出来。
        TextButton(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            text = stringResource(R.string.backup_now),
            // 不禁用：这个按钮原来是"在加载中"或"没勾够"就变灰，进出页面都能看它闪一下；
            // 真正该拦的情况由 backupNow() 里那句提示兜住，按钮本身始终是可点的样子。
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = actions.onBackup,
        )
    }
    locationItems()
    // 保留额度是"设一次就不管"的东西，放在备份按钮和自动备份之间：比它更该先被看到的
    // （位置、内容、按钮）都在上面，比它更常用的自动备份开关在下面。
    item { RetentionCardMiuix(state, actions) }
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
            // 目的地是自动备份的子选项：总开关关着时它没有意义，所以**灰着不响应**，但要看得见。
            // 藏起来的话，"自动备份还能挑地方"这件事就只有在打开开关的那一刻才会被发现；
            // 灰着至少把这一栏摆在那儿，也顺带说明它和上面那个开关是一组。
            // 目的地和上面「备份位置」用同一个下拉框：这两处选的都是"写到哪几处"，同一件
            // 事在两个地方长出两种控件（一边下拉、一边开关）只会让人以为是两套规则。
            // 原来那两个开关还各占半行，现在一行下拉就说完了。
            val localLabel = stringResource(R.string.backup_origin_local)
            val cloudLabel = stringResource(R.string.backup_origin_cloud)
            OverlayDropdownPreference(
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
                onSelectedIndexChange = { index ->
                    actions.onSelectAutoBackupOrigins(
                        originsForOption(state.cloudConfigured, index)
                    )
                },
            )
        }
        // 上次自动备份的结果留在卡片**外面**：它是"这个功能最近干了什么"的流水账，不是这一栏
        // 的设置项；压在卡片里会被当成「自动备份位置」那一行的注脚。
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
            fontSize = 12.sp,
            color = if (writesNothing || lastFailed) colorScheme.error else colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
    }
}

/** 「恢复」分页：已备份的清单。点一行进详情，恢复/导出/删除都只在那里发生。 */
private fun LazyListScope.restoreTabItems(state: BackupUiState, actions: BackupActions) {
    item { CloudCardMiuix(state, actions) }
    locationItems()
    if (state.loading) {
        item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) }
    }
    // 空列表必须给一句话：什么都不显示的话，用户分不清"没有备份"和"这一页坏了"。
    state.emptyText?.takeUnless { state.loading }?.let { empty ->
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
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp),
            )
        }
    }
    items(state.groups, key = { it.id }) { group ->
        val selected = group.id in state.selectedGroupIds
        Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        enabled = !state.loading,
                        onClick = {
                            if (state.selecting) {
                                actions.onToggleGroupSelection(group)
                            } else {
                                actions.onOpenGroup(group)
                            }
                        },
                        // 已经在多选里了再长按另一行不该把勾选清空重来，那时候长按没有别的意思。
                        onLongClick = { if (!state.selecting) actions.onStartSelection(group) },
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.selecting) {
                    Checkbox(
                        state = if (selected) ToggleableState.On else ToggleableState.Off,
                        // 勾选框自己吃掉这一下：不然行和框各切一次，等于没点。
                        onClick = { actions.onToggleGroupSelection(group) },
                        enabled = !state.loading,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // 牌子紧跟在时间文字后面：它标的是"这一次写到哪几侧"，属于这一行的属性，
                        // 掉到下面那行会跟"内容 · 项数"抢位置，也让人以为它只描述内容。右对齐
                        // 会让它跑到屏幕另一头，读的时候得横跨整行去对，像是另一个字段。
                        Text(
                            text = group.label,
                            fontSize = MiuixTheme.textStyles.headline1.fontSize,
                        )
                        group.origins.forEach { OriginTagMiuix(it) }
                    }
                    Text(
                        text = groupSummary(group),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (!state.selecting) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                    )
                }
            }
        }
    }
    // 导入放在清单末尾：它不产生新备份，只是把别处的一份收进来、让它出现在上面这个列表里，
    // 所以属于恢复这一侧。列表为空时它紧跟在空状态下面，一眼就能看到。
    item {
        TextButton(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            text = stringResource(R.string.backup_import),
            enabled = !state.loading,
            onClick = actions.onImport,
        )
    }
}

/**
 * 「本机备份在 …」那一行。
 *
 * 只写本机：云端地址就写在上面那张 WebDAV 卡片的标题栏里（[CloudCardMiuix]），再写一遍是
 * 同一句话说两次。本机这个目录别处没有，所以留着——用户是照着它去文件管理器找文件的。
 *
 * 常显，不跟着「本地」的勾选走：跟着勾选走的话，取消勾选会把这一行抽掉，下面的内容跟着跳。
 */
private fun LazyListScope.locationItems() {
    item(key = "location-local") { LocationLineMiuix() }
}

/**
 * 底部留白。
 *
 * 这一页的 contentWindowInsets 只要了水平方向（顶栏自己吃状态栏），所以底部 inset 不在
 * innerPadding 里。不自己留出这段高度，滚到底时最后一行就贴在屏幕边缘、被手势条压掉一半。
 */
private fun LazyListScope.bottomSpacerItem() {
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
