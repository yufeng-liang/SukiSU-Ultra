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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sukisu.ultra.R
import com.sukisu.ultra.data.backup.AutoBackupOutcome
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
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
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
                                Text(
                                    text = stringResource(R.string.backup_selection_delete),
                                    fontSize = 16.sp,
                                    color = if (state.selectedGroups.isNotEmpty() && !state.loading) {
                                        colorScheme.error
                                    } else {
                                        colorScheme.error.copy(alpha = DISABLED_ALPHA)
                                    },
                                    modifier = Modifier
                                        .padding(horizontal = 12.dp)
                                        .clickable(
                                            enabled = state.selectedGroups.isNotEmpty() && !state.loading,
                                            onClick = actions.onDeleteSelected,
                                        ),
                                )
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

    BackupProgressDialogMiuix(state = state, onDismiss = actions.onDismissBackupRun)
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
                // 这一栏是整页最上面的一块，标题要比下面那些分组标题（14sp 灰字）明显：
                // 字号大两号再加一档字重，扫一眼就知道这一栏说的是云端。
                Text(
                    text = stringResource(R.string.backup_cloud_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                // 地址整条给出来，最多两行：用户是照着它核对服务器的，截成域名就核对不了。
                Text(
                    text = state.cloudUrl.ifBlank {
                        stringResource(R.string.backup_cloud_summary_unset)
                    },
                    fontSize = 13.sp,
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
                            // 比标准按钮小一圈：它跟下面三个输入框是一组"填表"的东西，
                            // 和页面底部那个「立即备份」同样体量会把表单的层级顶乱。
                            Button(
                                onClick = { actions.onSelectPreset(preset) },
                                colors = if (preset == state.selectedPreset) {
                                    ButtonDefaults.buttonColorsPrimary()
                                } else {
                                    ButtonDefaults.buttonColors()
                                },
                                minWidth = 0.dp,
                                minHeight = 34.dp,
                                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(text = preset.label, fontSize = 13.sp)
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
 * 看不出"展开"的意思。
 */
@Composable
private fun ExpandChevron(expanded: Boolean) {
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
    // 和云端那一行一样自己画箭头：ArrowPreference 自带的是朝右的箭头，而这两行都是展开/收起。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = actions.onToggleModules)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = stringResource(R.string.backup_module_select), fontSize = 16.sp)
            Text(
                text = stringResource(
                    R.string.backup_module_selected,
                    state.selectedModuleIds.size,
                    state.modules.size,
                ),
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        ExpandChevron(expanded = state.modulesExpanded)
    }
    // 和云端那块一样给个展开过程：箭头转着、列表却"跳"出来，两件事对不上。
    AnimatedVisibility(
        visible = state.modulesExpanded,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(tween(EXPAND_MS)),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(tween(EXPAND_MS)),
    ) {
        Column {
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
                Text(text = stringResource(R.string.backup_retention_title), fontSize = 16.sp)
                Text(
                    text = retentionSummary(state),
                    fontSize = 13.sp,
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
                    title = stringResource(R.string.backup_retention_boot),
                    summary = stringResource(R.string.backup_retention_boot_summary),
                    value = state.bootRetention,
                    max = RetentionLimit.MAX_BOOT,
                    onCommit = actions.onSetBootRetention,
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
        // 这一页只剩一个动作了（导入搬去了恢复页，它不产生新备份，只是把别处的一份收进来），
        // 那就铺满整行——半个宽度的主按钮看着像还有别的选项没显示出来。
        TextButton(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            text = stringResource(R.string.backup_now),
            enabled = !state.loading && state.canBackUp,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = actions.onBackup,
        )
    }
    locationItems(state)
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
            // 目的地是自动备份的子选项：总开关关着时它们没有意义，灰掉而不是藏起来——
            // 藏起来用户就不知道"自动备份还能挑地方"，灰着至少看得见，也知道要先开总开关。
            CheckboxPreference(
                title = stringResource(R.string.backup_origin_local),
                checked = state.autoBackupLocal,
                enabled = state.autoBackupEnabled,
                onCheckedChange = actions.onSetAutoBackupLocal,
            )
            CheckboxPreference(
                title = stringResource(R.string.backup_origin_cloud),
                // 和「备份位置」同一个规矩：没配好地址就锁住，并说明为什么。勾了也写不出去，
                // 让它停在"看着生效其实不生效"的样子只会让人以为云端备份在跑。
                summary = if (state.cloudConfigured) null else stringResource(R.string.backup_cloud_chip_locked),
                checked = state.autoBackupTargets.cloud,
                enabled = state.autoBackupEnabled && state.cloudConfigured,
                onCheckedChange = actions.onSetAutoBackupCloud,
            )
            if (state.autoBackupWritesNothing) {
                Text(
                    text = stringResource(R.string.backup_auto_dest_none),
                    fontSize = 12.sp,
                    color = colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
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

/** 「恢复」分页：已备份的清单。点一行进详情，恢复/导出/删除都只在那里发生。 */
private fun LazyListScope.restoreTabItems(state: BackupUiState, actions: BackupActions) {
    item { CloudCardMiuix(state, actions) }
    locationItems(state)
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
                    Text(text = group.label, fontSize = 16.sp)
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OriginTagMiuix(group.origin)
                        Text(
                            text = groupSummary(group, showsOrigin = false),
                            fontSize = 13.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
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
 * 「备份存在哪」那几行。
 *
 * 两个分页都挂：「备份」页说的是"这次会写到哪"，「恢复」页说的是"列出来的这些在哪"。
 * 完整路径/地址照原样给出来（见 [LocationLineMiuix]），用户是照着它去文件管理器或
 * NAS 上找文件的。
 */
private fun LazyListScope.locationItems(state: BackupUiState) {
    items(state.orderedOrigins, key = { "location-${it.name}" }) { origin ->
        LocationLineMiuix(origin = origin, cloudUrl = state.cloudSavedUrl)
    }
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
