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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R

/**
 * 进入模块仓库页时的免责说明（Material 风格）。
 *
 * 用 [BasicAlertDialog] + [Surface]，与投稿对话框同一套写法：正文有好几段，必须自己控制滚动
 * 容器，套 `AlertDialog` 的 title/text/confirmButton 槽位反而是负担。
 *
 * 底部是一个"承诺"勾选框而不是"不再提示"：用户勾它是一次主动表态（已读并自负风险），
 * 副作用才是"以后不再弹"。[onDismiss] 的参数就是这个勾选的值，**所有关闭路径都带上它**——
 * 点确定、点外面、按返回键都一样：勾选框表达的是用户的意愿，不该因为他用哪种方式关掉而作废。
 */
@Composable
fun RepoDisclaimerDialogMaterial(
    show: Boolean,
    onDismiss: (committed: Boolean) -> Unit,
) {
    if (!show) return
    // 以 show 为 key：每次重新弹出都从"未勾选"开始，不带上一轮的记忆进来。
    var committed by remember(show) { mutableStateOf(false) }
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
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    text = stringResource(R.string.module_repo_disclaimer_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = committed,
                        onCheckedChange = { committed = it },
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = stringResource(R.string.module_repo_disclaimer_commitment),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = { onDismiss(committed) }) {
                        Text(stringResource(R.string.module_repo_disclaimer_confirm))
                    }
                }
            }
        }
    }
}
