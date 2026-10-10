package com.sukisu.ultra.ui.screen.settings.backup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R

/**
 * 备份进度与结果。
 *
 * 云端上传 boot 是几十秒到几分钟的事：只有一个不动的按钮，用户没法判断是在传还是卡住了。
 * 跑完之后结果也留在这里，而不是几秒就消失的 snackbar——哪一份失败、为什么，值得看清。
 */
@Composable
fun BackupProgressDialogMaterial(
    state: BackupUiState,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
) {
    val run = state.backupRun ?: return
    AlertDialog(
        // 还在跑的时候点外面/返回键不该把它关掉：关掉就再也看不到这次备份的进度和结果了。
        onDismissRequest = { if (run.done) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = run.done,
            dismissOnClickOutside = run.done,
        ),
        title = {
            Text(
                stringResource(
                    if (run.done) R.string.backup_result_title else R.string.backup_progress_title,
                ),
            )
        },
        text = {
            Column {
                if (run.done) {
                    Text(run.result.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    // 跑完之后瞬时速度就没意义了，用户想知道的是"这一份到底传了多久、平均多快"。
                    averageLine(run)?.let { average ->
                        Text(
                            text = average,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                } else {
                    Text(
                        text = stringResource(
                            R.string.backup_progress_target,
                            run.current,
                            run.total,
                            stringResource(BackupLabels.origin(run.origin)),
                            stringResource(BackupLabels.kind(run.kind)),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { run.fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    Text(
                        text = transferLine(run),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = run.done) {
                Text(stringResource(R.string.backup_done))
            }
        },
        // 还在跑的时候给一条出路：窗口关不掉也停不下来，云端上传卡住时原来只能杀进程。
        dismissButton = if (run.done) {
            null
        } else {
            {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}

/**
 * 「第几个文件 · 已传 / 总共 · 速度」。
 *
 * 文件数那一段回答的是"还有几个模块没传"：一次备份 11 个模块，进度里只写"第 1/1 个目标"会让人
 * 以为那 1/1 说的是模块（就是被问到的那个）。还不知道文件数、或还没采到速度采样时，
 * 对应那段直接不写——"0 B/s"看着像卡住了。
 */
@Composable
internal fun transferLine(run: BackupRunState): String {
    val parts = buildList {
        if (run.fileCount > 0 && run.fileIndex > 0) {
            add(stringResource(R.string.backup_progress_files, run.fileIndex, run.fileCount))
        }
        add(BackupListFormatter.humanSize(run.sentBytes) + " / " + BackupListFormatter.humanSize(run.totalBytes))
        if (run.speedBytesPerSecond > 0L) {
            add(BackupListFormatter.humanSize(run.speedBytesPerSecond) + "/s")
        }
    }
    return parts.joinToString(BackupListFormatter.SEPARATOR)
}

/**
 * 跑完之后那行平均速度（总字节 ÷ 这次目标的耗时）。
 *
 * 没推过东西时返回 null：一次全跳过的备份算出 0 B/s 只是噪音，而"没有可备份的镜像"那种情况
 * 连耗时都不代表传输。取消的那次也不报：只传了一半，除出来的数字是对的，读起来却像成绩。
 */
@Composable
internal fun averageLine(run: BackupRunState): String? =
    if (run.cancelled || run.averageBytesPerSecond <= 0L) {
        null
    } else {
        stringResource(
            R.string.backup_result_average_speed,
            BackupListFormatter.humanSize(run.averageBytesPerSecond),
        )
    }
