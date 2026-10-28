package com.sukisu.ultra.ui.screen.upload

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.component.material.ExpressiveScaffold
import com.sukisu.ultra.ui.component.material.IconTile
import com.sukisu.ultra.ui.component.material.TonalCard
import com.sukisu.ultra.ui.component.material.TopBarBackButton
import com.sukisu.ultra.ui.component.material.expressiveTopAppBarColors
import com.sukisu.ultra.ui.component.statustag.StatusTag
import com.sukisu.ultra.ui.util.MineModule
import com.sukisu.ultra.ui.util.formatEpochTime

/**
 * 上传记录（Material）：按模块汇总「我投过什么」。
 *
 * 这一页取代了原先挤在列表页里的「我的模块」那一栏。理由不是审美：那一栏把两种性质
 * 完全不同的东西（已装模块的收录状态 / 我投过的历史）塞进同一屏，两边的长度互相牵制——
 * 投得多的人，主列表永远被顶到第二屏去。
 *
 * 数据只有 `GET /mine` 这一个来源，一次请求带回这个账号投过的每一份（id + 版本 + sha + 时间）。
 * 同一模块投过几版就有几行，所以进页面先按 id 归并（[ModuleUploadRules.groupMine]）：
 * 列表上先看「有哪些模块、各投了几版」，点开再看每一版。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleUploadHistoryMaterial(
    /** 已装模块的当前状态：只用来判断「本地这一版比投过的新」。 */
    state: ModuleUploadListState,
    mine: MineState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    /** 给某个模块挑一枚新版本的 zip；与主列表共用同一个 SAF 启动器与同一道闸门。 */
    onPickForMine: (MineModule) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val groups = remember(mine.modules) { ModuleUploadRules.groupMine(mine.modules) }
    // 已装模块按 id 索引：分组里判断「有没有更新可传」只查这一张表，不再扫一遍 rows。
    val installed = remember(state.rows) { state.rows.associateBy { it.id } }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }

    // 提示条一次只说一件事，顺序即优先级：先答「能不能用」，再答「为什么是空的」。
    // 刷新中且手里已经有名单时不报「正在加载」：列表还在，顶栏那颗转着的刷新键
    // 已经把「在刷」说清楚了，再来一行文字只会把上面的内容往下推。
    val notice: String? = when {
        // 服务端/网络给了具体说法时优先说它：有旧名单又刷新失败，只说「请重试」
        // 会让人以为名单本来就是空的。
        mine.message != null -> mine.message
        mine.loading && groups.isEmpty() -> stringResource(R.string.module_upload_mine_loading)
        mine.needVerify -> stringResource(R.string.module_upload_mine_need_verify)
        mine.failed -> stringResource(R.string.module_upload_mine_failed)
        mine.degraded -> stringResource(R.string.module_upload_mine_degraded)
        else -> null
    }
    // 空态与提示条互斥：一句「还没投过」前面不该再压一条错误说明。
    val empty = groups.isEmpty() && !mine.loading && notice == null

    ExpressiveScaffold(
        topBar = {
            // 与设置页/关于页同一套：大标题随列表上滑收到小标题。上一版这里用的是普通
            // TopAppBar —— 它的高度是固定的，配 exitUntilCollapsedScrollBehavior 时
            // offset 上限恒为 0，滚动其实一点效果都没有（写法看着对、行为是空的）。
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.module_upload_mine_entry_title)) },
                navigationIcon = { TopBarBackButton(onClick = onBack) },
                actions = {
                    // 刷新期间图标原地转、并置灰，与列表页顶栏同一套表达：
                    // 换成一个圆环会让人以为这颗按钮变成了别的东西。
                    val spin = rememberSpinAngle(mine.loading)
                    IconButton(onClick = onRefresh, enabled = !mine.loading) {
                        Icon(
                            // 在图层里读角度：每帧只重绘这一层，不重组顶栏。
                            modifier = Modifier.graphicsLayer { rotationZ = spin.value },
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.module_upload_refresh),
                            // 置灰交给 IconButton 自己：enabled=false 时 M3 会把内容色
                            // 换成 alpha 0.38，这里再手动 tint 会叠成两层透明。
                        )
                    }
                },
                colors = expressiveTopAppBarColors(),
                // 大标题栏自己吃顶部安全区，所以 Scaffold 那边只留水平方向。
                windowInsets = WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item {
                Text(
                    text = stringResource(R.string.module_upload_mine_entry_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (notice != null) {
                item {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (empty) {
                item { HistoryEmptyMaterial() }
            }

            items(groups, key = { it.id }) { group ->
                val local = installed[group.id]
                HistoryCardMaterial(
                    group = group,
                    expanded = expandedId == group.id,
                    onToggle = { expandedId = if (expandedId == group.id) null else group.id },
                    // 本地装着更新的一版才有「上传更新」：与列表页那一枚标签同一条判据。
                    uploadUpdate = local != null && ModuleUploadRules.showsUploadUpdate(
                        localVersionCode = local.versionCode,
                        status = local.status,
                        uploadedVersionCode = group.latestVersionCode,
                    ),
                    picking = state.pickingFor != null,
                    pickingThis = state.pickingFor == group.id,
                    onPick = { onPickForMine(group.latest) },
                )
            }
        }
    }
}

/**
 * 空态（Material）：与列表页 [EmptyStateMaterial] 同结构。
 *
 * 单独给一行灰字是不行的：这一页在「从来没投过」时是用户唯一能看到的画面，
 * 而它同时还承担「这页是干什么的」这个说明职责。图标 + 标题 + 一句怎么才会有，
 * 三件套缺一件都会让人以为加载失败了。
 */
@Composable
private fun HistoryEmptyMaterial() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            modifier = Modifier.size(34.dp),
            // 图标只是意象：压到 0.35 不透明度，不跟主文案抢注意力。
            imageVector = Icons.Outlined.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        )
        Text(
            text = stringResource(R.string.module_upload_mine_empty),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.module_upload_mine_empty_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 一个模块一张卡：收起时只有名字与版本数，展开才画它投过的每一版。 */
@Composable
private fun HistoryCardMaterial(
    group: ModuleUploadRules.MineGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    /** 本地装着比投过的更新的一版。 */
    uploadUpdate: Boolean,
    picking: Boolean,
    pickingThis: Boolean,
    onPick: () -> Unit,
) {
    val context = LocalContext.current
    // 名字左缘：图标块 36dp + 12dp 间距。展开区缩进到这条竖线上，
    // 一眼能看出它是这张卡的补充，而不是另起一块内容。
    val indent = 48.dp
    TonalCard(
        modifier = Modifier
            .fillMaxWidth()
            // 展开/收起随内容高度缓动，与列表页的行卡片同一套节奏。
            .animateContentSize(animationSpec = tween(durationMillis = 300)),
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
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconTile(Icons.Rounded.Extension, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.name.ifBlank { group.id },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // 名字可能缺（老条目），id 一定在；版本数就是「点开能看到几行」。
                        text = "${group.id} · " + stringResource(
                            R.string.module_upload_history_versions,
                            group.versions.size,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (uploadUpdate) {
                    StatusTag(
                        label = stringResource(R.string.module_upload_tag_upload_update),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                    )
                }
            }

            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = indent, top = 4.dp),
                    // 版本行是一份清单，不是几段话：2dp 让它们读起来是一组，
                    // 与列表页展开区的 4dp（段落间距）刻意不同。
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    group.versions.forEach { version ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                // 版本号缺了就退回版本代号：两个字段服务端都可能没给，
                                // 但总得让每一行有能区分开的东西。
                                text = version.version.ifBlank { "#${version.versionCode}" },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val time = formatEpochTime(context, version.time)
                            if (time.isNotEmpty()) {
                                Text(
                                    text = time,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // 动作与它的说明成对出现，与列表页每个动作同一个结构：
                    // 只给一枚「发新版本」，用户没法预判点了会发生什么。
                    // 挑包要弹系统选择器，回来之前这一行没有任何反馈——正在挑的那张卡
                    // 转圈并把按钮禁掉，其余卡一起禁用（闸门本来就是全局的）。
                    if (pickingThis) {
                        Row(
                            modifier = Modifier.padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = stringResource(R.string.module_upload_picking),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        RowActionMaterial(
                            label = stringResource(R.string.module_upload_mine_update),
                            summary = stringResource(R.string.module_upload_history_update_summary),
                            enabled = !picking,
                            onClick = onPick,
                            // 版本清单是 2dp 的紧凑行距，动作要自己和它分开一点，
                            // 否则读起来像是清单的最后一行。
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
