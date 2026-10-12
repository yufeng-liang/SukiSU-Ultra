package com.sukisu.ultra.ui.screen.settings.backup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.sukisu.ultra.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 备份进度与结果。
 *
 * 云端上传 boot 是几十秒到几分钟的事：只有一个不动的按钮，用户没法判断是在传还是卡住了。
 * 跑完之后结果也留在这里，而不是几秒就消失的 snackbar——哪一份失败、为什么，值得看清。
 */
@Composable
fun BackupProgressDialogMiuix(
    state: BackupUiState,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
) {
    val run = state.backupRun ?: return
    WindowDialog(
        show = true,
        // 还在跑的时候返回键不该把它关掉：关掉就再也看不到这次备份的进度和结果了。
        onDismissRequest = { if (run.done) onDismiss() },
        content = {
            if (!run.done) {
                val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
                NavigationBackHandler(
                    state = navEventState,
                    isBackEnabled = true,
                    onBackCompleted = { },
                )
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(
                        if (run.done) R.string.backup_result_title else R.string.backup_progress_title,
                    ),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (run.done) {
                    run.resultTable?.let { table ->
                        ResultTableMiuix(table)
                    } ?: Text(
                        text = run.result.orEmpty(),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    // 跑完之后瞬时速度就没意义了，用户想知道的是"这一份到底传了多久、平均多快"。
                    averageLine(run)?.let { average ->
                        Text(
                            text = average,
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
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
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    LinearProgressIndicator(
                        // 打包阶段没有字节可报：null = 不确定进度，条会自己走，不会杵在 0 上
                        // 加一句"0 B / 0 B"那样看着像卡死。
                        progress = if (run.packing) null else run.fraction,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    // 条只画得了当前这一步，所以两个阶段都列出来：哪一步在走、一共几步、这一步
                    // 走了多少，一行说清。没到的那一步淡着，扫一眼就知道现在在哪。
                    stageLines(run).forEach { stage ->
                        Text(
                            text = stage.text,
                            fontSize = 12.sp,
                            color = if (stage.active) colorScheme.onSurface else colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                // 还在跑的时候给一条出路：窗口关不掉也停不下来，云端上传卡住时原来只能杀进程。
                // 跑着的时候【完成】是灰的，所以同色的两个按钮不会同时可点。
                if (!run.done) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        onClick = onCancel,
                    )
                }
                TextButton(
                    text = stringResource(R.string.backup_done),
                    enabled = run.done,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    onClick = onDismiss,
                )
            }
        },
    )
}

/**
 * 结果表：一行一个目标，按来源分组，两列对齐。
 *
 * "哪一侧、哪类内容、结果如何"三件事在一行里说完。以前是一串
 * "本地 模块：这 10 项与已有备份内容相同，未重复备份 · 云端 模块：已备份 10 项 · …"：
 * 每条都带着自己的来源和内容，并排四条读起来像四句话，而用户要的是"哪边写了、哪边没写"
 * 这个对照——分组之后同一侧竖着对齐，扫一眼就有答案。
 *
 * 失败原因不占右列：它比"结果"长得多（HTTP 状态码、文件路径），塞进去会把列宽撑歪，
 * 所以整行宽度排在表下面，前面加一行"失败"。
 */
@Composable
private fun ResultTableMiuix(result: BackupResult) {
    Column {
        resultGroups(result.rows).forEachIndexed { index, rows ->
            Text(
                text = stringResource(BackupLabels.origin(rows.first().origin)),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.primary,
                modifier = Modifier.padding(top = if (index == 0) 0.dp else 12.dp),
            )
            rows.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(
                        text = stringResource(BackupLabels.kind(row.kind)),
                        fontSize = 14.sp,
                        // 两列按比例分：右列起点在每一行都一样，这才有表格的样子。
                        modifier = Modifier.weight(0.42f),
                    )
                    Text(
                        text = row.outcome,
                        fontSize = 14.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.weight(0.58f),
                    )
                }
            }
        }
        if (result.failures.isNotEmpty()) {
            Text(
                text = stringResource(R.string.backup_result_failed),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
            result.failures.forEach { line ->
                Text(
                    text = line,
                    fontSize = 12.sp,
                    color = colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        // 表里出现"已是最新"才解释这个词（见 BackupText.anyUnchanged）：四格里有两格写着同一句
        // 短话，用户需要知道它到底指什么，而这句话只需要说一遍。
        result.note?.let { note ->
            Text(
                text = note,
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
