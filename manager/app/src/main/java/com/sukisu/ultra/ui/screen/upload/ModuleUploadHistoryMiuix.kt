package com.sukisu.ultra.ui.screen.upload

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.theme.LocalEnableBlur
import com.sukisu.ultra.ui.util.BlurredBar
import com.sukisu.ultra.ui.util.MineModule
import com.sukisu.ultra.ui.util.formatEpochTime
import com.sukisu.ultra.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 上传记录（Miuix）：与 [ModuleUploadHistoryMaterial] 同一份状态、同一组规则。
 *
 * 一个模块一张卡，收起只报「投过几版」，点开才列每一版与它的时间；本地装着更新的一版时，
 * 卡片右上角挂「上传更新」——与列表页那一枚标签同一条判据（[ModuleUploadRules.showsUploadUpdate]）。
 *
 * 骨架照列表页与上游详情页抄：`MiuixScrollBehavior` 负责标题随滚动收起，
 * `BlurredBar` + `barColor` 负责开了模糊时顶栏透出毛玻璃——这两件任何一件缺了，
 * 从列表页点进来都会看到一次明显的样式跳变。
 */
@Composable
fun ModuleUploadHistoryMiuix(
    /** 已装模块的当前状态：只用来判断「本地这一版比投过的新」。 */
    state: ModuleUploadListState,
    mine: MineState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    /** 给某个模块挑一枚新版本的 zip；与主列表共用同一个 SAF 启动器与同一道闸门。 */
    onPickForMine: (MineModule) -> Unit,
) {
    val enableBlur = LocalEnableBlur.current
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(enableBlur)
    // 模糊可用时顶栏底色透明，让毛玻璃本身当底色；否则退回表面色。
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    val groups = remember(mine.modules) { ModuleUploadRules.groupMine(mine.modules) }
    val installed = remember(state.rows) { state.rows.associateBy { it.id } }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }

    // 提示条一次只说一件事，顺序即优先级：先答「能不能用」，再答「为什么是空的」。
    // 刷新中且手里已经有名单时不报「正在加载」：顶栏那颗转着的刷新键已经说明了。
    val notice: String? = when {
        // 有旧名单又刷新失败时，服务端消息比「请重试」更具体，优先说它。
        mine.message != null -> mine.message
        mine.loading && groups.isEmpty() -> stringResource(R.string.module_upload_mine_loading)
        mine.needVerify -> stringResource(R.string.module_upload_mine_need_verify)
        mine.failed -> stringResource(R.string.module_upload_mine_failed)
        mine.degraded -> stringResource(R.string.module_upload_mine_degraded)
        else -> null
    }
    // 空态与提示条互斥：一句「还没投过」前面不该再压一条错误说明。
    val empty = groups.isEmpty() && !mine.loading && notice == null

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.module_upload_mine_entry_title),
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
                    // 刷新不在顶栏：这一页只有一件可刷的事，改成列表下拉（见下面 PullToRefresh）。
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        // 下拉刷新：全页只有这一件可刷的事，手势比顶栏那颗键更短。
        // isRefreshing 跟 mine.loading 走 —— 这一页没有「首次载入」与「重新拉」
        // 两种语义，两者都是打一次 GET /mine。
        val refreshTexts = listOf(
            stringResource(R.string.refresh_pulling),
            stringResource(R.string.refresh_release),
            stringResource(R.string.refresh_refresh),
            stringResource(R.string.refresh_complete),
        )
        PullToRefresh(
            isRefreshing = mine.loading,
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
                contentPadding = innerPadding,
                // 卡片间的纵向间距统一由列表负责：卡片自己再带 padding，两种间距会叠起来。
                verticalArrangement = Arrangement.spacedBy(12.dp),
                overscrollEffect = null,
            ) {
                item {
                    Text(
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp, end = 4.dp),
                        text = stringResource(R.string.module_upload_mine_entry_summary),
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }

                if (notice != null) {
                    item {
                        Text(
                            modifier = Modifier.padding(start = 4.dp, end = 4.dp),
                            text = notice,
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }

                if (empty) {
                    item { HistoryEmptyMiuix() }
                }

                items(groups, key = { it.id }) { group ->
                    val local = installed[group.id]
                    HistoryCardMiuix(
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

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/**
 * 空态（Miuix）：与列表页 [EmptyStateMiuix] 同结构。
 *
 * 这一页在「从来没投过」时是用户唯一能看到的画面，还兼任「这页是干什么的」——
 * 一行灰字撑不起这两个职责，看起来像加载失败了。
 */
@Composable
private fun HistoryEmptyMiuix() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = MiuixIcons.Notes,
            // 下面两行字已经把话说完了，图标不再重复念一遍。
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = colorScheme.onSurfaceVariantSummary.copy(alpha = 0.35f),
        )
        Text(
            modifier = Modifier.padding(top = 10.dp),
            text = stringResource(R.string.module_upload_mine_empty),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            modifier = Modifier.padding(top = 4.dp),
            text = stringResource(R.string.module_upload_mine_empty_summary),
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
    }
}

/** 一个模块一张卡：收起时只有名字与版本数，展开才画它投过的每一版。 */
@Composable
private fun HistoryCardMiuix(
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
    // 名字左缘：图标块 36dp + 12dp 间距。展开区缩进到这条竖线上。
    val nameIndent = 48.dp
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 展开区不是瞬移出来的：高度变化交给 animateContentSize 缓过去，
            // 与列表页的行卡片同一套节奏（tween 300）。
            .animateContentSize(animationSpec = tween(durationMillis = 300)),
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
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 与列表页同一个图标块：这一页和列表页说的是同一批模块，
                // 换个样子会让人以为它们不是一回事。
                RowIconTileMiuix()
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.name.ifBlank { group.id },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        modifier = Modifier.padding(top = 2.dp),
                        // 名字可能缺（老条目），id 一定在；版本数就是「点开能看到几行」。
                        text = "${group.id} · " + stringResource(
                            R.string.module_upload_history_versions,
                            group.versions.size,
                        ),
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (uploadUpdate) {
                    TagMiuix(
                        text = stringResource(R.string.module_upload_tag_upload_update),
                        background = colorScheme.primaryContainer,
                        content = colorScheme.onPrimaryContainer,
                    )
                }
            }

            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = nameIndent),
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
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val time = formatEpochTime(context, version.time)
                            if (time.isNotEmpty()) {
                                Text(
                                    text = time,
                                    fontSize = 11.sp,
                                    color = colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                    // 动作后面跟一句说明，与列表页每个动作同一个结构：
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
                                progress = null,
                                size = 16.dp,
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = stringResource(R.string.module_upload_picking),
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    } else {
                        Column(modifier = Modifier.padding(top = 4.dp)) {
                            TextButton(
                                text = stringResource(R.string.module_upload_mine_update),
                                onClick = onPick,
                                enabled = !picking,
                            )
                            Text(
                                text = stringResource(R.string.module_upload_history_update_summary),
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }
    }
}
