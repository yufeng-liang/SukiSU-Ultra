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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
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
 * 与 Material 那套同一条内容、同一套判据，只换控件：正文与勾选框都放进 [OverlayDialog] 的
 * 滚动内容区，[OverlayDialog] 的 title 只放标题。
 *
 * 底部是一个"承诺"勾选框而不是"不再提示"：用户勾它是一次主动表态（已读并自负风险），
 * 副作用才是"以后不再弹"。[onDismiss] 的参数就是这个勾选的值，所有关闭路径都带上它
 * （点确定、点外面、按返回）：勾选框表达的是用户的意愿，不该因为他用哪种方式关掉而作废。
 */
@Composable
fun RepoDisclaimerDialogMiuix(
    show: Boolean,
    onDismiss: (committed: Boolean) -> Unit,
) {
    // 以 show 为 key：每次重新弹出都从"未勾选"开始。OverlayDialog 在 show=false 时内容仍在
    // 组合里，不带 key 的话上一次的勾选会留到下一次。
    var committed by remember(show) { mutableStateOf(false) }
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
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.module_repo_disclaimer_body),
                    fontSize = 14.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        state = if (committed) ToggleableState.On else ToggleableState.Off,
                        onClick = { committed = !committed },
                    )
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = stringResource(R.string.module_repo_disclaimer_commitment),
                        fontSize = 14.sp,
                    )
                }
                TextButton(
                    text = stringResource(R.string.module_repo_disclaimer_confirm),
                    onClick = { onDismiss(committed) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        },
    )
}
