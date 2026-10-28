package com.sukisu.ultra.ui.screen.upload

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.sukisu.ultra.ui.component.SearchStatus
import com.sukisu.ultra.ui.component.miuix.SearchBarFake
import com.sukisu.ultra.ui.component.miuix.SearchBox
import com.sukisu.ultra.ui.component.miuix.SearchPager
import com.sukisu.ultra.ui.theme.LocalEnableBlur
import com.sukisu.ultra.ui.util.BlurredBar
import com.sukisu.ultra.ui.util.CheckStatus
import com.sukisu.ultra.ui.util.MineModule
import com.sukisu.ultra.ui.util.UpdateEligibility
import com.sukisu.ultra.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
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
import top.yukonga.miuix.kmp.icon.extended.Refresh
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
    onContinueCheck: () -> Unit,
    onFilter: (ModuleUploadRules.Filter) -> Unit,
    onSearchStatusChange: (SearchStatus) -> Unit,
    onToggleRow: (String) -> Unit,
    onPickForRow: (UploadRow) -> Unit,
    onUseArchived: (UploadRow) -> Unit,
    onPickZip: () -> Unit,
    onOpenArchiveSettings: () -> Unit,
    /** 「我的模块」：服务端 `GET /mine` 的结果，与主列表共用同一套状态机。 */
    mine: MineState,
    /** 为「我的模块」的某一行挑包；与主列表共用同一个单行 SAF 启动器与同一道闸门。 */
    onPickForMine: (MineModule) -> Unit,
) {
    val searchStatus = state.searchStatus
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    val visible = state.visibleRows
    val checked = state.rows.count { it.status != null }
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
                        // 刷新放顶栏：列表一滚行内按钮就看不见了，空列表时那一行也只剩它一个。
                        actions = {
                            IconButton(
                                onClick = onRefresh,
                                enabled = !state.refreshing && !state.loading,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Refresh,
                                    contentDescription = stringResource(R.string.module_upload_refresh),
                                    // Miuix 的 IconButton 不会按 enabled 调淡图标，禁用态与可点态长得
                                    // 一模一样，用户会以为没点上而反复点。这里手动换禁用色。
                                    tint = if (state.refreshing || state.loading) {
                                        colorScheme.onSurfaceVariantSummary
                                    } else {
                                        colorScheme.onSurface
                                    },
                                )
                            }
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
                    FilterRow(
                        selected = state.filter,
                        onFilter = onFilter,
                    )
                }

                // 查重进度：原先是「一行字 + 一个按钮」，看不出整体进度。这里换成「条 + 已查/总数」，
                // 正在查时条走不确定态；一次都没查过、也不在查时整块不占位（否则顶部留白像没加载）。
                if (state.checking || checked > 0) {
                    item {
                        CheckProgressMiuix(
                            checked = checked,
                            total = state.rows.size,
                            remaining = state.remaining,
                            checking = state.checking,
                            onContinue = onContinueCheck,
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

                // 「我的模块」紧跟挑包入口：两块是同一件事的两半——手里有 zip 就从上面进，
                // 只是给已收录的模块发新版本就点下面这一行，挑完包带着模块 id 直接进表单。
                item {
                    MineSectionMiuix(
                        mine = mine,
                        picking = state.pickingFor != null,
                        pickingId = state.pickingFor,
                        onPick = onPickForMine,
                    )
                }

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

                if (visible.isEmpty()) {
                    // 空态：图标 + 主文案 + 副说明；筛选筛空时再给一枚「清掉筛选」的动作。
                    // 组标题仍然保留，否则「已装在本机」下面突然空一块说不清是哪一组空了。
                    item {
                        EmptyStateMiuix(
                            filtered = state.rows.isNotEmpty(),
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

/**
 * 「我的模块」：已经收录的模块 + 一键发新版本（与 Material 那份同一份状态、同一道闸门）。
 *
 * 投稿的主路径其实是这一块：用户手里通常只剩新版 zip，早年的包早丢了；从这里挑包会带着
 * 模块 id 回表单，服务端不必再人工核对「这个包是谁的版本」。
 */
@Composable
private fun MineSectionMiuix(
    mine: MineState,
    /** 有任意一行正在挑包（全局闸门生效中）。 */
    picking: Boolean,
    /** 正在挑包的那一行（模块 id）；与主列表共用同一道闸门。 */
    pickingId: String?,
    onPick: (MineModule) -> Unit,
) {
    // 常态提示按状态取，一次只显示一句；message 那类一次性补充说明由外层用 Toast 消化，
    // 不在这里重复。顺序即优先级：先答「能不能用」，再答「为什么是空的」。
    val status = when {
        mine.loading -> R.string.module_upload_mine_loading
        mine.needVerify -> R.string.module_upload_mine_need_verify
        mine.failed -> R.string.module_upload_mine_failed
        // 降级时服务端也回空列表，但语义是「索引读不到」。说成「你没投过」会让人重投一遍。
        mine.degraded -> R.string.module_upload_mine_degraded
        mine.modules.isEmpty() -> R.string.module_upload_mine_empty
        else -> null
    }

    // 这一组也做成「标题 + 一块卡片」：与上下两组同一个语言。组内的提示只占一行小字，
    // 不给每条提示各配一张卡 —— 提示是这一组的注释，不是独立内容。
    Column(modifier = Modifier.fillMaxWidth()) {
        SmallTitle(
            text = stringResource(R.string.module_upload_mine_entry_title),
            modifier = Modifier.padding(top = 20.dp),
            // 纵向内边距收成 0：间距由外部 modifier 和卡片自己给，默认值会把它叠成两倍。
            insideMargin = PaddingValues(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 0.dp),
        )
        Card(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(),
            insideMargin = PaddingValues(0.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp),
                    text = stringResource(R.string.module_upload_mine_entry_summary),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
                if (status != null) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (mine.loading) {
                            CircularProgressIndicator(progress = null, size = 14.dp, strokeWidth = 2.dp)
                        }
                        Text(
                            text = stringResource(status),
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                // 失败或降级时手上可能还有一份旧名单：留着比清空好，上面那句提示已经说明它没过期。
                mine.modules.forEach { module ->
                    MineRowMiuix(
                        module = module,
                        picking = picking,
                        pickingThis = pickingId == module.id,
                        onPick = { onPick(module) },
                    )
                }
                // 组内最后一行与卡片底边之间留一点：否则按钮贴着圆角，看着像被切掉。
                if (mine.modules.isNotEmpty()) Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun MineRowMiuix(
    module: MineModule,
    /** 有任意一行正在挑包：其余行的按钮一起禁掉（闸门本来就是全局的）。 */
    picking: Boolean,
    /** 正在挑的就是这一行。 */
    pickingThis: Boolean,
    onPick: () -> Unit,
) {
    // 整卡不可点：挑包要弹系统选择器，整行都能触发的话误触成本太高。
    // 现在这一行落在「我的模块」那张组卡里，只有右侧按钮可点，判据不变。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = module.name.ifBlank { module.id },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // 索引里 name 可能是空的（老条目），id 一定在；版本号缺了就只留 id。
                text = if (module.version.isBlank()) module.id else "${module.id} · ${module.version}",
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (pickingThis) {
            // 与主列表同一件事：系统选择器回来之前要有反馈，否则用户会以为没点上。
            CircularProgressIndicator(progress = null, size = 16.dp, strokeWidth = 2.dp)
        } else {
            TextButton(
                text = stringResource(R.string.module_upload_mine_update),
                onClick = onPick,
                enabled = !picking,
            )
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
 * 查重进度：一条进度条 + 「已查 N / 共 M」+ 右侧「继续查」。
 *
 * 原先是「一行字 + 一个按钮」，看不出整体走没走完。数字只在条下面出现一次，
 * 不和按钮挤在同一行 —— 那是两个不同的问题（查到哪了 / 要不要接着查）。
 */
@Composable
private fun CheckProgressMiuix(
    checked: Int,
    total: Int,
    remaining: Int,
    checking: Boolean,
    onContinue: () -> Unit,
) {
    // 正在查时交给不确定态（条自己走）；列表还没回来时不做除法。
    val progress = if (checking) {
        null
    } else if (total > 0) {
        checked.toFloat() / total
    } else {
        0f
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 顶部节奏统一 12dp：与筛选行、挑包入口卡同一口径。
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
            Text(
                text = if (checking) {
                    stringResource(R.string.module_upload_checking)
                } else {
                    stringResource(R.string.module_upload_progress_checked, checked, total)
                },
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            if (remaining > 0) {
                // 一次只查 CHECK_CAP 条，剩下的靠「继续查」：说清楚为什么没查完。
                Text(
                    text = stringResource(
                        R.string.module_upload_continue_hint,
                        ModuleUploadRules.CHECK_CAP,
                    ),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (remaining > 0) {
            TextButton(
                text = stringResource(R.string.module_upload_continue_check),
                onClick = onContinue,
                enabled = !checking,
            )
        }
    }
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
                if (filtered) R.string.module_upload_empty_filtered else R.string.module_upload_empty,
            ),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            modifier = Modifier.padding(top = 4.dp),
            text = stringResource(
                if (filtered) {
                    R.string.module_upload_empty_filtered_summary
                } else {
                    R.string.module_upload_empty_summary
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
                StatusPill(row.status, row.checking)
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
                    val copy = ModuleUploadRules.statusCopy(row.status)
                    Text(
                        modifier = Modifier.padding(top = 2.dp),
                        text = stringResource(copy.title),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(copy.summary),
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
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
private fun RowIconTileMiuix() {
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
private fun TagMiuix(
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
