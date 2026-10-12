package com.sukisu.ultra.ui.screen.settings.backup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
                    run.resultTable?.let { table ->
                        ResultTableMaterial(table)
                    } ?: Text(run.result.orEmpty(), style = MaterialTheme.typography.bodyMedium)
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
                    // 打包阶段（源在打 zip / 读镜像）没有字节可报：画不确定进度。停在一个 0 的条
                    // 上加一句"0 B / 0 B"就是用户看到的"点下去几秒没反应"。
                    if (run.packing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                    } else {
                        LinearProgressIndicator(
                            progress = { run.fraction },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        )
                    }
                    // 条只画得了当前这一步，所以两个阶段都列出来：哪一步在走、一共几步、这一步
                    // 走了多少，一行说清。没到的那一步淡着，扫一眼就知道现在在哪。
                    stageLines(run).forEach { stage ->
                        Text(
                            text = stage.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (stage.active) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
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
private fun ResultTableMaterial(result: BackupResult) {
    Column {
        resultGroups(result.rows).forEachIndexed { index, rows ->
            Text(
                text = stringResource(BackupLabels.origin(rows.first().origin)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = if (index == 0) 0.dp else 12.dp),
            )
            rows.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(
                        text = stringResource(BackupLabels.kind(row.kind)),
                        style = MaterialTheme.typography.bodyMedium,
                        // 两列按比例分：右列起点在每一行都一样，这才有表格的样子。
                        modifier = Modifier.weight(0.42f),
                    )
                    Text(
                        text = row.outcome,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.58f),
                    )
                }
            }
        }
        if (result.failures.isNotEmpty()) {
            Text(
                text = stringResource(R.string.backup_result_failed),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
            result.failures.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        // 表里出现"已是最新"才解释这个词（见 BackupText.anyUnchanged）：四格里有两格写着同一句
        // 短话，用户需要知道它到底指什么，而这句话只需要说一遍。
        result.note?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * 一个目标要走的阶段数：打包（产出归档）→ 传输（写到目标）。
 *
 * 和引擎里能报出来的两段一一对应（[BackupSource.export] 的 `onPacking` 与存储写入的
 * `onProgress`）。引擎要是多出一段，这个数也要跟着改，否则"阶段 2/2"会说谎。
 */
private const val PROGRESS_STAGES = 2

/** 阶段表里的一行：[text] 形如"阶段 1/2 · 正在打包 3/10"，[active] 是正在走的那一步。 */
data class StageLine(val text: String, val active: Boolean)

/**
 * 两个阶段的当前状态。
 *
 * 进度条只能画一个阶段：上传到 45% 时用户看到的只是一根条，分不清这是在打包还是在传，
 * 更不知道后面还有几步。两行都写出来，当前那一行用正文色、没到的那一行用次要色。
 *
 * 传输阶段没有字节可报时（源里没有可备的东西，这次没东西可传）只写"正在传输"，
 * 不写"0 B / 0 B"——那是这一整轮改动要消掉的那句"看着像卡死"。
 */
@Composable
internal fun stageLines(run: BackupRunState): List<StageLine> {
    val separator = BackupListFormatter.SEPARATOR
    val packingState = if (run.packing) packingLine(run) else stringResource(R.string.backup_stage_done)
    val transferState = when {
        run.packing -> stringResource(R.string.backup_stage_waiting)
        run.totalBytes <= 0L -> stringResource(R.string.backup_stage_transferring)
        else -> stringResource(R.string.backup_stage_transferring) + separator + transferLine(run)
    }
    return listOf(
        StageLine(
            text = stringResource(R.string.backup_progress_stage_line, 1, PROGRESS_STAGES, packingState),
            active = run.packing,
        ),
        StageLine(
            text = stringResource(R.string.backup_progress_stage_line, 2, PROGRESS_STAGES, transferState),
            active = !run.packing,
        ),
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
 * 打包阶段那一行。
 *
 * 这一段没有字节数可报（归档还在生成，总字节要到打包完才知道），所以报"第几个 / 共几个"。
 * 连条目数都还没有时（源刚被叫起来）说"正在准备"——总比"0 B / 0 B"强：后者看着像已经卡住了。
 */
@Composable
internal fun packingLine(run: BackupRunState): String =
    if (run.packedCount > 0) {
        stringResource(R.string.backup_progress_packing, run.packedIndex, run.packedCount)
    } else {
        stringResource(R.string.backup_progress_preparing)
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
