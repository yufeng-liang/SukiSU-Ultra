package com.sukisu.ultra.ui.screen.settings.backup

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.data.backup.BackupOrigin
import com.sukisu.ultra.ui.component.material.ExpressiveScaffold
import com.sukisu.ultra.ui.component.material.SegmentedColumn
import com.sukisu.ultra.ui.component.material.SegmentedItemContainer
import com.sukisu.ultra.ui.component.material.SegmentedListItem
import com.sukisu.ultra.ui.component.material.expressiveTopAppBarColors

/** 删除按钮那条描边的透明度：全实心红边会把这颗按钮抬到和主按钮一样重。 */
private const val DELETED_BORDER_ALPHA = 0.5f

/**
 * 一次备份的详情：这一组里到底有哪些条目。
 *
 * 列表上不铺条目，是因为一次备份 11 个模块就是 11 行。勾选之后批量恢复/导出：一次备份本来
 * 就是"一整份"，逐个点按钮反而慢；而"点一下行就恢复"在 root 工具里等于给误触点了火，
 * 所以恢复只发生在按按钮的时候。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupDetailMaterial(
    group: BackupGroup,
    state: BackupUiState,
    actions: BackupActions,
    snackbarHostState: SnackbarHostState,
) {
    val selected = state.openGroupSelection.size
    val busy = state.loading

    // 和列表页用同一层 Scaffold：详情页原来是自己一层白底，进出的时候底色会换一次，而且
    // 白色的顶栏跟列表页那层 surfaceContainer 也不是一个色。两边一致之后，进出详情只有内容
    // 在动。
    ExpressiveScaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(group.label) },
                colors = expressiveTopAppBarColors(),
                navigationIcon = {
                    IconButton(onClick = actions.onCloseGroup) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SegmentedColumn {
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(group.label) },
                            supportingContent = {
                                // 这一组存在哪：恢复/删除之前该知道动的是哪些地方的文件。
                                // 本机和云端都写过就是两行（两处地址各不相同）。
                                Text(groupHeaderText(group, state.cloudSavedUrl))
                            },
                        )
                    }
                    item {
                        // 按钮面板自己不带底色，得接着标题行的卡面往下画：不接的话标题行收在
                        // 4dp 的内圆角上、下面一截直接落在页面底色上，像一张被切掉下半截的卡片。
                        SegmentedItemContainer {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                                // 两个按钮等宽：宽度跟着文字走的话，一行里一长一短看着像没对齐的
                                // 标签，而这两个动作是一对——"导出"和"恢复"都对勾选项整体生效。
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    // 三个按钮都作用在勾选上，一条都没勾时都按不动：删除也不再是
                                    // "删整组"——本机与云端各有一份时，只勾了本地就只删本地那份。
                                    OutlinedButton(
                                        onClick = actions.onShareSelected,
                                        enabled = !busy && selected > 0,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(stringResource(R.string.backup_detail_share))
                                    }
                                    Button(
                                        onClick = actions.onRestoreSelected,
                                        enabled = !busy && selected > 0,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(stringResource(R.string.backup_detail_restore))
                                    }
                                }
                                // 删除是这一页最重的一件事，但它不是主按钮。
                                //
                                // 不填底色：这套配色里的 errorContainer 是一块偏暖的珊瑚色，铺满整行
                                // 之后成了整页最大的一块颜色，压在冷色调的页面上就是那句"配色有点丑"。
                                // 走描边——红字 + 半透明红边，形态和上面那个「导出/分享」一致，既看得出
                                // 是个按钮（纯红字会被读成说明文字），又不像一整块警告色。
                                //
                                // 和上面一行之间只用留白隔开，不再画一条分割线：这一整块本来就是一张
                                // 卡，三个按钮都在卡里，中间那条横线把一张卡切成了两截。
                                OutlinedButton(
                                    onClick = actions.onDeleteChecked,
                                    enabled = !busy && selected > 0,
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.error.copy(alpha = DELETED_BORDER_ALPHA),
                                    ),
                                ) {
                                    Text(stringResource(R.string.backup_detail_delete))
                                }
                            }
                        }
                    }
                }
            }
            // 条目按来源分成一段一张卡：本机一段、云端一段。
            //
            // 两侧原来混在一张卡里，只在每行后面缀一个来源标签，勾选和"恢复/删除"的对应关系
            // 全靠用户自己脑补——只勾了本地那一行时，他没法确定按下去动的是哪一份，而"删除这
            // 次备份"又整组删，两边一起没。分开之后：一段一行标题（左边那格管这一段的"全选"），
            // 勾了哪一段就是哪一份，按钮动的是勾中的那几条。
            val sections = group.origins.map { origin -> origin to group.rows.filter { it.origin == origin } }
            // 只有一侧时标题仍是「全选」：这时候没有"哪一份"可挑，一行就是全部。
            val singleOrigin = sections.size == 1
            sections.forEach { (origin, rows) ->
                // 这一段是否展开要在 item 之外算：标题行和它下面那几条各自是一个 item，
                // 在标题行的 lambda 里定义的话，下面那些条目根本看不见它。
                val expanded = origin !in state.openGroupCollapsed
                item {
                    SegmentedColumn {
                        item {
                            // 这一行和主列表的「模块」那一栏是同一套做法：点行展开/收起下面这一
                            // 段的条目，左边那一格管这一段的勾选，只勾了一部分时画成半选——否则
                            // 挑完再收起来，这一行看起来和"一条都没勾"一模一样，刚挑的那几个就
                            // 像没被记住。
                            // 这一段勾了几条 / 共几条由状态层算：两个主题各算一遍，迟早有一边把
                            // 分母写成整组的行数。
                            val picks = state.picksOf(origin)
                            val rotation by animateFloatAsState(
                                targetValue = if (expanded) 180f else 0f,
                                label = "detailArrow",
                            )
                            SegmentedListItem(
                                onClick = if (picks.total > 0) {
                                    { actions.onToggleGroupOrigin(origin) }
                                } else {
                                    null
                                },
                                leadingContent = {
                                    if (picks.partial) {
                                        // 半选时点一下就是全选：从"挑了几个"再往前走一步，
                                        // 只剩"这一侧全都要"。
                                        TriStateCheckbox(
                                            state = ToggleableState.Indeterminate,
                                            onClick = { actions.onSetOriginEntries(origin, true) },
                                            modifier = Modifier.size(24.dp),
                                        )
                                    } else {
                                        Checkbox(
                                            checked = picks.allSelected,
                                            onCheckedChange = { actions.onSetOriginEntries(origin, it) },
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                },
                                headlineContent = {
                                    Text(
                                        if (singleOrigin) {
                                            stringResource(R.string.backup_detail_select_all)
                                        } else {
                                            stringResource(BackupLabels.origin(origin))
                                        },
                                    )
                                },
                                // 数字按**这一段**算：两侧分开之后，"已选 2/2"要读成"这一段里的
                                // 两条都勾上了"，而不是"整组四条我勾了两条"。
                                supportingContent = {
                                    Text(
                                        stringResource(
                                            R.string.backup_module_selected,
                                            picks.picked,
                                            picks.total,
                                        ),
                                    )
                                },
                                trailingContent = {
                                    if (picks.total > 0) {
                                        Icon(
                                            imageVector = Icons.Filled.ExpandMore,
                                            contentDescription = stringResource(R.string.expand),
                                            modifier = Modifier.graphicsLayer { rotationZ = rotation },
                                        )
                                    }
                                },
                            )
                        }
                        rows.forEach { row ->
                            item(visible = expanded) {
                                SubEntryRowMaterial(
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
        }
    }
}
