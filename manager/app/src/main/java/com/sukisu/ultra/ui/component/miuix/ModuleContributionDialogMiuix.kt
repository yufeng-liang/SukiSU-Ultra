package com.sukisu.ultra.ui.component.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.component.ContributionForm
import com.sukisu.ultra.ui.component.ContributionSubmitState
import com.sukisu.ultra.ui.component.CoolapkUidState
import com.sukisu.ultra.ui.component.ModuleContributionRules
import com.sukisu.ultra.ui.util.ModulePropInfo
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * Miuix 风格的投稿对话框。
 *
 * 三段，与 Material 那套一一对应：**模块信息（只读）→ 出处与身份 → 确认勾选 → 提交**。
 * 只换控件、不换规则：能提交的条件（[ContributionForm.canSubmit]）只有一份，
 * 在 [com.sukisu.ultra.ui.component.ContributionForm] 里。
 *
 * 身份那一栏是「酷安 UID」而不是自由署名：用户填完 UID 后自动去服务端反查昵称，
 * 只有 [CoolapkUidState.Verified] 才放行提交（见 [UidStatusRow] 的四种回显）。
 *
 * [OverlayDialog] 的 `onDismissRequest` 在提交中被忽略（[busyDismiss]）：
 * zip 还在传的时候关掉对话框会让人以为"没传上去"，而实际上服务端可能已经收下。
 */
@Composable
fun ModuleContributionDialogMiuix(
    show: Boolean,
    form: ContributionForm,
    onSourceChange: (String) -> Unit,
    onUidChange: (String) -> Unit,
    onRetryLookup: () -> Unit,
    onConsentChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = stringResource(R.string.module_contribution_dialog_title),
        onDismissRequest = { if (!form.isSubmitting) onDismissRequest() },
        // 纵向留 24dp（内容区只补左右）：否则表单和底部按钮会贴住对话框上下边框。
        insideMargin = DpSize(0.dp, 24.dp),
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                form.meta?.let { meta ->
                    SectionLabel(stringResource(R.string.module_contribution_section_info))
                    // 只读信息放在一层浅底容器里：它是"这次要传上去的是什么"，
                    // 与下面两栏要用户填的东西视觉上分开，别混成一堆灰字。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colorScheme.surfaceContainer)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        MetaSection(meta = meta)
                    }
                }

                SectionLabel(stringResource(R.string.module_contribution_section_identity))

                TextField(
                    value = form.source,
                    onValueChange = onSourceChange,
                    enabled = !form.isSubmitting,
                    label = stringResource(R.string.module_contribution_source),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = form.uid,
                    // 只留数字：UID 就是一串数字，让字母压根进不来，比事后报错友好。
                    onValueChange = { raw -> onUidChange(raw.filter { it.isDigit() }.take(UID_MAX_LEN)) },
                    enabled = !form.isSubmitting,
                    label = stringResource(R.string.module_contribution_uid),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                UidStatusRow(form = form, onRetry = onRetryLookup)

                // 所有权口令：绑「模块 id + 整包 sha256」——换 id 或换包都换一串。
                // 整包哈希还在后台算的时候这一行不出现（判据在 ModuleContributionRules.showProofRow）。
                form.meta?.let { meta -> ProofRow(form = form, id = meta.id, zipSha = form.zipSha256) }

                // 确认说明：不勾就提交不了，且它永远在提交按钮上方
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
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

/** UID 输入框下面那一行回显：把"核实到哪一步了"如实写出来。 */
@Composable
private fun UidStatusRow(form: ContributionForm, onRetry: () -> Unit) {
    when (val state = form.uidState) {
        is CoolapkUidState.Verified -> StatusLine(
            icon = MiuixIcons.Ok,
            text = stringResource(R.string.module_contribution_uid_verified, state.nickname),
            color = colorScheme.primary,
        )

        CoolapkUidState.Checking -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(progress = null, size = 16.dp, strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.module_contribution_uid_checking),
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }

        CoolapkUidState.NotFound -> StatusLine(
            icon = MiuixIcons.Close,
            text = stringResource(R.string.module_contribution_uid_not_found),
            color = colorScheme.error,
        )

        // 「这次没查成」与「查无此人」必须分开说：前者重试就可能好，后者要用户去核对 UID。
        CoolapkUidState.Unavailable -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.module_contribution_uid_unavailable),
                fontSize = 13.sp,
                color = colorScheme.error,
            )
            TextButton(
                text = stringResource(R.string.network_retry),
                onClick = onRetry,
                enabled = !form.isSubmitting,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }

        CoolapkUidState.SelfReported -> Text(
            text = stringResource(R.string.module_contribution_uid_self_reported),
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )

        CoolapkUidState.Empty -> Text(
            text = if (form.uid.isNotBlank() && !form.uidLooksValid) {
                stringResource(R.string.module_contribution_uid_invalid)
            } else {
                stringResource(R.string.module_contribution_uid_hint)
            },
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
    }
}

/**
 * 所有权口令行：展示 `<sha256("<id>:<整包 sha256>") 前 8 位>-<nonce>`，一键复制去填酷安签名。
 *
 * 与 Material 那套同一条内容、同一套判据（[ModuleContributionRules.showProofRow]：模块 id 与
 * 整包 sha256 都齐了才显示）：口令现算自 [ContributionForm.proofText]、绑死 [id] 与 [zipSha]，
 * 换 id 或换包都跟着变。[zipSha] 必须就是提交时发出去的那个整包哈希，显示另一个等于喂给用户
 * 一串服务端认不出的口令。两套渲染器都要有它，只改一套等于另一套主题上这条所有权链断了。
 */
@Composable
private fun ProofRow(form: ContributionForm, id: String, zipSha: String) {
    if (!ModuleContributionRules.showProofRow(id, zipSha)) return
    val clipboard = LocalClipboardManager.current
    val proof = form.proofText(id, zipSha)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.module_contribution_proof_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurfaceVariantSummary,
            )
            TextButton(
                text = stringResource(R.string.module_contribution_proof_copy),
                onClick = { clipboard.setText(AnnotatedString(proof)) },
            )
        }
        Text(text = proof, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            text = stringResource(R.string.module_contribution_proof_hint),
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun StatusLine(icon: ImageVector, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            modifier = Modifier.size(16.dp),
            imageVector = icon,
            tint = color,
            contentDescription = null,
        )
        Text(text = text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        modifier = Modifier.padding(top = 2.dp, start = 2.dp),
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
private fun MetaSection(meta: ModulePropInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            color = if (emphasized) colorScheme.onSurface else colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 与 [com.sukisu.ultra.ui.util.ModuleContribution] 的 `\d{4,12}` 同一条约定。 */
private const val UID_MAX_LEN = 12
