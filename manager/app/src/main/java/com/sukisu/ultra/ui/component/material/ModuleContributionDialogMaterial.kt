package com.sukisu.ultra.ui.component.material

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.component.ContributionForm
import com.sukisu.ultra.ui.component.ContributionSubmitState
import com.sukisu.ultra.ui.component.CoolapkUidState
import com.sukisu.ultra.ui.util.ModulePropInfo

/**
 * Material 风格的投稿对话框。
 *
 * 三段式，对应契约里"一屏不可跳过的确认说明"：
 * ① 只读的模块信息（[ModulePropInfo] 解析出来预填，用户不用手抄）；
 * ② 出处 + **酷安 UID**：UID 填完自动去服务端反查昵称，核实通过才放行提交；
 * ③ 确认说明 + 勾选框——没勾上时 [onSubmit] 不可能被触发：按钮 disabled，
 *    且 [ContributionForm.canSubmit] 在状态层再挡一道。
 *
 * 用 [BasicAlertDialog] + [Surface] 而不是 `AlertDialog(onDismissRequest=…)`：
 * 后者在各版本 material3 上的具名参数集合会变，而投稿表单内容很长、必须自己
 * 控制滚动容器与按钮排布，套它的 title/text/confirmButton 槽位反而是负担。
 *
 * 提交中（[ContributionForm.isSubmitting]）禁用一切输入：zip 上传可能几十 MB，
 * 期间改字段会让"看到的表单"和"实际传上去的元数据"对不上。
 */
@Composable
fun ModuleContributionDialogMaterial(
    show: Boolean,
    form: ContributionForm,
    onSourceChange: (String) -> Unit,
    onUidChange: (String) -> Unit,
    onRetryLookup: () -> Unit,
    onConsentChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    if (!show) return
    BasicAlertDialog(
        // zip 还在传的时候不许点外面关掉：关掉会让人以为没传上去，而服务端可能已经收下。
        onDismissRequest = { if (!form.isSubmitting) onDismissRequest() },
        properties = DialogProperties(dismissOnBackPress = !form.isSubmitting),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    text = stringResource(R.string.module_contribution_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val meta = form.meta
                    if (meta != null) {
                        SectionLabel(stringResource(R.string.module_contribution_section_info))
                        // 只读信息放进一层浅底容器：与下面要用户填的两栏在视觉上分开。
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            MetaSection(meta = meta)
                        }
                    }

                    SectionLabel(stringResource(R.string.module_contribution_section_identity))

                    OutlinedTextField(
                        value = form.source,
                        onValueChange = onSourceChange,
                        enabled = !form.isSubmitting,
                        label = { Text(stringResource(R.string.module_contribution_source)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = form.uid,
                        // 只留数字：UID 就是一串数字，让字母压根进不来，比事后报错友好。
                        onValueChange = { raw -> onUidChange(raw.filter { it.isDigit() }.take(UID_MAX_LEN)) },
                        enabled = !form.isSubmitting,
                        label = { Text(stringResource(R.string.module_contribution_uid)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        trailingIcon = { UidTrailingIcon(form.uidState) },
                    )

                    UidStatusRow(form = form, onRetry = onRetryLookup)

                    // 所有权口令：只在读得出模块 id 时显示——口令绑 id，id 变了口令跟着变。
                    meta?.let { ProofRow(form = form, id = it.id) }

                    // 确认说明：勾选项排在提交按钮正上方，不划过它就点不到提交
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Checkbox(
                            checked = form.consentChecked,
                            onCheckedChange = if (form.isSubmitting) null else onConsentChange,
                            enabled = !form.isSubmitting,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            modifier = Modifier.padding(start = 8.dp),
                            text = stringResource(R.string.module_contribution_consent),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (form.consentMissing) {
                        Text(
                            text = stringResource(R.string.module_contribution_consent_required),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    SubmitStateBlock(form.submitState)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onDismissRequest,
                        enabled = !form.isSubmitting,
                    ) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    SpacerWidth(12.dp)
                    Button(
                        onClick = onSubmit,
                        enabled = form.canSubmit,
                    ) {
                        Icon(
                            modifier = Modifier.size(18.dp),
                            imageVector = Icons.Outlined.CloudUpload,
                            contentDescription = null,
                        )
                        Text(
                            modifier = Modifier.padding(start = 6.dp),
                            text = stringResource(
                                if (form.isFailed) {
                                    R.string.network_retry
                                } else {
                                    R.string.module_contribution_submit
                                }
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** UID 输入框尾部的状态指示：核实中转圈、核实通过打勾，其余不占位。 */
@Composable
private fun UidTrailingIcon(state: CoolapkUidState) {
    when (state) {
        CoolapkUidState.Checking -> CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
        )

        is CoolapkUidState.Verified -> Icon(
            modifier = Modifier.size(20.dp),
            imageVector = Icons.Filled.Check,
            tint = MaterialTheme.colorScheme.primary,
            contentDescription = null,
        )

        CoolapkUidState.NotFound -> Icon(
            modifier = Modifier.size(20.dp),
            imageVector = Icons.Filled.Close,
            tint = MaterialTheme.colorScheme.error,
            contentDescription = null,
        )

        else -> Unit
    }
}

/** UID 输入框下面那一行回显：把"核实到哪一步了"如实写出来。 */
@Composable
private fun UidStatusRow(form: ContributionForm, onRetry: () -> Unit) {
    when (val state = form.uidState) {
        is CoolapkUidState.Verified -> StatusLine(
            icon = Icons.Filled.Check,
            text = stringResource(R.string.module_contribution_uid_verified, state.nickname),
            color = MaterialTheme.colorScheme.primary,
        )

        CoolapkUidState.Checking -> Text(
            text = stringResource(R.string.module_contribution_uid_checking),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        CoolapkUidState.NotFound -> StatusLine(
            icon = Icons.Filled.Close,
            text = stringResource(R.string.module_contribution_uid_not_found),
            color = MaterialTheme.colorScheme.error,
        )

        // 「这次没查成」与「查无此人」必须分开说：前者重试就可能好，后者要用户去核对 UID。
        CoolapkUidState.Unavailable -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.module_contribution_uid_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRetry, enabled = !form.isSubmitting) {
                Icon(
                    modifier = Modifier.size(16.dp),
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                )
                Text(
                    modifier = Modifier.padding(start = 4.dp),
                    text = stringResource(R.string.network_retry),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        CoolapkUidState.SelfReported -> Text(
            text = stringResource(R.string.module_contribution_uid_self_reported),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        CoolapkUidState.Empty -> Text(
            text = if (form.uid.isNotBlank() && !form.uidLooksValid) {
                stringResource(R.string.module_contribution_uid_invalid)
            } else {
                stringResource(R.string.module_contribution_uid_hint)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 所有权口令行：展示 `<sha256(模块 id) 前 8 位>-<nonce>`，一键复制去填酷安签名。
 *
 * 口令现算自 [ContributionForm.proofText]、与 [id] 绑死——换模块 id 它跟着变，
 * 服务端据此确认这个酷安账号归投稿人本人。只在读得出模块 id 时由调用方显示。
 */
@Composable
private fun ProofRow(form: ContributionForm, id: String) {
    val clipboard = LocalClipboardManager.current
    val proof = form.proofText(id)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.module_contribution_proof_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(proof)) }) {
                Text(stringResource(R.string.module_contribution_proof_copy))
            }
        }
        Text(
            text = proof,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(R.string.module_contribution_proof_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusLine(icon: ImageVector, text: String, color: Color) {
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
        Text(text = text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = color)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        modifier = Modifier.padding(start = 2.dp, top = 2.dp),
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 提交进度 / 成功（带 PR 链接）/ 失败（带原因）三种结果。 */
@Composable
private fun SubmitStateBlock(state: ContributionSubmitState) {
    when (state) {
        is ContributionSubmitState.Submitting -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    modifier = Modifier.padding(start = 10.dp),
                    text = stringResource(R.string.module_contribution_submitting),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        is ContributionSubmitState.Failed -> {
            // 服务端/网络层给的原因优先——那是唯一能告诉用户"哪里不合格"的信息。
            Text(
                text = state.message.ifBlank {
                    stringResource(R.string.module_contribution_failed)
                },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        is ContributionSubmitState.Success -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    // 服务端给了原因/说明就用它的，没有就落到我们自己的那句。
                    text = state.message.ifBlank {
                        stringResource(R.string.module_contribution_success)
                    },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.prUrl?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        ContributionSubmitState.Idle -> Unit
    }
}

/**
 * 只读的模块信息段。
 *
 * 全部来自 [ModulePropInfo]，不给编辑框：索引里的 id/name/version 必须与 zip 里的
 * module.prop 一致，让用户手改就等着人工审核被打回。
 */
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
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            text = value,
            style = if (emphasized) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SpacerWidth(width: Dp) {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(width))
}

/** 与 [com.sukisu.ultra.ui.util.ModuleContribution] 的 `\d{4,12}` 同一条约定。 */
private const val UID_MAX_LEN = 12
