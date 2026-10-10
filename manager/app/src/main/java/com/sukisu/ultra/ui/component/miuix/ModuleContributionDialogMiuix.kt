package com.sukisu.ultra.ui.component.miuix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.component.ContributionForm
import com.sukisu.ultra.ui.component.ContributionSubmitState
import com.sukisu.ultra.ui.util.ModulePropInfo
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * Miuix 风格的投稿对话框。
 *
 * 结构与 Material 那套一一对应（只读信息 → 两个必填输入 → 确认勾选 → 提交按钮），
 * 只换控件：确认勾选在滚动区**之内但排在提交按钮正上方**，任何屏高下都要划过它才能提交。
 *
 * [OverlayDialog] 的 `onDismissRequest` 在提交中被忽略（[busyDismiss]）：
 * zip 还在传的时候关掉对话框会让人以为"没传上去"，而实际上服务端可能已经收下。
 */
@Composable
fun ModuleContributionDialogMiuix(
    show: Boolean,
    form: ContributionForm,
    onSourceChange: (String) -> Unit,
    onUploaderChange: (String) -> Unit,
    onConsentChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = stringResource(R.string.module_contribution_dialog_title),
        onDismissRequest = { if (!form.isSubmitting) onDismissRequest() },
        insideMargin = DpSize(0.dp, 0.dp),
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val meta = form.meta
                if (meta != null) {
                    MetaSection(meta = meta)
                    HorizontalDivider(thickness = 0.5.dp, color = colorScheme.outline.copy(alpha = 0.5f))
                }

                TextField(
                    value = form.source,
                    onValueChange = onSourceChange,
                    enabled = !form.isSubmitting,
                    label = stringResource(R.string.module_contribution_source),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = form.uploader,
                    onValueChange = onUploaderChange,
                    enabled = !form.isSubmitting,
                    label = stringResource(R.string.module_contribution_uploader),
                    modifier = Modifier.fillMaxWidth(),
                )

                // 确认说明：不勾就提交不了，且它永远在提交按钮上方
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Checkbox(
                        state = if (form.consentChecked) ToggleableState.On else ToggleableState.Off,
                        onClick = { if (!form.isSubmitting) onConsentChange(!form.consentChecked) },
                        enabled = !form.isSubmitting,
                    )
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = stringResource(R.string.module_contribution_consent),
                        fontSize = 13.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
                if (form.consentMissing) {
                    Text(
                        text = stringResource(R.string.module_contribution_consent_required),
                        fontSize = 13.sp,
                        color = colorScheme.error,
                    )
                }

                when (val state = form.submitState) {
                    is ContributionSubmitState.Submitting -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // progress 为 null = 不确定进度：zip 上传没有可靠的字节进度可报，
                            // 报一个假的百分比反而比转圈更像卡住。
                            CircularProgressIndicator(
                                progress = null,
                                size = 20.dp,
                                strokeWidth = 2.dp,
                            )
                            Text(
                                modifier = Modifier.padding(start = 10.dp),
                                text = stringResource(R.string.module_contribution_submitting),
                                fontSize = 14.sp,
                            )
                        }
                    }

                    is ContributionSubmitState.Failed -> {
                        // 服务端/网络层给的原因优先——那是唯一能告诉用户"哪里不合格"的信息。
                        Text(
                            text = state.message.ifBlank {
                                stringResource(R.string.module_contribution_failed)
                            },
                            fontSize = 14.sp,
                            color = colorScheme.error,
                        )
                    }

                    is ContributionSubmitState.Success -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = state.message.ifBlank {
                                    stringResource(R.string.module_contribution_success)
                                },
                                fontSize = 14.sp,
                                color = colorScheme.primary,
                            )
                            state.prUrl?.let {
                                Text(
                                    text = it,
                                    fontSize = 12.sp,
                                    color = colorScheme.primary,
                                )
                            }
                        }
                    }

                    ContributionSubmitState.Idle -> Unit
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = onDismissRequest,
                        enabled = !form.isSubmitting,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(
                            if (form.isFailed) {
                                R.string.network_retry
                            } else {
                                R.string.module_contribution_submit
                            }
                        ),
                        onClick = onSubmit,
                        enabled = form.canSubmit,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    )
}

@Composable
private fun MetaSection(meta: ModulePropInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MetaLine(
            label = stringResource(R.string.module_contribution_name),
            value = meta.name,
            emphasized = true,
        )
        MetaLine(label = stringResource(R.string.module_contribution_id), value = meta.id)
        MetaLine(
            label = stringResource(R.string.module_contribution_version),
            value = "${meta.version} (${meta.versionCode})",
        )
        if (meta.author.isNotBlank()) {
            MetaLine(label = stringResource(R.string.module_contribution_author), value = meta.author)
        }
        if (meta.description.isNotBlank()) {
            MetaLine(
                label = stringResource(R.string.module_contribution_description),
                value = meta.description,
            )
        }
    }
}

@Composable
private fun MetaLine(label: String, value: String, emphasized: Boolean = false) {
    Row(
        modifier = Modifier.padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "$label: ",
            fontSize = if (emphasized) 15.sp else 13.sp,
            fontWeight = if (emphasized) FontWeight.Medium else FontWeight.Normal,
            color = colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            fontSize = if (emphasized) 15.sp else 13.sp,
            fontWeight = if (emphasized) FontWeight.Medium else FontWeight.Normal,
            color = colorScheme.onSurfaceVariantSummary,
        )
    }
}
