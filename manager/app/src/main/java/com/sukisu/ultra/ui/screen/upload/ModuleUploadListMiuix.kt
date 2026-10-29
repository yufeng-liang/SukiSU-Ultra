package com.sukisu.ultra.ui.screen.upload

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.component.ScrollToTopOnChange
import com.sukisu.ultra.ui.component.SearchStatus
import com.sukisu.ultra.ui.component.miuix.SearchBarFake
import com.sukisu.ultra.ui.component.miuix.SearchBox
import com.sukisu.ultra.ui.component.miuix.SearchPager
import com.sukisu.ultra.ui.theme.LocalEnableBlur
import com.sukisu.ultra.ui.util.BlurredBar
import com.sukisu.ultra.ui.util.CheckStatus
import com.sukisu.ultra.ui.util.UpdateEligibility
import com.sukisu.ultra.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Miuix 风格的上传列表。
 *
 * 这一屏是**状态板**：只回答「这一版收录了吗」，不做提交。真正的提交从挑 zip 开始，
 * 挑中的包会直接进表单（[ModuleUploadFormMiuix]）。
 *
 * 状态与 Material 那套共用 [ModuleUploadListState]；这里只负责把它画出来。
 */
@Composable
fun ModuleUploadListMiuix(
    state: ModuleUploadListState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onFilter: (ModuleUploadRules.Filter) -> Unit,
    onSearchStatusChange: (SearchStatus) -> Unit,
    onToggleRow: (String) -> Unit,
    onPickForRow: (UploadRow) -> Unit,
    onUseArchived: (UploadRow) -> Unit,
    /** 打开「为什么只能传原始 zip」的说明。 */
    onOpenInfo: () -> Unit,
    onPickZip: () -> Unit,
    onOpenArchiveSettings: () -> Unit,
    /** 上传记录（`GET /mine`）：只用来算每行的「上传更新」标签，列表本身不再展开它。 */
    mine: MineState,
    /** 打开上传记录页。 */
    onOpenHistory: () -> Unit,
) {
    val searchStatus = state.searchStatus
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    val visible = state.visibleRows
    // 筛选/搜索一变就把列表拉回顶部：结果变短后 LazyColumn 会把滚动位置夹到末尾。
    val listState = rememberLazyListState()
    val latestVisible = rememberUpdatedState(visible)
    ScrollToTopOnChange(listState, state.filter, state.query) { latestVisible.value }
    val checked = state.rows.count { it.status != null }
    // 每个模块投过的最新版本号；「上传更新」标签的判据之一（另一个是这一行的版本号）。
    val uploadedMax = remember(mine.modules) { ModuleUploadRules.uploadedMaxVersions(mine.modules) }
    val density = LocalDensity.current
    // 顶栏收起时假搜索条跟着沉下去：与模块页/仓库页同一公式，展开搜索后由 SearchPager 接管。
    val dynamicTopPadding by remember(scrollBehavior) {
        derivedStateOf { 12.dp * (1f - scrollBehavior.state.collapsedFraction) }
    }
    // 本页 ViewModel 不管理 resultStatus（与 SulogMiuix 同一处境），在屏内按搜索词派生：
    // 有词 SHOW（画搜索结果），无词 DEFAULT。所有状态回写都走这一个出口。
    val handleSearchStatusChange: (SearchStatus) -> Unit = { next ->
        onSearchStatusChange(
            next.copy(
                resultStatus = if (next.searchText.isBlank()) {
                    SearchStatus.ResultStatus.DEFAULT
                } else {
                    SearchStatus.ResultStatus.SHOW
                },
            )
        )
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                searchStatus.TopAppBarAnim(backgroundColor = barColor) {
                    TopAppBar(
                        color = barColor,
                        title = stringResource(R.string.module_upload_title),
                        scrollBehavior = scrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                val layoutDirection = LocalLayoutDirection.current
                                Icon(
                                    modifier = Modifier.graphicsLayer {
                                        if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                    },
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = null,
                                )
                            }
                        },
                        // 顶栏只留「解释」「记录」「更多」三件事：都是点一下就走。
                        // 刷新是「等一下再看」，已经挪到列表下拉（见 content 里的 PullToRefresh）。
                        actions = {
                            // 「为什么只能传原始 zip」的说明入口：重建包那条路关掉之后，
                            // 已经装过的模块在这页没有上传入口，解释放在这里。
                            IconButton(onClick = onOpenInfo) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = stringResource(R.string.module_upload_info_open),
                                    tint = colorScheme.onSurface,
                                )
                            }
                            // 上传记录：回答「我以前投过什么」，与「这一版收录了没有」是两件事。
                            IconButton(onClick = onOpenHistory) {
                                Icon(
                                    imageVector = MiuixIcons.Notes,
                                    contentDescription = stringResource(R.string.module_upload_history_open),
                                    tint = colorScheme.onSurface,
                                )
                            }
                            // 刷新不占顶栏：与模块页/仓库页一样改成列表下拉。
                            // 留存开关与清理入口都在这个按钮背后：两件事都只跟「占空间」有关，
                            // 顶栏也不该为此多摆一个开关。
                            // 载入完成前先禁掉：面板里的开关初值来自 settings，而列表状态里的
                            // keepZip 默认是 true —— 这一小段窗口里打开面板会显示一个用户其实
                            // 已经关掉的开关，点一下反而把设置写成 true。
                            IconButton(
                                onClick = onOpenArchiveSettings,
                                enabled = !state.loading && !state.refreshing,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.MoreCircle,
                                    contentDescription = stringResource(R.string.module_upload_more),
                                    tint = colorScheme.onSurface,
                                )
                            }
                        },
                        // 假搜索条三件套照仓库页抄：收起时可见可点（点它展开真搜索条），
                        // 展开时整体隐藏，并把自身位置写回 offsetY 供 SearchPager 定位。
                        bottomContent = {
                            // 假搜索条 + 刷新进度条：后者是顶栏最底边那一条，
                            // 槽位常驻 3dp、只在忙时画条，所以进度条出现/消失
                            // 不会让整块顶栏改变高度（下面的列表也就不跟着跳）。
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Box(
                                    modifier = Modifier
                                        .alpha(if (searchStatus.isCollapsed()) 1f else 0f)
                                        .onGloballyPositioned { coordinates ->
                                            with(density) {
                                                val newOffsetY = coordinates.positionInWindow().y.toDp()
                                                if (searchStatus.offsetY != newOffsetY) {
                                                    handleSearchStatusChange(
                                                        searchStatus.copy(offsetY = newOffsetY)
                                                    )
                                                }
                                            }
                                        }
                                        .then(
                                            if (searchStatus.isCollapsed()) {
                                                Modifier.pointerInput(Unit) {
                                                    detectTapGestures {
                                                        handleSearchStatusChange(
                                                            searchStatus.copy(current = SearchStatus.Status.EXPANDING)
                                                        )
                                                    }
                                                }
                                            } else Modifier,
                                        ),
                                ) {
                                    SearchBarFake(searchStatus.label, dynamicTopPadding)
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.dp),
                                ) {
                                    // 刷新中不画：那一刻下拉指示器正悬在列表顶上转，
                                    // 两条进度说同一件事，一条就够 —— 这条留给它管不到的
                                    // 「首次载入」和「查重」。
                                    if (state.busy && !state.refreshing) {
                                        LinearProgressIndicator(
                                            progress = null,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }
        },
        // 搜索展开后盖住整页的结果层：SearchPager 自带背景动画、取消按钮与返回手势。
        popupHost = {
            searchStatus.SearchPager(
                onSearchStatusChange = handleSearchStatusChange,
                defaultResult = {},
                searchBarTopPadding = dynamicTopPadding,
            ) {
                // 搜索结果复用主列表的行渲染与筛选逻辑：搜的就是同一份 visibleRows。
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .overScrollVertical(),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    if (visible.isEmpty()) {
                        // 搜空了复用主列表的空态：同一份文案与「清掉筛选」出口，不另造字符串。
                        item(key = "search_empty") {
                            EmptyStateMiuix(
                                filtered = state.rows.isNotEmpty(),
                                // 同 Material 侧：扫描失败与「筛选后为空」说不同的话。
                                failed = state.scanFailed,
                                onReset = {
                                    onFilter(ModuleUploadRules.Filter.ALL)
                                    handleSearchStatusChange(searchStatus.copy(searchText = ""))
                                },
                            )
                        }
                    } else {
                        itemsIndexed(visible, key = { _, row -> row.id }) { index, row ->
                            UploadRowCardMiuix(
                                row = row,
                                expanded = state.expandedId == row.id,
                                onToggle = { onToggleRow(row.id) },
                                onPick = { onPickForRow(row) },
                                onUseArchived = { onUseArchived(row) },
                                uploadUpdate = ModuleUploadRules.showsUploadUpdate(
                                    row.versionCode,
                                    row.status,
                                    uploadedMax[row.id],
                                ),
                                // 闸门在 ViewModel 里是全局的，搜索结果里的挑包按钮同样照办。
                                picking = state.pickingFor != null,
                                pickingThis = state.pickingFor == row.id,
                                topGap = if (index == 0) 8.dp else 12.dp,
                            )
                        }
                    }
                    item {
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        searchStatus.SearchBox {
            // 下拉刷新：与模块页/仓库页同一个手势、同一组提示语。
            // 刷新那颗键原来占着顶栏，要「先找到、再按下」；这一屏的操作重心就在列表上。
            val refreshTexts = listOf(
                stringResource(R.string.refresh_pulling),
                stringResource(R.string.refresh_release),
                stringResource(R.string.refresh_refresh),
                stringResource(R.string.refresh_complete),
            )
            PullToRefresh(
                // 只跟刷新走，不跟 busy：busy 里还含「查重」和首次载入，
                // 那两件事由顶栏底边那条细进度条负责。
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                refreshTexts = refreshTexts,
                // 只留顶部：下拉指示器要落在顶栏下面，不能钻进标题栏里。
                contentPadding = PaddingValues(top = innerPadding.calculateTopPadding()),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxHeight()
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .padding(horizontal = 12.dp),
                    state = listState,
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        // 底边自己补导航栏：Scaffold 的 insets 只留了水平方向。
                        bottom = innerPadding.calculateBottomPadding() +
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    ),
                    overscrollEffect = null,
                ) {
                    item {
                        FilterRow(
                            selected = state.filter,
                            onFilter = onFilter,
                        )
                    }

                    // 查重进度：只有一句计数，永远占一行 —— 「继续查」按钮和它下面那句说明
                    // 会随 remaining 消失，查完那一刻整块缩一行、下面内容跟着上移一截。
                    if (state.checking || checked > 0) {
                        item(key = "check_progress") {
                            CheckProgressMiuix(
                                checked = checked,
                                total = state.rows.size,
                                checking = state.checking,
                                fromCache = state.fromCache,
                            )
                        }
                    }

                    item {
                        Card(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth(),
                        ) {
                            ArrowPreference(
                                title = stringResource(R.string.module_upload_pick_zip),
                                summary = stringResource(R.string.module_upload_pick_zip_summary),
                                startAction = {
                                    Icon(
                                        MiuixIcons.UploadCloud,
                                        modifier = Modifier.padding(end = 6.dp),
                                        // 这一行的标题就是「选择 zip 上传」，图标再报一遍会被 TalkBack 念两次。
                                        contentDescription = null,
                                        tint = colorScheme.onBackground,
                                    )
                                },
                                onClick = onPickZip,
                            )
                        }
                    }

                    // 「上传记录」不占这一页：它挪到了顶栏图标背后（见 onOpenHistory）。

                    // 已装模块这一组也给标题：上面两组都有名有姓，这一组裸着会读成「还没结束的上一组」。
                    item {
                        SmallTitle(
                            text = stringResource(R.string.module_upload_section_installed),
                            modifier = Modifier.padding(top = 20.dp),
                            // 纵向内边距收成 0：组与组之间的 20dp、标题与内容的 8dp 由这里和卡片自己给，
                            // 否则默认内边距会把间距叠成两倍。
                            insideMargin = PaddingValues(
                                start = 16.dp,
                                top = 0.dp,
                                end = 16.dp,
                                bottom = 0.dp,
                            ),
                        )
                    }

                    // 加载中不画空态：首帧 rows 一定是空的，先报「没有已安装的模块」，
                    // 等扫描回来又变成有，用户看到的是系统在说假话。
                    if (visible.isEmpty() && !state.loading) {
                        // 空态：图标 + 主文案 + 副说明；筛选筛空时再给一枚「清掉筛选」的动作。
                        // 组标题仍然保留，否则「已装在本机」下面突然空一块说不清是哪一组空了。
                        item(key = "empty_state") {
                            EmptyStateMiuix(
                                filtered = state.rows.isNotEmpty(),
                                failed = state.scanFailed,
                                onReset = {
                                    onFilter(ModuleUploadRules.Filter.ALL)
                                    handleSearchStatusChange(searchStatus.copy(searchText = ""))
                                },
                            )
                        }
                    } else {
                        itemsIndexed(visible, key = { _, row -> row.id }) { index, row ->
                            UploadRowCardMiuix(
                                row = row,
                                expanded = state.expandedId == row.id,
                                onToggle = { onToggleRow(row.id) },
                                onPick = { onPickForRow(row) },
                                onUseArchived = { onUseArchived(row) },
                                uploadUpdate = ModuleUploadRules.showsUploadUpdate(
                                    row.versionCode,
                                    row.status,
                                    uploadedMax[row.id],
                                ),
                                // 闸门在 ViewModel 里是全局的（pickingFor != null 就拒绝一切挑包），
                                // 界面照这个来：任一行为 true 时所有挑包按钮都禁用，只有那一行转圈。
                                picking = state.pickingFor != null,
                                pickingThis = state.pickingFor == row.id,
                                // 组内第一张卡紧跟标题：8dp；其余卡片之间 12dp。
                                topGap = if (index == 0) 8.dp else 12.dp,
                            )
                        }
                    }

                    item {
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(
    selected: ModuleUploadRules.Filter,
    onFilter: (ModuleUploadRules.Filter) -> Unit,
) {
    val labels = listOf(
        ModuleUploadRules.Filter.ALL to R.string.module_upload_filter_all,
        ModuleUploadRules.Filter.NOT_RECORDED to R.string.module_upload_filter_not_recorded,
        ModuleUploadRules.Filter.RECORDED to R.string.module_upload_filter_recorded,
        ModuleUploadRules.Filter.UNKNOWN to R.string.module_upload_filter_unknown,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 与查重进度、组标题的 12dp 间距同一个节奏；10dp 是历史口径。
            .padding(top = 12.dp)
            // 四个 chip 原来塞在一个固定 Row 里，360dp 窄屏会把最后两个裁掉一半；
            // 横滑之后 chip 再多也不挤，间距与选中态照旧。
            .horizontalScroll(rememberScrollState())
            // 四个 chip 是同一组互斥选项，不标成一组的话 TalkBack 会逐个念成独立可选元素，
            // 听不出「这是筛选、当前选中第几个」。
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEach { (filter, label) ->
            val active = filter == selected
            Text(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) colorScheme.primary else colorScheme.surfaceContainer)
                    .selectable(
                        selected = active,
                        role = Role.Tab,
                        onClick = { onFilter(filter) },
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                text = stringResource(label),
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                color = if (active) colorScheme.onPrimary else colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * 查重进度：**只有一句计数**，永远一行。
 *
 * 这里**不画进度条**——顶栏底边那条已经在表达「正在忙」，同一屏两条进度条纯属重复。
 *
 * 也**没有「继续查」按钮和它下面那句说明**：这两块都只在 `remaining > 0` 时出现，
 * 于是「查完」那一刻这一行会从两行缩成一行，下面的挑包卡和模块卡整体上移一截——
 * 进页面时先看到按钮、等查询结束按钮又消失，观感像内容跳了一下。
 * 现在这一块的高度是恒定的：同一句话、同一个字号，`remaining` 再大也不多长一行。
 * 没查完这件事仍然看得出来——计数本身就是「已查询 200 / 350」。
 *
 * [fromCache] 为真时那句话后面挂一句「缓存」：这批读数一条请求都没发（见
 * [ModuleUploadCheckCache]），得让人知道它最多是半小时前的，而不是刚才那一刻的。
 */
@Composable
private fun CheckProgressMiuix(
    checked: Int,
    total: Int,
    checking: Boolean,
    fromCache: Boolean,
) {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            // 顶部节奏统一 12dp：与筛选行、挑包入口卡同一口径。
            .padding(top = 12.dp),
        text = when {
            checking -> stringResource(R.string.module_upload_checking)
            fromCache -> stringResource(
                R.string.module_upload_progress_checked_cached,
                checked,
                total,
            )

            else -> stringResource(R.string.module_upload_progress_checked, checked, total)
        },
        fontSize = 12.sp,
        color = colorScheme.onSurfaceVariantSummary,
    )
}

/**
 * 空列表：图标 + 主文案 + 副说明。
 *
 * 分两种：真的一条都没有（去刷新 / 直接从上面挑包），和筛出来是空的（换个条件）。
 * 后者额外给一枚「清掉筛选」的动作 —— 只说「没有匹配」而不给出口，用户只能自己猜怎么退回来。
 */
@Composable
private fun EmptyStateMiuix(
    /** true 表示列表本身有数据，只是当前筛选/搜索把它们全滤掉了。 */
    filtered: Boolean,
    /** true 表示上一次扫描已安装模块失败——不能把它说成「一个都没装」。 */
    failed: Boolean,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = if (filtered) MiuixIcons.Filter else MiuixIcons.Layers,
            // 下面两行字已经把话说完了，图标不再重复念一遍。
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = colorScheme.onSurfaceVariantSummary.copy(alpha = 0.35f),
        )
        Text(
            modifier = Modifier.padding(top = 10.dp),
            text = stringResource(
                when {
                    filtered -> R.string.module_upload_empty_filtered
                    failed -> R.string.module_upload_scan_failed
                    else -> R.string.module_upload_empty
                },
            ),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            modifier = Modifier.padding(top = 4.dp),
            text = stringResource(
                when {
                    filtered -> R.string.module_upload_empty_filtered_summary
                    failed -> R.string.module_upload_scan_failed_summary
                    else -> R.string.module_upload_empty_summary
                },
            ),
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        if (filtered) {
            TextButton(
                modifier = Modifier.padding(top = 8.dp),
                text = stringResource(R.string.module_upload_empty_filtered_action),
                onClick = onReset,
            )
        }
    }
}

@Composable
private fun UploadRowCardMiuix(
    row: UploadRow,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPick: () -> Unit,
    onUseArchived: () -> Unit,
    /** 这个模块投过、而本地装着更新的版本：右侧显示「上传更新」。 */
    uploadUpdate: Boolean = false,
    /** 有任意一行正在挑包（全局闸门生效中）。 */
    picking: Boolean = false,
    /** 正在挑的就是这一行。 */
    pickingThis: Boolean = false,
    /** 与上一块的间距：组内第一张紧跟组标题用 8dp，其余卡片之间 12dp。 */
    topGap: Dp = 12.dp,
) {
    // 归属牌 / 功能标签 / 展开区统一缩进到名字左缘（图标块 36dp + 间距 12dp）：
    // 一眼能看出这几段说的都是同一个模块，而不是各说各的。
    val nameIndent = 48.dp
    // 元信息用 `·` 分隔；作者为空时只留版本号，不要留一个孤零零的分隔符。
    val meta = if (row.author.isBlank()) row.version else "${row.version} · ${row.author}"
    val hasTags = !row.enabled || row.remove || row.metamodule
    Card(
        modifier = Modifier
            .padding(top = topGap)
            .fillMaxWidth()
            // 展开区不是瞬移出来的：高度变化交给 animateContentSize 缓过去，
            // 与上游仓库页同一套节奏（tween 300）。
            .animateContentSize(animationSpec = tween(durationMillis = 300)),
        onClick = onToggle,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 第一行：图标块 + 名字/元信息 + 状态 pill。状态与名字同一行才对得上
            // 「这个名字是什么状态」，而不是浮在另一行末尾。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RowIconTileMiuix()
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        modifier = Modifier.padding(top = 2.dp),
                        text = meta,
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 右侧两个标签：「上传更新」（你手里这版比投过的新）与收录状态
                // （索引里有没有这一版）回答的是两件事，可以同时为真，所以并排而不是互相顶掉。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (uploadUpdate) {
                        TagMiuix(
                            stringResource(R.string.module_upload_tag_upload_update),
                            background = colorScheme.primaryContainer,
                            content = colorScheme.onPrimaryContainer,
                        )
                    }
                    StatusPill(row.status, row.checking)
                }
            }
            // 归属单独一行：它回答「这一版我还能不能发」，比版本号重要，
            // 不该再挤在元信息行末尾。
            // UNKNOWN 是「没问出来」（没核实 UID、服务端没开归属判定、网络不通），
            // 画个牌子等于把一次没问到的事变成用户要自己判断的事，所以不画。
            if (row.eligibility != UpdateEligibility.UNKNOWN) {
                Row(modifier = Modifier.padding(start = nameIndent)) {
                    when (row.eligibility) {
                        UpdateEligibility.MINE -> TagMiuix(
                            stringResource(R.string.module_upload_ownership_mine),
                            background = colorScheme.primaryContainer,
                            content = colorScheme.onPrimaryContainer,
                        )

                        UpdateEligibility.NOT_MINE -> TagMiuix(
                            stringResource(R.string.module_upload_ownership_not_mine),
                            background = colorScheme.errorContainer,
                            content = colorScheme.onErrorContainer,
                        )

                        UpdateEligibility.UNKNOWN -> Unit
                    }
                }
            }
            // 三个功能标签只在至少有一个为真时占位：三无模块不该多出一条空白行。
            if (hasTags) {
                Row(
                    modifier = Modifier.padding(start = nameIndent),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (!row.enabled) TagMiuix(stringResource(R.string.module_upload_tag_disabled))
                    if (row.remove) TagMiuix(stringResource(R.string.module_upload_tag_remove))
                    if (row.metamodule) TagMiuix(stringResource(R.string.module_upload_tag_metamodule))
                }
            }

            if (expanded) {
                // 展开区整体缩进到名字左缘：与头部视觉上是一条线下来的，
                // 而不是另起一块「不属于这张卡」的文字墙。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = nameIndent),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // 这里不再复述收录状态：右侧标签已经把结论说完了，
                    // 展开区只留标签装不下的东西（归属解释、描述、能点的动作）。
                    // 归属那一格单独补一句解释：牌子只写「你的模块 / 别人的模块」，
                    // 展开的人想知道的是「所以这一版我还能不能发」。UNKNOWN 不说话。
                    val ownershipSummary = when (row.eligibility) {
                        UpdateEligibility.MINE -> R.string.module_upload_ownership_mine_summary
                        UpdateEligibility.NOT_MINE -> R.string.module_upload_ownership_not_mine_summary
                        UpdateEligibility.UNKNOWN -> null
                    }
                    if (ownershipSummary != null) {
                        // 多留 4dp 与上一句分段：归属说明是另一件事，不是状态那句话的续写。
                        Text(
                            modifier = Modifier.padding(top = 4.dp),
                            text = stringResource(ownershipSummary),
                            fontSize = 12.sp,
                            color = if (row.eligibility == UpdateEligibility.NOT_MINE) {
                                colorScheme.error
                            } else {
                                colorScheme.onSurfaceVariantSummary
                            },
                        )
                    }
                    if (row.description.isNotBlank()) {
                        Text(
                            modifier = Modifier.padding(top = 4.dp),
                            text = row.description,
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 安装时留存了原始包就直接给一条捷径：绝大多数用户根本不知道自己的 zip 放哪。
                    // 说明是按钮的解释，紧贴在按钮下面，不要隔开。
                    if (row.archived) {
                        TextButton(
                            text = stringResource(R.string.module_upload_use_archived_zip),
                            onClick = onUseArchived,
                            enabled = !picking,
                        )
                        Text(
                            text = stringResource(R.string.module_upload_use_archived_zip_summary),
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    // 挑包要弹系统选择器，回来之前这一行没有任何反馈，用户会以为没点上而反复点。
                    // 正在挑的那行转圈并把按钮禁掉，其余行一起禁用（闸门本来就是全局的）。
                    if (pickingThis) {
                        Row(
                            modifier = Modifier.padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(progress = null, size = 16.dp, strokeWidth = 2.dp)
                            Text(
                                text = stringResource(R.string.module_upload_picking),
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    } else {
                        TextButton(
                            text = stringResource(R.string.module_upload_pick_original_zip),
                            onClick = onPick,
                            enabled = !picking,
                        )
                        Text(
                            text = stringResource(
                                R.string.module_upload_pick_original_zip_summary,
                                row.id,
                            ),
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 行首那枚 36dp 的圆角图标块。
 *
 * 尺寸与圆角跟 Material 侧的 `IconTile` 取同一个值（36dp / 12dp），两套主题的行高
 * 与视觉重量才对得上；Miuix 没有现成的 IconTile，这里用 Box 拼一个。
 */
@Composable
internal fun RowIconTileMiuix() {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MiuixIcons.Layers,
            // 名字就在右边，图标再报一遍是噪音。
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun StatusPill(status: CheckStatus?, checking: Boolean) {
    val (background, content) = when {
        checking -> colorScheme.surfaceContainer to colorScheme.onSurfaceVariantSummary
        status == CheckStatus.RECORDED -> colorScheme.secondaryContainer to colorScheme.onSecondaryContainer
        status == CheckStatus.NEW || status == CheckStatus.VERSION_UPDATE ->
            colorScheme.tertiaryContainer to colorScheme.onTertiaryContainer

        else -> colorScheme.surfaceContainer to colorScheme.onSurfaceVariantSummary
    }
    val label = when {
        checking -> stringResource(R.string.module_upload_checking)
        else -> stringResource(ModuleUploadRules.statusCopy(status).title)
    }
    Text(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        text = label,
        fontSize = 11.sp,
        color = content,
    )
}

@Composable
internal fun TagMiuix(
    text: String,
    /** 底色为 null 时用表面色：多数标签是中性信息，只有归属这类要分对错的才给彩色。 */
    background: Color? = null,
    content: Color? = null,
) {
    Text(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background ?: colorScheme.surfaceContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        text = text,
        fontSize = 10.sp,
        color = content ?: colorScheme.onSurfaceVariantSummary,
    )
}
