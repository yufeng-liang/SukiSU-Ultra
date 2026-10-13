package com.sukisu.ultra.ui.screen.settings.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sukisu.ultra.R
import com.sukisu.ultra.data.backup.BackupOrigin
import com.sukisu.ultra.ui.theme.LocalEnableBlur
import com.sukisu.ultra.ui.util.BlurredBar
import com.sukisu.ultra.ui.util.blockTaps
import com.sukisu.ultra.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 一次备份的详情：这一组里到底有哪些条目。
 *
 * 列表上不铺条目，是因为一次备份 11 个模块就是 11 行。勾选之后批量恢复/导出：一次备份本来
 * 就是"一整份"，逐个点按钮反而慢；而"点一下行就恢复"在 root 工具里等于给误触点了火，
 * 所以恢复只发生在按按钮的时候。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupDetailMiuix(
    group: BackupGroup,
    state: BackupUiState,
    actions: BackupActions,
    snackbarHostState: SnackbarHostState,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    val selected = state.openGroupSelection.size
    val busy = state.loading

    Scaffold(
        topBar = {
            // 和备份页同一个道理：顶栏半透明、列表从它下面滚过去，所以顶栏自己得接住点击，
            // 否则点在这行的空白处会穿到下面那一条上去。见 blockTaps。
            Box(Modifier.blockTaps()) {
                BlurredBar(backdrop) {
                    TopAppBar(
                        color = barColor,
                        title = group.label,
                        scrollBehavior = scrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = actions.onCloseGroup) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = null)
                            }
                        }
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
            contentPadding = innerPadding,
            overscrollEffect = null,
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text(text = group.label, fontSize = 17.sp)
                        Text(
                            // 这一组存在哪：恢复/删除之前该知道动的是哪些地方的文件。
                            // 本机和云端都写过就是两行（两处地址各不相同）。
                            text = groupHeaderText(group, state.cloudSavedUrl),
                            fontSize = 13.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        // 两个按钮等宽：宽度跟着文字走的话，一行里一长一短看着像没对齐的标签，
                        // 而这两个动作是一对——"导出"和"恢复"都是对勾选项整体生效的。
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // 三个按钮都作用在勾选上：没勾任何一项时它们都按不动。删除不再是"动
                            // 整组"——那个语义和勾选框对不上，勾了本地一份却把两侧都删掉，是这
                            // 一页最容易出事的地方。
                            TextButton(
                                modifier = Modifier.weight(1f),
                                text = stringResource(R.string.backup_detail_share),
                                enabled = !busy && selected > 0,
                                onClick = actions.onShareSelected,
                            )
                            TextButton(
                                modifier = Modifier.weight(1f),
                                text = stringResource(R.string.backup_detail_restore),
                                enabled = !busy && selected > 0,
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                onClick = actions.onRestoreSelected,
                            )
                        }
                        // 删除是这一页最重的一件事，但它不是主按钮：淡红底配正红字，实心大红块
                        // 会把上面那两个真正要按的按钮压下去，看着像整页的主操作。字用 error
                        // 而不是 onErrorContainer：后者的红偏暗，整块看着像禁用。
                        // 按钮之间不再画分隔线：线跟按钮一样宽，看着像把按钮框进了格子里。
                        TextButton(
                            modifier = Modifier.padding(top = 14.dp).fillMaxWidth(),
                            text = stringResource(R.string.backup_detail_delete),
                            enabled = !busy && selected > 0,
                            colors = ButtonDefaults.textButtonColors(
                                color = colorScheme.errorContainer,
                                textColor = colorScheme.error,
                            ),
                            onClick = actions.onDeleteChecked,
                        )
                    }
                }
            }
            // 条目按来源分成几段：本机一段、云端一段。两侧原来混在一张卡里，只在每行后面缀一个
            // 来源标签，勾了哪一份、按钮动的是哪一份全靠用户自己脑补（Material 那一版同理）。
            item {
                val singleOrigin = group.origins.size == 1
                group.origins.forEach { origin ->
                    // 两侧各是一张卡：本机一段、云端一段，中间留出空档。以前两段挤在同一张卡里
                    // 上下相接，看着像一整块，"这一段到哪儿为止"只能靠标题行去猜。
                    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                        val rows = group.rows.filter { it.origin == origin }
                        // 这一段勾了几条 / 共几条由状态层算：两个主题各算一遍，迟早有一边把
                        // 分母写成整组的行数。
                        val picks = state.picksOf(origin)
                        val expanded = origin !in state.openGroupCollapsed
                        // 和「模块」那一行同一个道理：勾选框管这一段的全选/全不选，行本身收放
                        // 这一段。整行点击要是也去翻勾选框，收放就没有地方点了。
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = picks.total > 0) { actions.onToggleGroupOrigin(origin) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                state = when {
                                    picks.allSelected -> ToggleableState.On
                                    picks.partial -> ToggleableState.Indeterminate
                                    else -> ToggleableState.Off
                                },
                                // 半选时点一下是"补全到全选"，和 Material 那边一致。
                                onClick = { actions.onSetOriginEntries(origin, !picks.allSelected) },
                            )
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(
                                    text = if (singleOrigin) {
                                        stringResource(R.string.backup_detail_select_all)
                                    } else {
                                        stringResource(BackupLabels.origin(origin))
                                    },
                                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                                )
                                // 数字按**这一段**算：两侧分开之后，"已选 2/2"要读成"这一段里的两条
                                // 都勾上了"，而不是"整组四条我勾了两条"。
                                Text(
                                    text = stringResource(
                                        R.string.backup_module_selected,
                                        picks.picked,
                                        picks.total,
                                    ),
                                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                                    color = colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            if (picks.total > 0) {
                                ExpandChevron(expanded = expanded)
                            }
                        }
                        if (expanded) {
                            rows.forEach { row ->
                                SubEntryRowMiuix(
                                    title = entryTitle(row),
                                    // 来源已经写在标题行上了，行里不再重复一遍。
                                    summary = row.subtitle.takeIf { it.isNotBlank() },
                                    checked = row.id in state.openGroupSelected,
                                    onCheckedChange = { actions.onToggleGroupEntry(row.id) },
                                )
                            }
                        }
                    }
                }
            }
            // 和主列表一样：滚到底不能贴着屏幕边缘，最后一行会被手势条压掉。
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
    }
}

/** 条目在列表里显示的名字。 */
internal fun entryTitle(row: BackupRow): String = row.title
