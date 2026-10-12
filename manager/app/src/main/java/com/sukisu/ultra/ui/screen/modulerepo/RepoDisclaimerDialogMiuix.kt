package com.sukisu.ultra.ui.screen.modulerepo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sukisu.ultra.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 进入模块仓库页时的免责说明（Miuix 风格）。
 *
 * 与 Material 那套同一条内容、同一套判据，只换控件：正文、提示、勾选框与按钮都在
 * [OverlayDialog] 的滚动内容区里，[OverlayDialog] 的 title 只放标题。
 *
 * 两道闸，缺一不可：
 * ① **读完才能勾**：滚到底（`!canScrollForward`）之前勾选框禁用——不读完就勾等于没读。
 * ② **勾了才能点**：「我已知晓」在勾选框为空时禁用，所以对话框里没有"随手点掉"的路径。
 *
 * 于是只剩两条出路：读完并承诺（勾选后点「我已知晓」），或是不承诺地关掉（点外面／按返回）。
 * 后者按"不接受"处理——[onDismiss] 拿到 false，调用方据此直接退回上一页，本页不展示；
 * 只有承诺过的才落盘、以后不再弹。底部是"承诺"勾选框而不是"不再提示"：勾它是一次主动表态
 * （已读并自负风险），副作用才是"以后不再弹"。[onDismiss] 的参数就是这个勾选的值，所有关闭
 * 路径都带上它。
 */
@Composable
fun RepoDisclaimerDialogMiuix(
    show: Boolean,
    onDismiss: (committed: Boolean) -> Unit,
) {
    // 以 show 为 key：每次重新弹出都从"未勾选"开始。OverlayDialog 在 show=false 时内容仍在
    // 组合里，不带 key 的话上一次的勾选会留到下一次。
    var committed by remember(show) { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    // 到底了才算读完；正文短到不需要滚动时 canScrollForward 一开始就是 false，视为已读完。
    val readToEnd by remember { derivedStateOf { !scrollState.canScrollForward } }
    val emphasis = SpanStyle(color = colorScheme.error, fontWeight = FontWeight.Bold)
    OverlayDialog(
        show = show,
        title = stringResource(R.string.module_repo_disclaimer_title),
        onDismissRequest = { onDismiss(committed) },
        // 纵向留 24dp（内容区只补左右）：否则正文与底部按钮会贴住对话框上下边框。
        insideMargin = DpSize(0.dp, 24.dp),
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = annotateDisclaimer(
                        stringResource(R.string.module_repo_disclaimer_body),
                        emphasis,
                    ),
                    fontSize = 14.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
                // 这一行一直占位（读完时留空）：否则它消失的瞬间下面整块会往上跳一下。
                Text(
                    text = if (readToEnd) "" else stringResource(R.string.module_repo_disclaimer_scroll_hint),
                    fontSize = 12.sp,
                    color = colorScheme.primary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        state = if (committed) ToggleableState.On else ToggleableState.Off,
                        onClick = { if (readToEnd) committed = !committed },
                        enabled = readToEnd,
                    )
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = stringResource(R.string.module_repo_disclaimer_commitment),
                        fontSize = 14.sp,
                        color = if (readToEnd) colorScheme.onSurface else colorScheme.onSurfaceVariantSummary,
                    )
                }
                TextButton(
                    text = stringResource(R.string.module_repo_disclaimer_confirm),
                    onClick = { onDismiss(committed) },
                    enabled = committed,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        },
    )
}
