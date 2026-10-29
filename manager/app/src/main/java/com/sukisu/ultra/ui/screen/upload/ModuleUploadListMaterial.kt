package com.sukisu.ultra.ui.screen.upload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.component.SearchStatus
import com.sukisu.ultra.ui.component.material.ExpressiveScaffold
import com.sukisu.ultra.ui.component.material.IconTile
import com.sukisu.ultra.ui.component.material.SearchAppBar
import com.sukisu.ultra.ui.component.material.SegmentedColumn
import com.sukisu.ultra.ui.component.material.SegmentedListItem
import com.sukisu.ultra.ui.component.material.SnackBarHost
import com.sukisu.ultra.ui.component.material.TonalCard
import com.sukisu.ultra.ui.component.material.TopBarBackButton
import com.sukisu.ultra.ui.component.statustag.StatusTag
import com.sukisu.ultra.ui.util.CheckStatus
import com.sukisu.ultra.ui.util.UpdateEligibility

/**
 * Material 风格的上传列表，与 [ModuleUploadListMiuix] 同一份状态、同一组规则。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleUploadListMaterial(
    state: ModuleUploadListState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onFilter: (ModuleUploadRules.Filter) -> Unit,
    onSearchTextChange: (String) -> Unit,
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
    // 与模块页/仓库页同一套表达：标题随列表上滑收起，不再是钉住不动的小标题。
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val visible = state.visibleRows
    val checked = state.rows.count { it.status != null }
    // 每个模块投过的最新版本号；「上传更新」标签的判据之一（另一个是这一行的版本号）。
    val uploadedMax = remember(mine.modules) { ModuleUploadRules.uploadedMaxVersions(mine.modules) }

    ExpressiveScaffold(
        snackbarHost = { SnackBarHost(hostState = snackbarHostState) },
        topBar = {
            SearchAppBar(
                snackbarHostState = snackbarHostState,
                title = { Text(stringResource(R.string.module_upload_title)) },
                searchText = state.searchStatus.searchText,
                onSearchTextChange = onSearchTextChange,
                // 清除即清空搜索词：VM 的 updateSearchText 会把 searchStatus 一并同步。
                onClearClick = { onSearchTextChange("") },
                navigationIcon = { TopBarBackButton(onClick = onBack) },
                // 顶栏只留「解释」「记录」「更多」三件事：它们都是点一下就走。
                // 刷新是「等一下再看」，已经挪到列表下拉（见 content 里的 PullToRefreshBox）。
                actions = {
                    // 「为什么只能传原始 zip」的说明入口。重建包那条路关掉之后，
                    // 「已经装过的模块」上就没有上传入口了，用户会来找解释——就在这里。
                    IconButton(onClick = onOpenInfo) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.module_upload_info_open),
                        )
                    }
                    // 上传记录单独一个入口：它回答的是「我以前投过什么」，
                    // 与「这一版收录了没有」是两件事，塞进列表里只会把主列表顶长。
                    IconButton(onClick = onOpenHistory) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = stringResource(R.string.module_upload_history_open),
                        )
                    }
                    // 刷新不再占顶栏位置：顶栏按钮要「点一下就走」，而刷新是「等一下再看」，
                    // 放在列表上下拉更符合它（与模块页/仓库页同一个手势）。
                    // 留存开关与清理入口都在这个菜单背后：两件事都只跟「占空间」有关，
                    // 顶栏也不该为此多摆一个开关。
                    // 载入完成前先禁掉：面板里的开关初值来自 settings，而列表状态里的
                    // keepZip 默认是 true —— 这一小段窗口里打开面板会显示一个用户其实
                    // 已经关掉的开关，点一下反而把设置写成 true。
                    IconButton(
                        onClick = onOpenArchiveSettings,
                        enabled = !state.loading && !state.refreshing,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.module_upload_more),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                // 顶栏底边的进度条。槽位常驻 3dp、只在忙时往里画条：
                // 高度恒定，进度条出现/消失不会让下面的列表跟着上下跳（这一屏
                // 的查询进度行已经为「高度跳动」踩过一次，同一套规矩）。
                // 刷新中不画：那一刻下拉指示器正悬在列表顶上转，两条进度说同一件事，
                // 一条就够 —— 这条留给它管不到的「首次载入」和「查重」。
                bottomProgress = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                    ) {
                        if (state.busy && !state.refreshing) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxSize())
                        }
                    }
                },
                // 展开搜索时直接看过滤结果，与上游 ModuleMaterial.kt:463 的 searchContent
                // 同一用法：主列表仍留在 Scaffold content，收起后回到原位。
                searchContent = { bottomPadding, _ ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = bottomPadding + 24.dp,
                        ),
                    ) {
                        if (visible.isEmpty()) {
                            item {
                                EmptyStateMaterial(
                                    filtered = state.rows.isNotEmpty(),
                                    onClear = {
                                        onFilter(ModuleUploadRules.Filter.ALL)
                                        onSearchTextChange("")
                                    },
                                )
                            }
                        } else {
                            itemsIndexed(visible, key = { _, row -> row.id }) { _, row ->
                                UploadRowCardMaterial(
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
                                    picking = state.pickingFor != null,
                                    pickingThis = state.pickingFor == row.id,
                                )
                            }
                        }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        // 下拉刷新：与模块页/仓库页同一枚指示器、同一个手势。
        // 刷新这颗键原来占着顶栏，要「先找到、再按下」；而这一屏的操作重心就在列表上，
        // 往下拉一把是最短的路。刷的语义没变（重新扫一遍已装模块 + 强制重查）。
        val pullToRefreshState = rememberPullToRefreshState()
        val haptic = LocalHapticFeedback.current
        PullToRefreshBox(
            // padding(innerPadding) 是这几页的统一写法：指示器要从顶栏下沿钻出来，
            // 不加这层 padding 它会被顶栏盖住，只剩一滴看不见的水珠。
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            // 只跟刷新走，不跟 busy：busy 里还含「查重」和首次载入，
            // 那两件事由顶栏底边那条细进度条负责，不该让下拉指示器一直转着。
            isRefreshing = state.refreshing,
            onRefresh = {
                // 轻振一下：下拉本身没有「按下」的反馈，不补这一下就分不清
                // 是「触发了」还是「没拉够」。
                haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onRefresh()
            },
            state = pullToRefreshState,
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    modifier = Modifier.align(Alignment.TopCenter),
                    isRefreshing = state.refreshing,
                    state = pullToRefreshState,
                )
            },
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                // 上游列表规范：水平 16dp 走 contentPadding（内容可以滚出屏幕边缘），
                // item 之间的纵向间距统一由 spacedBy(12.dp) 负责，不再靠各 item 手工垫。
                // 顶部不再叠 innerPadding：它已经被外面那层 PullToRefreshBox 吃掉了，
                // 这里再加一次会平白空出整条顶栏的高度。
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 24.dp,
                ),
            ) {
                item {
                    // 四个筛选项在 360dp 窄屏上放不下，原来的定宽 Row 会把最后两个裁掉一半。
                    // 换横向滚动；外层列表已经留了 16dp，这里不再叠 contentPadding，
                    // 否则筛选条和上下内容的左缘对不齐。
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val labels = listOf(
                            ModuleUploadRules.Filter.ALL to R.string.module_upload_filter_all,
                            ModuleUploadRules.Filter.NOT_RECORDED to R.string.module_upload_filter_not_recorded,
                            ModuleUploadRules.Filter.RECORDED to R.string.module_upload_filter_recorded,
                            ModuleUploadRules.Filter.UNKNOWN to R.string.module_upload_filter_unknown,
                        )
                        items(labels) { (filter, label) ->
                            FilterChip(
                                selected = state.filter == filter,
                                onClick = { onFilter(filter) },
                                label = { Text(stringResource(label)) },
                            )
                        }
                    }
                }

                // 查重进度：一行计数（见 CheckProgressMaterial）。进度条交给顶栏底边那条，
                // 这里不重复画第二条。一次都没查过、也不在查（checked == 0 && !checking）
                // 时整块不占位，否则列表顶上会平白多出一行空白。
                if (checked > 0 || state.checking) {
                    item {
                        CheckProgressMaterial(
                            checked = checked,
                            total = state.rows.size,
                            checking = state.checking,
                            fromCache = state.fromCache,
                        )
                    }
                }

                item {
                    SegmentedColumn(
                        content = listOf {
                            SegmentedListItem(
                                onClick = onPickZip,
                                headlineContent = { Text(stringResource(R.string.module_upload_pick_zip)) },
                                supportingContent = { Text(stringResource(R.string.module_upload_pick_zip_summary)) },
                                leadingContent = {
                                    IconTile(
                                        Icons.Outlined.CloudUpload,
                                        stringResource(R.string.module_upload_pick_zip),
                                    )
                                },
                                trailingContent = {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                                },
                            )
                        },
                    )
                }

                // 「上传记录」不再占这一页：它挪到了顶栏图标背后（见 onOpenHistory）。
                // 这一页只回答「已装的这些收录了没有」，记录是另一件事、另一个页面。

                // 第三块「已装在本机」也补上标题：三块并列成同一级的分组，
                // 而不是「两块有标题 + 一堆平铺卡片」。
                item {
                    SectionHeaderMaterial(
                        modifier = Modifier.padding(top = 20.dp),
                        text = stringResource(R.string.module_upload_section_installed),
                    )
                }

                if (visible.isEmpty()) {
                    item {
                        EmptyStateMaterial(
                            filtered = state.rows.isNotEmpty(),
                            // 只组合既有回调：筛选与搜索词一起复位，不为这个按钮新增参数。
                            onClear = {
                                onFilter(ModuleUploadRules.Filter.ALL)
                                onSearchTextChange("")
                            },
                        )
                    }
                } else {
                    itemsIndexed(visible, key = { _, row -> row.id }) { _, row ->
                        UploadRowCardMaterial(
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
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadRowCardMaterial(
    row: UploadRow,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPick: () -> Unit,
    onUseArchived: () -> Unit,
    /** 这个模块投过、而本地装着更新的版本：右侧显示「上传更新」。 */
    uploadUpdate: Boolean = false,
    modifier: Modifier = Modifier,
    /** 有任意一行正在挑包（全局闸门生效中）。 */
    picking: Boolean = false,
    /** 正在挑的就是这一行。 */
    pickingThis: Boolean = false,
) {
    // 名字左缘：图标块 36dp + 12dp 间距。归属标签、标签行与展开区都缩进到这条竖线上，
    // 一眼能看出它们说的是同一个模块，而不是三块互不相干的内容。
    val indent = 48.dp
    TonalCard(
        // 展开/收起随内容高度缓动，避免状态牌下方的说明区一帧弹出（对齐上游仓库页同款）。
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 300)),
        onClick = onToggle,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 第一行：图标 + 名字 / 元信息 + 状态牌。状态牌与名字同高，
            // 展开与否都不会把它挤到别处去。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconTile(Icons.Rounded.Extension, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 版本与作者之间用「·」：逗号会让「3.1.0, 作者」读成半句话；
                    // 作者为空时只留版本，不出现孤零零的分隔符。
                    val meta = listOf(row.version, row.author)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // 右侧标签：「上传更新」与收录状态是两个问题，但都挂在同一处——
                // 前者是「你手里这一版比投过的新」，后者是「索引里有没有这一版」。
                // 两者可以同时为真（投过旧版、这一版没收录），所以并排显示而不是互相顶掉。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (uploadUpdate) {
                        StatusTag(
                            label = stringResource(R.string.module_upload_tag_upload_update),
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                        )
                    }
                    StatusPillMaterial(row.status, row.checking)
                }
            }
            // 归属只在服务端明确答过（MINE / NOT_MINE）时才显示：UNKNOWN 是「没问出来」，
            // 挂个「不知道」的牌子只会让用户以为要自己判断，而这件事本来就该由服务端说了算。
            // 它单独占一行：收录状态回答「要不要传」，归属回答「传了算不算你的」，
            // 两件事挤在同一行会互相暗示（「已收录 + 蓝色」看起来就像「你能覆盖它」）。
            if (row.eligibility != UpdateEligibility.UNKNOWN) {
                Row(modifier = Modifier.padding(start = indent)) {
                    OwnershipTagMaterial(row.eligibility)
                }
            }
            // 三个标签只在至少有一个为真时才占位，否则会白空出一行。
            if (!row.enabled || row.remove || row.metamodule) {
                Row(
                    modifier = Modifier.padding(start = indent),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (!row.enabled) {
                        StatusTag(
                            label = stringResource(R.string.module_upload_tag_disabled),
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                    }
                    if (row.remove) {
                        StatusTag(
                            label = stringResource(R.string.module_upload_tag_remove),
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            backgroundColor = MaterialTheme.colorScheme.errorContainer,
                        )
                    }
                    if (row.metamodule) {
                        StatusTag(
                            label = stringResource(R.string.module_upload_tag_metamodule),
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            backgroundColor = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (expanded) {
                // 展开区整体缩进到名字左缘：它是这一行的补充说明，不是卡片的第二栏。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = indent, top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // 这里不再复述收录状态（「未收录 / 索引里没有这个模块，可以上传」那两句）：
                    // 右侧的标签已经把结论说完了，再说一遍只是把用户往下推一屏。
                    // 展开区只留标签装不下的东西：归属解释、模块描述、以及真正能点的动作。
                    // 归属那一格单独补一句解释：牌子只写「你的模块 / 别人的模块」，
                    // 展开的人想知道的是「所以这一版我还能不能发」。UNKNOWN 不说话。
                    val ownershipSummary = when (row.eligibility) {
                        UpdateEligibility.MINE -> R.string.module_upload_ownership_mine_summary
                        UpdateEligibility.NOT_MINE -> R.string.module_upload_ownership_not_mine_summary
                        UpdateEligibility.UNKNOWN -> null
                    }
                    if (ownershipSummary != null) {
                        Text(
                            modifier = Modifier.padding(top = 2.dp),
                            text = stringResource(ownershipSummary),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (row.eligibility == UpdateEligibility.NOT_MINE) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (row.description.isNotBlank()) {
                        Text(
                            text = row.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 安装时留存了原始包就直接给一条捷径：绝大多数用户根本不知道自己的 zip 放哪。
                    // 按钮与它的说明是一对——说明解释按钮，中间不再隔 8dp 以上的空隙。
                    if (row.archived) {
                        RowActionMaterial(
                            label = stringResource(R.string.module_upload_use_archived_zip),
                            summary = stringResource(R.string.module_upload_use_archived_zip_summary),
                            enabled = !picking,
                            onClick = onUseArchived,
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
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                text = stringResource(R.string.module_upload_picking),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        RowActionMaterial(
                            label = stringResource(R.string.module_upload_pick_original_zip),
                            summary = stringResource(
                                R.string.module_upload_pick_original_zip_summary,
                                row.id,
                            ),
                            enabled = !picking,
                            onClick = onPick,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一个文字按钮 + 它自己的说明，成对紧贴。
 *
 * 说明是对按钮的解释，分开摆会让人先把两段都读完才明白哪个是按钮；按钮的左内边距压到 0，
 * 标签文字与下面的说明才对在同一条竖线上。
 */
@Composable
internal fun RowActionMaterial(
    label: String,
    summary: String,
    enabled: Boolean,
    onClick: () -> Unit,
    /** 调用方需要额外拉开与上一段的间距时用（列表页靠父 Column 的 spacedBy，不传）。 */
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(
            onClick = onClick,
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
        ) {
            Text(label)
        }
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 分组标题。与 `SegmentedColumn` 里那副标题逐像素一致（同样的字重、主色、左 16dp 与下 8dp），
 * 两个分组的标题才落在同一条竖线上，也就是同一个间距节奏。
 *
 * 单独抽出来是因为「已装在本机」这一组仍用可展开卡片，套不进 `SegmentedColumn`。
 */
@Composable
private fun SectionHeaderMaterial(text: String, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier.padding(start = 16.dp, bottom = 8.dp),
        text = text,
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * 空态：图标 + 主文案 + 副说明，居中。
 *
 * 分两支：真的没有模块，与「筛选 / 搜索之后没有」。后者是用户自己缩窄的视野，
 * 所以多给一个一键回到全量的出口——只组合既有回调，不新增参数。
 */
@Composable
private fun EmptyStateMaterial(filtered: Boolean, onClear: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            modifier = Modifier.size(34.dp),
            // 图标只是意象，压到 0.35 不透明度：空态是一个结论，不该跟主文案抢注意力。
            imageVector = if (filtered) Icons.Rounded.SearchOff else Icons.Rounded.Extension,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        )
        Text(
            text = stringResource(
                if (filtered) {
                    R.string.module_upload_empty_filtered
                } else {
                    R.string.module_upload_empty
                }
            ),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(
                if (filtered) {
                    R.string.module_upload_empty_filtered_summary
                } else {
                    R.string.module_upload_empty_summary
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (filtered) {
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.module_upload_empty_filtered_action))
            }
        }
    }
}

/**
 * 查重进度：**只有一句计数**，永远一行。
 *
 * 这里**不再画进度条**——顶栏底边那条（`bottomProgress`）已经在表达「正在忙」，
 * 同一屏出现两条进度条纯属重复。
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
private fun CheckProgressMaterial(
    checked: Int,
    total: Int,
    checking: Boolean,
    fromCache: Boolean,
) {
    Text(
        modifier = Modifier
            .fillMaxWidth()
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
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StatusPillMaterial(status: CheckStatus?, checking: Boolean) {
    val (background, content) = when {
        checking -> MaterialTheme.colorScheme.surfaceContainerHighest to
            MaterialTheme.colorScheme.onSurfaceVariant

        status == CheckStatus.RECORDED -> MaterialTheme.colorScheme.secondaryContainer to
            MaterialTheme.colorScheme.onSecondaryContainer

        status == CheckStatus.NEW || status == CheckStatus.VERSION_UPDATE ->
            MaterialTheme.colorScheme.tertiaryContainer to
                MaterialTheme.colorScheme.onTertiaryContainer

        else -> MaterialTheme.colorScheme.surfaceContainerHighest to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when {
        checking -> stringResource(R.string.module_upload_checking)
        else -> stringResource(ModuleUploadRules.statusCopy(status).title)
    }
    StatusTag(
        label = label,
        contentColor = content,
        backgroundColor = background,
    )
}

/**
 * 归属标签：这一版归不归当前这个酷安账号。
 *
 * 与 [StatusPillMaterial] 是两件事，必须分开显示：收录状态回答「要不要传」，
 * 归属回答「传了算不算你的」。把两者挤进同一个牌子，会出现「已收录 + 蓝色」
 * 这种反过来暗示「你能覆盖它」的配色。
 *
 * [UpdateEligibility.UNKNOWN] 什么都不画：那是「没问出来」（没核实 UID、服务端没开归属
 * 判定、网络不通），画一个「未知」的牌子等于把一次没问到的事变成用户要自己判断的事。
 */
@Composable
private fun OwnershipTagMaterial(eligibility: UpdateEligibility) {
    when (eligibility) {
        UpdateEligibility.MINE -> StatusTag(
            label = stringResource(R.string.module_upload_ownership_mine),
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            backgroundColor = MaterialTheme.colorScheme.primaryContainer,
        )

        UpdateEligibility.NOT_MINE -> StatusTag(
            label = stringResource(R.string.module_upload_ownership_not_mine),
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            backgroundColor = MaterialTheme.colorScheme.errorContainer,
        )

        UpdateEligibility.UNKNOWN -> Unit
    }
}
