package com.sukisu.ultra.ui.screen.modulerepo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R

/**
 * 进入模块仓库页时的免责说明（Material 风格）。
 *
 * 用 [BasicAlertDialog] + [Surface]，与投稿对话框同一套写法：正文有好几段，必须自己控制滚动
 * 容器，套 `AlertDialog` 的 title/text/confirmButton 槽位反而是负担。
 *
 * 两道闸，缺一不可：
 * ① **读完才能勾**：正文滚到底（`!canScrollForward`）之前勾选框是禁用的——不读完就勾等于没读。
 * ② **勾了才能点**：「我已知晓」在勾选框为空时禁用，所以对话框里没有"随手点掉"的路径。
 *
 * 于是只剩两条出路：读完并承诺（勾选后点「我已知晓」），或是不承诺地关掉（点外面／按返回）。
 * 后者按"不接受"处理——[onDismiss] 拿到 false，调用方据此直接退回上一页，本页不展示；
 * 只有承诺过的才落盘、以后不再弹。底部是"承诺"勾选框而不是"不再提示"：勾它是一次主动表态
 * （已读并自负风险），副作用才是"以后不再弹"。[onDismiss] 的参数就是这个勾选的值，所有关闭
 * 路径都带上它。
 */
@Composable
fun RepoDisclaimerDialogMaterial(
    show: Boolean,
    onDismiss: (committed: Boolean) -> Unit,
) {
    if (!show) return
    // 以 show 为 key：每次重新弹出都从"未勾选"开始，不带上一轮的记忆进来。
    var committed by remember(show) { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    // 到底了才算读完；正文短到不需要滚动时 canScrollForward 一开始就是 false，视为已读完。
    val readToEnd by remember { derivedStateOf { !scrollState.canScrollForward } }
    val emphasis = SpanStyle(
        color = MaterialTheme.colorScheme.error,
        fontWeight = FontWeight.Bold,
    )
    BasicAlertDialog(onDismissRequest = { onDismiss(committed) }) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    text = stringResource(R.string.module_repo_disclaimer_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 24.dp),
                    text = annotateDisclaimer(
                        stringResource(R.string.module_repo_disclaimer_body),
                        emphasis,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                ) {
                    // 这一行一直占位（读完时留空）：否则它消失的瞬间下面整块会往上跳一下。
                    Text(
                        text = if (readToEnd) "" else stringResource(R.string.module_repo_disclaimer_scroll_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = committed,
                            onCheckedChange = if (readToEnd) ({ committed = it }) else null,
                            enabled = readToEnd,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            modifier = Modifier.padding(start = 8.dp),
                            text = stringResource(R.string.module_repo_disclaimer_commitment),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (readToEnd) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = { onDismiss(committed) },
                        enabled = committed,
                    ) {
                        Text(stringResource(R.string.module_repo_disclaimer_confirm))
                    }
                }
            }
        }
    }
}
