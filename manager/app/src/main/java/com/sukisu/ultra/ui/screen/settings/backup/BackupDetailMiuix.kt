package com.sukisu.ultra.ui.screen.settings.backup

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
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
import top.yukonga.miuix.kmp.basic.HorizontalDivider
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
import top.yukonga.miuix.kmp.preference.CheckboxPreference
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
                            text = groupSummary(group, state.showsOriginBadge),
                            fontSize = 13.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        // 这一组存在哪：恢复/删除之前该知道动的是哪一侧的文件。
                        BackupLocation.full(group.origin, state.cloudSavedUrl)
                            .takeIf { it.isNotBlank() }
                            ?.let { location ->
                                Text(
                                    text = stringResource(
                                        if (group.origin == BackupOrigin.CLOUD) {
                                            R.string.backup_location_cloud
                                        } else {
                                            R.string.backup_location_local
                                        },
                                        location,
                                    ),
                                    fontSize = 12.sp,
                                    color = colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        // 两个按钮等宽：宽度跟着文字走的话，一行里一长一短看着像没对齐的标签，
                        // 而这两个动作是一对——"导出"和"恢复"都是对勾选项整体生效的。
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // 恢复和导出都作用在勾选上，没勾任何一项时它们按不动；删除动的是
                            // 整组，跟勾选无关。
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
                        HorizontalDivider(modifier = Modifier.padding(top = 14.dp))
                        // 删除是这一页最重的一件事，但它不是主按钮：淡红底配正红字，实心大红块
                        // 会把上面那两个真正要按的按钮压下去，看着像整页的主操作。字用 error
                        // 而不是 onErrorContainer：后者的红偏暗，整块看着像禁用。
                        TextButton(
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                            text = stringResource(R.string.backup_detail_delete),
                            enabled = !busy,
                            colors = ButtonDefaults.textButtonColors(
                                color = colorScheme.errorContainer,
                                textColor = colorScheme.error,
                            ),
                            onClick = actions.onDeleteGroup,
                        )
                    }
                }
            }
            // 全选和条目放在同一张卡里（和主列表的模块勾选一样）：一条一张卡在 11 条时
            // 就是一堵卡片墙，而它们本来就是同一份备份里的东西。
            item {
                Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    CheckboxPreference(
                        title = stringResource(R.string.backup_detail_select_all),
                        summary = stringResource(R.string.backup_module_selected, selected, group.rows.size),
                        checked = state.allGroupEntriesSelected,
                        onCheckedChange = actions.onSetAllGroupEntries,
                    )
                    group.rows.forEach { row ->
                        CheckboxPreference(
                            title = entryTitle(row, group),
                            summary = row.subtitle.takeIf { it.isNotBlank() },
                            checked = row.id in state.openGroupSelected,
                            onCheckedChange = { actions.onToggleGroupEntry(row.id) },
                        )
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

/** 条目标题：整组都是回滚点时标题上已经说了，单个条目就不必再缀一次。 */
@Composable
internal fun entryTitle(row: BackupRow, group: BackupGroup): String =
    if (row.isRollback && !group.isRollback) {
        row.title + BackupListFormatter.SEPARATOR + stringResource(R.string.backup_rollback_badge)
    } else {
        row.title
    }
