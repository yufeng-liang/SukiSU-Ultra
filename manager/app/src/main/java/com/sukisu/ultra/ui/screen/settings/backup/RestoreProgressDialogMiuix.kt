package com.sukisu.ultra.ui.screen.settings.backup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.sukisu.ultra.R
import com.sukisu.ultra.data.repository.isSoftRebootPreferred
import com.sukisu.ultra.ui.component.rebootlistpopup.rememberRebootAction
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 恢复进度与结果（Miuix）。
 *
 * 恢复是一串 root 操作（重装模块、刷原厂镜像、整批替换仓库源），一条一条跑下来可能是几十秒。
 * 原来详情页只是把按钮灰掉：用户既看不出跑到第几条，也停不下来，而且返回键还能退出详情，
 * 让这串操作在后台继续跑。这里补上"第几条 / 共几条"、取消，以及跑完之后的实情——
 * 取消**不会**撤销已经生效的那几条，所以那句话必须说出来。
 */
@Composable
fun RestoreProgressDialogMiuix(
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
    WindowDialog(
        show = true,
        // 还在跑的时候返回键不该把它关掉：关掉就看不到进度，也没法取消。
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
                        if (run.done) R.string.backup_restore_result_title else R.string.backup_restore_progress_title,
                    ),
                )
                if (run.done) {
                    // 取消不是"没发生过"：先说要紧的那句（已经生效的不回滚），再报数字。
                    if (run.cancelled) {
                        Text(
                            text = stringResource(R.string.backup_restore_cancelled),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (run.result.isNotBlank()) {
                        Text(
                            text = run.result,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    // 失败明细逐行列出：只写"1 项失败"等于让用户去别处找是哪一项。
                    if (run.failures.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.backup_result_failed),
                            color = colorScheme.error,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        run.failures.forEach { line ->
                            Text(
                                text = line,
                                color = colorScheme.error,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    // "恢复成功"只是文件到位：这句要跟着重启入口一起出现在眼前，
                    // 只躺在页面 snackbar 里时，弹窗一关就没人看见它。
                    if (run.needsReboot) {
                        Text(
                            text = stringResource(R.string.reboot_to_apply),
                            color = colorScheme.primary,
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
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    LinearProgressIndicator(
                        progress = if (run.total == 0) null else run.current.toFloat() / run.total,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
                // 还在跑的时候给一条出路：窗口关不掉又停不下来，只能干等。
                if (!run.done) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        onClick = onCancel,
                    )
                }
                // 重启是恢复完成后的下一步动作，放 Done 上面；不想要重启的用户直接 Done 关掉。
                if (run.done && run.needsReboot) {
                    TextButton(
                        text = rebootLabel,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        onClick = { onReboot(if (softReboot) "soft_reboot" else "") },
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
