package com.sukisu.ultra.ui.screen.settings.backup

import androidx.compose.foundation.layout.Column
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
fun BackupProgressDialogMiuix(state: BackupUiState, onDismiss: () -> Unit) {
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
                    Text(
                        text = run.result.orEmpty(),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
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
                        progress = run.fraction,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    Text(
                        text = transferLine(run),
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp),
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
