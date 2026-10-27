package com.sukisu.ultra.ui.screen.upload

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.component.material.ExpressiveScaffold
import com.sukisu.ultra.ui.component.material.IconTile
import com.sukisu.ultra.ui.component.material.SegmentedColumn
import com.sukisu.ultra.ui.component.material.SegmentedListItem
import com.sukisu.ultra.ui.component.material.TonalCard
import com.sukisu.ultra.ui.component.material.TopBarBackButton
import com.sukisu.ultra.ui.component.material.expressiveTopAppBarColors
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
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val visible = state.visibleRows
    val checked = state.rows.count { it.status != null }

    ExpressiveScaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.module_upload_title)) },
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
                colors = expressiveTopAppBarColors(),
            )
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 16.dp),
            contentPadding = innerPadding,
        ) {
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    label = { Text(stringResource(R.string.module_upload_search_hint)) },
                    singleLine = true,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val labels = listOf(
                        ModuleUploadRules.Filter.ALL to R.string.module_upload_filter_all,
                        ModuleUploadRules.Filter.NOT_RECORDED to R.string.module_upload_filter_not_recorded,
                        ModuleUploadRules.Filter.RECORDED to R.string.module_upload_filter_recorded,
                        ModuleUploadRules.Filter.UNKNOWN to R.string.module_upload_filter_unknown,
                    )
                    labels.forEach { (filter, label) ->
                        FilterChip(
                            selected = state.filter == filter,
                            onClick = { onFilter(filter) },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
            }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.remaining > 0) {
                            TextButton(onClick = onContinueCheck, enabled = !state.checking) {
                                Text(stringResource(R.string.module_upload_continue_check))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (state.checking) {
                            Text(
                                text = stringResource(R.string.module_upload_checking),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (checked > 0) {
                            Text(
                                text = stringResource(
                                    R.string.module_upload_check_progress,
                                    checked,
                                    state.remaining,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SegmentedColumn(
                    modifier = Modifier.padding(top = 4.dp),
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
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(visible, key = { it.id }) { row ->
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

            item { Spacer(Modifier.height(24.dp)) }
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
        Text(
            text = stringResource(R.string.module_upload_mine_entry_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            modifier = Modifier.padding(top = 2.dp),
            text = stringResource(R.string.module_upload_mine_entry_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (status != null) {
            Row(
                modifier = Modifier.padding(top = 6.dp),
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
        // 失败或降级时手上可能还有一份旧名单：留着比清空好，上面那句提示已经说明它没过期。
        if (mine.modules.isNotEmpty()) {
            SegmentedColumn(
                modifier = Modifier.padding(top = 8.dp),
                content = mine.modules.map { module ->
                    {
                        MineRowMaterial(
                            module = module,
                            picking = picking,
                            pickingThis = pickingId == module.id,
                            onPick = { onPick(module) },
                        )
                    }
                },
            )
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
                text = if (module.version.isBlank()) module.id else "${module.id}, ${module.version}",
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
    /** 有任意一行正在挑包（全局闸门生效中）。 */
    picking: Boolean = false,
    /** 正在挑的就是这一行。 */
    pickingThis: Boolean = false,
) {
    TonalCard(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
        onClick = onToggle,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = row.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusPillMaterial(row.status, row.checking)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    modifier = Modifier.weight(1f, fill = false),
                    text = "${row.version}, ${row.author}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 归属只在服务端明确答过（MINE / NOT_MINE）时才显示：UNKNOWN 是「没问出来」，
                // 挂个「不知道」的牌子只会让用户以为要自己判断，而这件事本来就该由服务端说了算。
                OwnershipTagMaterial(row.eligibility)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

            if (expanded) {
                val copy = ModuleUploadRules.statusCopy(row.status)
                Text(
                    modifier = Modifier.padding(top = 2.dp),
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
                if (row.archived) {
                    TextButton(onClick = onUseArchived, enabled = !picking) {
                        Text(stringResource(R.string.module_upload_use_archived_zip))
                    }
                    Text(
                        text = stringResource(R.string.module_upload_use_archived_zip_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    TextButton(onClick = onPick, enabled = !picking) {
                        Text(stringResource(R.string.module_upload_pick_original_zip))
                    }
                    Text(
                        text = stringResource(
                            R.string.module_upload_pick_original_zip_summary,
                            row.id,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
