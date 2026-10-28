package com.sukisu.ultra.ui.screen.upload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.LocalUiMode
import com.sukisu.ultra.ui.UiMode
import com.sukisu.ultra.ui.component.material.ExpressiveDialog
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 「这一步没被接受，你得知道为什么」——一次只显示一条，点「知道了」才关。
 *
 * 为什么不用 Toast：这些文案里有**两个模块 id** 或**两个版本号**要对照着看，
 * 而 Toast 在窄屏上会被系统截断，且 3.5 秒后自己就走了 —— 用户既看不全，也来不及
 * 记下来该去哪改。对话框把同一句话放在一个不会被截的容器里，并且**必须由用户
 * 确认才消失**，用户可以在看清之前一直留着它。
 *
 * 只做单按钮：这不是「确认/取消」的选择题，包已经判为不合格，没有第二条路可走。
 */
@Composable
internal fun ModuleUploadNoticeDialog(notice: UploadNotice, onDismiss: () -> Unit) {
    val ok = stringResource(R.string.module_upload_notice_ok)
    when (LocalUiMode.current) {
        UiMode.Material -> ExpressiveDialog(
            onDismissRequest = onDismiss,
            title = { Text(notice.title) },
            text = { Text(notice.body) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(ok) }
            },
        )

        UiMode.Miuix -> WindowDialog(
            show = true,
            title = notice.title,
            onDismissRequest = onDismiss,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MiuixText(
                    text = notice.body,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                MiuixTextButton(
                    text = ok,
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
