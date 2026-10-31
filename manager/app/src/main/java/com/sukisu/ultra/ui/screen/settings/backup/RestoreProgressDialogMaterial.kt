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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.sukisu.ultra.R
import com.sukisu.ultra.data.repository.isSoftRebootPreferred
import com.sukisu.ultra.ui.component.rebootlistpopup.rememberRebootAction

/**
 * 恢复进度与结果（Material）。
 *
 * 与 Miuix 版同语义：跑到第几条、能取消、跑完说清结果。恢复是一串 root 操作，
 * 中途离开详情页不该让它变成"看不见的后台任务"——所以跑着的时候弹窗不可关，
 * 只有显式取消或等它跑完。
 */
@Composable
fun RestoreProgressDialogMaterial(
    run: RestoreRunState?,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
) {
    if (run == null) return
    // 重启方式跟闪刷页同一来源：偏好软重启就软重启（保留 root、照样加载模块）。
    // late-load 模式下普通重启会掉 root，rememberRebootAction 会先弹确认再动手。
    val onReboot = rememberRebootAction()
    // 弹窗打开期间定死一种重启方式：label 和点击用的是同一个值，不跟设置实时漂移。
    val softReboot = isSoftRebootPreferred()
    val rebootLabel = stringResource(if (softReboot) R.string.reboot_soft else R.string.reboot)
    AlertDialog(
        onDismissRequest = { if (run.done) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = run.done,
            dismissOnClickOutside = run.done,
        ),
        title = {
            Text(
                stringResource(
                    if (run.done) R.string.backup_restore_result_title else R.string.backup_restore_progress_title,
                ),
            )
        },
        text = {
            Column {
                if (run.done) {
                    // 取消不是"没发生过"：先说要紧的那句（已经生效的不回滚），再报数字。
                    if (run.cancelled) {
                        Text(
                            text = stringResource(R.string.backup_restore_cancelled),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (run.result.isNotBlank()) {
                        Text(
                            text = run.result,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = if (run.cancelled) 8.dp else 0.dp),
                        )
                    }
                    // 失败明细逐行列出：只写"1 项失败"等于让用户去别处找是哪一项。
                    if (run.failures.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.backup_result_failed),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        run.failures.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    // "恢复成功"只是文件到位：这句要跟着重启入口一起出现在眼前，
                    // 只躺在页面 snackbar 里时，弹窗一关就没人看见它。
                    if (run.needsReboot) {
                        Text(
                            text = stringResource(R.string.reboot_to_apply),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                } else {
                    Text(
                        text = stringResource(
                            R.string.backup_restore_progress_item,
                            run.current.coerceAtMost(run.total),
                            run.total,
                            run.label,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { if (run.total == 0) 0f else run.current.toFloat() / run.total },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            // 重启是恢复完成后的下一步动作，放主按钮位；不想要重启的用户用 Done 关掉。
            if (run.done && run.needsReboot) {
                TextButton(onClick = { onReboot(if (softReboot) "soft_reboot" else "") }) {
                    Text(rebootLabel)
                }
            }
            TextButton(onClick = onDismiss, enabled = run.done) {
                Text(stringResource(R.string.backup_done))
            }
        },
        dismissButton = {
            if (!run.done) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}
