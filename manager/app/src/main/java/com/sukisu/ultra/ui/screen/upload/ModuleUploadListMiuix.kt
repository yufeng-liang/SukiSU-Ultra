package com.sukisu.ultra.ui.screen.upload

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sukisu.ultra.R
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
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
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
    onQueryChange: (String) -> Unit,
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
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    val visible = state.visibleRows
    val checked = state.rows.count { it.status != null }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
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
                                imageVector = Icons.Rounded.Refresh,
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
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
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
                TextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    label = stringResource(R.string.module_upload_search_hint),
                    singleLine = true,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                )
            }

            item {
                FilterRow(
                    selected = state.filter,
                    onFilter = onFilter,
                )
            }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.remaining > 0) {
                            TextButton(
                                text = stringResource(R.string.module_upload_continue_check),
                                onClick = onContinueCheck,
                                enabled = !state.checking,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (state.checking) {
                            Text(
                                text = stringResource(R.string.module_upload_checking),
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        } else if (checked > 0) {
                            Text(
                                text = stringResource(
                                    R.string.module_upload_check_progress,
                                    checked,
                                    state.remaining,
                                ),
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    if (state.remaining > 0) {
                        // 一次只查 200 条；剩下的靠「继续查」，这里说清楚为什么没查完。
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
            }

            item {
                Card(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth(),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.module_upload_pick_zip),
                        summary = stringResource(R.string.module_upload_pick_zip_summary),
                        startAction = {
                            Icon(
                                Icons.Rounded.CloudUpload,
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

            if (visible.isEmpty()) {
                item {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        text = stringResource(
                            if (state.rows.isEmpty()) {
                                R.string.module_upload_empty
                            } else {
                                R.string.module_upload_empty_filtered
                            }
                        ),
                        fontSize = 14.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            items(visible, key = { it.id }) { row ->
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
                )
            }

            item {
                Spacer(Modifier.height(24.dp))
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
    ) {
        Text(
            text = stringResource(R.string.module_upload_mine_entry_title),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = colorScheme.primary,
        )
        Text(
            modifier = Modifier.padding(top = 2.dp),
            text = stringResource(R.string.module_upload_mine_entry_summary),
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
        if (status != null) {
            Row(
                modifier = Modifier.padding(top = 6.dp),
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
    Card(
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = module.name.ifBlank { module.id },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // 索引里 name 可能是空的（老条目），id 一定在；版本号缺了就只留 id。
                    text = if (module.version.isBlank()) module.id else "${module.id}, ${module.version}",
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
            .padding(top = 10.dp)
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
) {
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
        onClick = onToggle,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = row.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusPill(row.status, row.checking)
            }
            Text(
                text = "${row.version}, ${row.author}",
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!row.enabled) TagMiuix(stringResource(R.string.module_upload_tag_disabled))
                if (row.remove) TagMiuix(stringResource(R.string.module_upload_tag_remove))
                if (row.metamodule) TagMiuix(stringResource(R.string.module_upload_tag_metamodule))
                // UNKNOWN 是「没问出来」（没核实 UID、服务端没开归属判定、网络不通），
                // 画个牌子等于把一次没问到的事变成用户要自己判断的事，所以不画。
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

            if (expanded) {
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
                    Text(
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
                        text = row.description,
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 安装时留存了原始包就直接给一条捷径：绝大多数用户根本不知道自己的 zip 放哪。
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
