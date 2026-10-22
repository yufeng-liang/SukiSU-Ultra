package com.sukisu.ultra.ui.screen.upload

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.component.CoolapkUidState
import com.sukisu.ultra.ui.component.material.ExpressiveScaffold
import com.sukisu.ultra.ui.component.material.TonalCard
import com.sukisu.ultra.ui.component.material.TopBarBackButton
import com.sukisu.ultra.ui.component.material.expressiveTopAppBarColors
import com.sukisu.ultra.ui.util.BatchLimits
import kotlinx.coroutines.launch

/**
 * Material 风格的上传表单。与 [ModuleUploadFormMiuix] 同一份状态、同一套判据，
 * 只换控件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleUploadFormMaterial(
    state: ModuleUploadFormState,
    limits: BatchLimits?,
    onBack: () -> Unit,
    onRemove: (String) -> Unit,
    onSourceChange: (String, String) -> Unit,
    onApplySourceToAll: (String) -> Unit,
    onUidChange: (String) -> Unit,
    onRetryUid: () -> Unit,
    onConsentChange: (Boolean) -> Unit,
    onSignatureConfirmedChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val limitProblem = ModuleUploadRules.limitProblem(
        count = state.items.size,
        sizes = state.items.map { it.sizeBytes },
        limits = limits,
    )

    ExpressiveScaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.module_upload_form_title)) },
                navigationIcon = {
                    TopBarBackButton(onClick = onBack)
                },
                scrollBehavior = scrollBehavior,
                colors = expressiveTopAppBarColors(),
            )
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 16.dp),
            contentPadding = innerPadding,
        ) {
            item {
                Text(
                    modifier = Modifier.padding(top = 12.dp),
                    text = stringResource(R.string.module_upload_form_items_title, state.items.size),
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            items(state.items.size, key = { state.items[it].path }) { index ->
                val item = state.items[index]
                UploadZipCardMaterial(
                    item = item,
                    enabled = !state.submitting,
                    onRemove = { onRemove(item.path) },
                    onSourceChange = { onSourceChange(item.path, it) },
                    onApplyToAll = { onApplySourceToAll(item.source) },
                )
            }

            item {
                TonalCard(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionLabelMaterial(stringResource(R.string.module_upload_section_identity))
                        OutlinedTextField(
                            value = state.uid,
                            onValueChange = onUidChange,
                            enabled = !state.submitting,
                            label = { Text(stringResource(R.string.module_upload_uid)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        UidStatusRowMaterial(state = state, onRetry = onRetryUid)
                        Text(
                            text = stringResource(R.string.module_upload_uid_public),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.module_upload_uid_level_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                ProofSectionMaterial(
                    state = state,
                    enabled = !state.submitting,
                    onSignatureConfirmedChange = onSignatureConfirmedChange,
                )
            }

            item {
                TonalCard(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Checkbox(
                                checked = state.consent,
                                onCheckedChange = if (state.submitting) null else onConsentChange,
                                enabled = !state.submitting,
                            )
                            Text(
                                modifier = Modifier.padding(start = 8.dp),
                                text = stringResource(R.string.module_upload_consent),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 只差这一个勾时才提示：一打开表单就挂红字是噪音。
                        if (state.uidVerified && state.signatureValid && !state.consent) {
                            Text(
                                text = stringResource(R.string.module_upload_consent_required),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            if (limitProblem != ModuleUploadRules.LimitProblem.NONE) {
                item {
                    // 单枚 / 合计两档要把服务端自报的数字写进话术（§1.3），
                    // 不能只挂一句「超过上限」让用户自己猜差多少。
                    val limitText = when (limitProblem) {
                        ModuleUploadRules.LimitProblem.TOO_MANY -> stringResource(
                            R.string.module_upload_limit_too_many,
                            ModuleUploadRules.maxModules(limits),
                        )

                        ModuleUploadRules.LimitProblem.SINGLE_TOO_BIG -> stringResource(
                            R.string.module_upload_limit_single_too_big,
                            ModuleUploadRules.formatBytes(limits?.maxBytes ?: 0L),
                        )

                        ModuleUploadRules.LimitProblem.TOTAL_TOO_BIG -> stringResource(
                            R.string.module_upload_limit_total_too_big,
                            ModuleUploadRules.formatBytes(limits?.maxTotalBytes ?: 0L),
                        )

                        else -> stringResource(R.string.module_upload_limit_unknown)
                    }
                    Text(
                        modifier = Modifier.padding(top = 10.dp),
                        text = limitText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            item {
                SubmitSectionMaterial(
                    state = state,
                    canSubmit = state.canSubmit(limits),
                    onSubmit = onSubmit,
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun UploadZipCardMaterial(
    item: UploadZip,
    enabled: Boolean,
    onRemove: () -> Unit,
    onSourceChange: (String) -> Unit,
    onApplyToAll: () -> Unit,
) {
    val problem = ModuleUploadRules.sourceProblem(item.source)
    TonalCard(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onRemove, enabled = enabled) {
                    Text(stringResource(R.string.module_upload_remove))
                }
            }

            SectionLabelMaterial(stringResource(R.string.module_upload_section_info))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                InfoLineMaterial(stringResource(R.string.module_contribution_id), item.id)
                InfoLineMaterial(
                    stringResource(R.string.module_contribution_version),
                    "${item.version} (${item.versionCode})",
                )
                InfoLineMaterial(stringResource(R.string.module_contribution_name), item.name)
                if (item.author.isNotBlank()) {
                    InfoLineMaterial(stringResource(R.string.module_contribution_author), item.author)
                }
                if (item.description.isNotBlank()) {
                    InfoLineMaterial(
                        stringResource(R.string.module_contribution_description),
                        item.description,
                    )
                }
                InfoLineMaterial(
                    stringResource(R.string.module_upload_size),
                    ModuleUploadRules.formatBytes(item.sizeBytes),
                )
                InfoLineMaterial(stringResource(R.string.module_upload_sha), item.sha256, mono = true)
                if (item.checking) {
                    Text(
                        modifier = Modifier.padding(top = 6.dp),
                        text = stringResource(R.string.module_upload_checking),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (item.status != null) {
                    val copy = ModuleUploadRules.statusCopy(item.status)
                    Text(
                        modifier = Modifier.padding(top = 6.dp),
                        text = stringResource(copy.title) + " · " + stringResource(copy.summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.status == com.sukisu.ultra.ui.util.CheckStatus.RECORDED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            SectionLabelMaterial(stringResource(R.string.module_upload_section_source))
            OutlinedTextField(
                value = item.source,
                onValueChange = onSourceChange,
                enabled = enabled,
                label = { Text(stringResource(R.string.module_upload_source)) },
                singleLine = true,
                isError = problem == ModuleUploadRules.SourceProblem.NOT_COOLAPK,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(
                        when (problem) {
                            ModuleUploadRules.SourceProblem.EMPTY -> R.string.module_upload_source_empty
                            ModuleUploadRules.SourceProblem.NOT_COOLAPK -> R.string.module_upload_source_invalid
                            ModuleUploadRules.SourceProblem.OK -> R.string.module_upload_source_hint
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (problem == ModuleUploadRules.SourceProblem.OK) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                TextButton(onClick = onApplyToAll, enabled = enabled && item.source.isNotBlank()) {
                    Text(stringResource(R.string.module_upload_apply_all))
                }
            }
        }
    }
}

@Composable
private fun ProofSectionMaterial(
    state: ModuleUploadFormState,
    enabled: Boolean,
    onSignatureConfirmedChange: (Boolean) -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val proof = state.proof
    TonalCard(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.module_upload_proof_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (proof != null) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("proof", proof.text)))
                            }
                        },
                    ) {
                        Text(stringResource(R.string.module_upload_proof_copy))
                    }
                }
            }

            if (proof == null) {
                // 只要还有一个包的 sha 没算完，就一行口令都不显示。
                Text(
                    text = stringResource(R.string.module_upload_proof_pending),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = proof.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.module_upload_proof_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(
                        checked = state.signatureValid,
                        onCheckedChange = { onSignatureConfirmedChange(it) },
                        enabled = enabled,
                    )
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = stringResource(R.string.module_upload_proof_confirm),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (state.confirmedSetKey != null && !state.signatureValid) {
                    Text(
                        text = stringResource(R.string.module_upload_proof_changed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SubmitSectionMaterial(
    state: ModuleUploadFormState,
    canSubmit: Boolean,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.submitting) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                Column {
                    Text(
                        text = stringResource(R.string.module_upload_submitting),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // 契约没有字节回调：只报总数与不确定进度，不编一个百分比。
                    Text(
                        text = stringResource(
                            R.string.module_upload_submitting_bytes,
                            state.items.size,
                            ModuleUploadRules.formatBytes(state.totalBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        when (val outcome = state.outcome) {
            is UploadOutcome.Failure -> Text(
                text = outcome.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

            is UploadOutcome.Success -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = outcome.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                outcome.prUrl?.let { url ->
                    Text(
                        text = stringResource(R.string.module_upload_result_pr, url),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            null -> Unit
        }

        Button(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            onClick = onSubmit,
            enabled = canSubmit,
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudUpload,
                contentDescription = null,
            )
            Text(
                modifier = Modifier.padding(start = 6.dp),
                text = stringResource(R.string.module_upload_submit),
            )
        }
    }
}

@Composable
private fun UidStatusRowMaterial(state: ModuleUploadFormState, onRetry: () -> Unit) {
    when (val uidState = state.uidState) {
        is CoolapkUidState.Verified -> Text(
            text = stringResource(R.string.module_upload_uid_verified, uidState.nickname),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )

        CoolapkUidState.Checking -> Text(
            text = stringResource(R.string.module_upload_uid_checking),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        CoolapkUidState.NotFound -> Text(
            text = stringResource(R.string.module_upload_uid_not_found),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        CoolapkUidState.Unavailable -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.module_upload_uid_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRetry, enabled = !state.submitting) {
                Icon(
                    modifier = Modifier.height(16.dp),
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                )
                Text(
                    modifier = Modifier.padding(start = 4.dp),
                    text = stringResource(R.string.network_retry),
                )
            }
        }

        CoolapkUidState.SelfReported -> Text(
            text = stringResource(R.string.module_upload_uid_self_reported),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        CoolapkUidState.Empty -> Text(
            text = if (state.uid.isNotBlank()) {
                stringResource(R.string.module_upload_uid_invalid)
            } else {
                stringResource(R.string.module_upload_uid_hint)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabelMaterial(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InfoLineMaterial(label: String, value: String, mono: Boolean = false) {
    Row {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            modifier = if (mono) Modifier.weight(1f) else Modifier,
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            maxLines = if (mono) 1 else 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
