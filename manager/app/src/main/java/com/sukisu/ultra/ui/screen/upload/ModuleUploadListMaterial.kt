package com.sukisu.ultra.ui.screen.upload

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudUpload
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
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import com.sukisu.ultra.ui.util.MineModule
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
    onContinueCheck: () -> Unit,
    onFilter: (ModuleUploadRules.Filter) -> Unit,
    onSearchTextChange: (String) -> Unit,
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
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val visible = state.visibleRows
    val checked = state.rows.count { it.status != null }

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
                // 刷新放顶栏而不是列表行里：列表一滚，行内按钮就看不见了；
                // 而且在模块为空时那一行只剩一个孤零零的「刷新」。
                actions = {
                    IconButton(
                        onClick = onRefresh,
                        enabled = !state.refreshing && !state.loading,
                    ) {
                        if (state.refreshing || state.loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.module_upload_refresh),
                            )
                        }
                    }
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
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            // 上游列表规范：水平 16dp 走 contentPadding（内容可以滚出屏幕边缘），
            // item 之间的纵向间距统一由 spacedBy(12.dp) 负责，不再靠各 item 手工垫。
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
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

            // 查重进度：原来只有一行小字，看不出「还差多少」。改成进度条 + 计数并排。
            // 一次都没查过、也不在查（checked == 0 && !checking）时整块不占位，
            // 否则列表顶上会永远挂着一条空白的灰条。
            if (checked > 0 || state.checking) {
                item {
                    CheckProgressMaterial(
                        checked = checked,
                        total = state.rows.size,
                        checking = state.checking,
                        remaining = state.remaining,
                        onContinue = onContinueCheck,
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

            // 「我的模块」紧跟挑包入口：两块是同一件事的两半——手里有 zip 就从上面进，
            // 只是给已收录的模块发新版本就点下面这一行，挑完包带着模块 id 直接进表单。
            item {
                MineSectionMaterial(
                    mine = mine,
                    picking = state.pickingFor != null,
                    pickingId = state.pickingFor,
                    onPick = onPickForMine,
                )
            }

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

/**
 * 「我的模块」：已经收录的模块 + 一键发新版本。
 *
 * 投稿的主路径其实是这一块：用户手里通常只剩新版 zip，早年的包早丢了；从这里挑包会带着
 * 模块 id 回表单，服务端不必再人工核对「这个包是谁的版本」。
 */
@Composable
private fun MineSectionMaterial(
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
    ) {
        // 标题交给 SegmentedColumn 自己画（titleSmallEmphasized + 主色 + 左 16dp + 下 8dp），
        // 与下一块「已装在本机」的标题共用同一副字号与缩进，三块才像同一级的分组。
        // 失败或降级时手上可能还有一份旧名单：留着比清空好，下面那句提示已经说明它没过期。
        SegmentedColumn(
            title = stringResource(R.string.module_upload_mine_entry_title),
            content = {
                if (mine.modules.isEmpty()) {
                    // 一条内容都没有时 SegmentedColumn 连标题一起不画，可「正在载入 / 你没投过」
                    // 恰恰要靠标题说明这是哪一组。塞一条 visible = false 的行占位：
                    // 它会被动画收成 0 高度，看不见也点不到。
                    item(key = "mine-empty", visible = false) {
                        Spacer(Modifier.height(0.dp))
                    }
                }
                mine.modules.forEach { module ->
                    item(key = module.id) {
                        MineRowMaterial(
                            module = module,
                            picking = picking,
                            pickingThis = pickingId == module.id,
                            onPick = { onPick(module) },
                        )
                    }
                }
            },
        )
        // 组内提示压成组下方的一行小字：每条状态各占一张卡片会把「我的模块」顶成一张表格。
        Text(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            text = stringResource(R.string.module_upload_mine_entry_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (status != null) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (mine.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                }
                Text(
                    text = stringResource(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MineRowMaterial(
    module: MineModule,
    /** 有任意一行正在挑包：其余行的按钮一起禁掉（闸门本来就是全局的）。 */
    picking: Boolean,
    /** 正在挑的就是这一行。 */
    pickingThis: Boolean,
    onPick: () -> Unit,
) {
    SegmentedListItem(
        headlineContent = {
            Text(
                text = module.name.ifBlank { module.id },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                // 索引里 name 可能是空的（老条目），id 一定在；版本号缺了就只留 id。
                // 分隔符与主列表用同一个「·」，两处元信息的读法一致。
                text = if (module.version.isBlank()) {
                    module.id
                } else {
                    "${module.id} · ${module.version}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            if (pickingThis) {
                // 与主列表同一件事：系统选择器回来之前要有反馈，否则用户会以为没点上。
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onPick, enabled = !picking) {
                    Text(stringResource(R.string.module_upload_mine_update))
                }
            }
        },
    )
}

@Composable
private fun UploadRowCardMaterial(
    row: UploadRow,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPick: () -> Unit,
    onUseArchived: () -> Unit,
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
                StatusPillMaterial(row.status, row.checking)
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
                val copy = ModuleUploadRules.statusCopy(row.status)
                // 展开区整体缩进到名字左缘：它是这一行的补充说明，不是卡片的第二栏。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = indent, top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // 结论在前、解释在后：标题加 Medium 字重，与下面那一堆 bodySmall 拉开重量差。
                    Text(
                        text = stringResource(copy.title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(copy.summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
private fun RowActionMaterial(
    label: String,
    summary: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
 * 查重进度：进度条 + 一句计数，「继续查」与进度条同一行右对齐。
 *
 * 正在查（[checking]）时只说「在查」，查完才报「查了多少」——同一时刻只讲一件事，
 * 不出现两行内容一样的提示。总数还不知道时给不确定进度条，比一个停在 0% 的条更诚实。
 */
@Composable
private fun CheckProgressMaterial(
    checked: Int,
    total: Int,
    checking: Boolean,
    remaining: Int,
    onContinue: () -> Unit,
) {
    val fraction = if (total > 0) checked.toFloat() / total else 0f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (checking) {
                        stringResource(R.string.module_upload_checking)
                    } else {
                        stringResource(R.string.module_upload_progress_checked, checked, total)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (checking && total == 0) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                }
            }
            if (remaining > 0) {
                TextButton(onClick = onContinue, enabled = !checking) {
                    Text(stringResource(R.string.module_upload_continue_check))
                }
            }
        }
        if (remaining > 0) {
            // 一次只查 200 条；剩下的靠「继续查」，这里说清楚为什么没查完。
            Text(
                modifier = Modifier.padding(top = 2.dp),
                text = stringResource(
                    R.string.module_upload_continue_hint,
                    ModuleUploadRules.CHECK_CAP,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
