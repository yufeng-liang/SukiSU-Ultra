package com.sukisu.ultra.ui.screen.upload

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
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
    onSelectItem: (String) -> Unit,
    onPickMore: () -> Unit,
    onSignOut: () -> Unit,
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
    val canSubmit = state.canSubmit(limits)
    val blockers = remainingBlockers(state, limitProblem)
    val ownershipBlock = ModuleUploadRules.ownershipBlockReason(state.items)

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
        // 提交按钮钉在底部：这一屏有四张卡，滚到底才能提交的旧做法让人白滚一趟。
        bottomBar = {
            SubmitBarMaterial(
                canSubmit = canSubmit,
                submitting = state.submitting,
                blockers = blockers,
                ownershipBlock = ownershipBlock,
                onSubmit = onSubmit,
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
                BatchSummaryMaterial(
                    state = state,
                    canSubmit = canSubmit,
                    blockers = blockers,
                    ownershipBlock = ownershipBlock,
                )
            }

            item {
                // 墙：这一批每一枚一张 zip 图标卡，横向滑、点哪张编辑哪张。
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    // 墙自身宽度固定，靠外层 Row 摆到中间 —— 否则左侧贴边、右边空一大片。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        UploadZipWallMaterial(
                            items = state.items,
                            selectedPath = state.selectedPath,
                            enabled = !state.submitting,
                            onSelect = onSelectItem,
                            onPickMore = onPickMore,
                        )
                    }
                    val index = state.items.indexOfFirst { it.path == state.selectedPath }
                    if (state.items.size > 1) {
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            text = stringResource(
                                R.string.module_upload_wall_editing,
                                if (index >= 0) index + 1 else 1,
                                state.items.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }

            val current = state.items.firstOrNull { it.path == state.selectedPath }
                ?: state.items.firstOrNull()
            if (current != null) {
                item(key = "detail-" + current.path) {
                    UploadZipCardMaterial(
                        item = current,
                        enabled = !state.submitting,
                        onRemove = { onRemove(current.path) },
                        onSourceChange = { onSourceChange(current.path, it) },
                        onApplyToAll = { onApplySourceToAll(current.source) },
                    )
                }
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
                        SectionLabelMaterial(
                            icon = Icons.Outlined.Info,
                            text = stringResource(R.string.module_upload_section_identity),
                        )
                        OutlinedTextField(
                            value = state.uid,
                            onValueChange = onUidChange,
                            enabled = !state.submitting,
                            label = { Text(stringResource(R.string.module_upload_uid)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        UidStatusRowMaterial(
                            state = state,
                            onRetry = onRetryUid,
                            onSignOut = onSignOut,
                        )
                        if (state.sessionMatchesUid) {
                            Text(
                                text = stringResource(R.string.module_upload_session_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                    // 整行可点：勾选框在左上、文字占三行时，只点那个小方块太窄。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.submitting) { onConsentChange(!state.consent) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Checkbox(
                            checked = state.consent,
                            onCheckedChange = null,
                            enabled = !state.submitting,
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.module_upload_consent),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // 只差这一个勾时才提示：一打开表单就挂红字是噪音。
                            if (state.uidVerified &&
                                (state.signatureValid || state.sessionMatchesUid) &&
                                !state.consent
                            ) {
                                Text(
                                    text = stringResource(R.string.module_upload_consent_required),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
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

            if (state.submitting || state.outcome != null) {
                item { OutcomeMaterial(state) }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * 还差几项才能提交。只数「用户还得动手」的项，判据与 [ModuleUploadRules.canSubmit] 一一对应，
 * 不新增规则 —— 这里只负责把「差什么」说清楚。
 *
 * 归属闸【不】计入这里：那一条用户动手也解决不了（模块已经记在别人名下），把它算进
 * 「还差 N 项」等于派给他一件做不完的事。那一类由 [ModuleUploadRules.ownershipBlockReason]
 * 单独出一句原话，界面上优先显示它。
 */
private fun remainingBlockers(
    state: ModuleUploadFormState,
    limitProblem: ModuleUploadRules.LimitProblem,
): Int {
    var count = 0
    if (!state.uidVerified) count++
    if (!state.sessionMatchesUid && !state.signatureValid) count++
    if (!state.consent) count++
    if (limitProblem != ModuleUploadRules.LimitProblem.NONE) count++
    if (state.items.any { ModuleUploadRules.sourceProblem(it.source) != ModuleUploadRules.SourceProblem.OK }) {
        count++
    }
    return count
}

/** 顶部汇总：几枚、多大、能不能传 —— 一进屏就知道，不用滚到底。 */
@Composable
private fun BatchSummaryMaterial(
    state: ModuleUploadFormState,
    canSubmit: Boolean,
    blockers: Int,
    ownershipBlock: String,
) {
    val accent = if (canSubmit) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    TonalCard(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                modifier = Modifier.size(20.dp),
                imageVector = Icons.Outlined.FolderZip,
                contentDescription = null,
                tint = accent,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.module_upload_summary_count,
                        state.items.size,
                        ModuleUploadRules.formatBytes(state.totalBytes),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = submitHint(canSubmit, blockers, ownershipBlock),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ownershipBlock.isNotEmpty()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        accent
                    },
                )
            }
        }
    }
}

/** 底部常驻提交栏：按钮永远在手边，左边一句说清能不能传。 */
@Composable
private fun SubmitBarMaterial(
    canSubmit: Boolean,
    submitting: Boolean,
    blockers: Int,
    ownershipBlock: String,
    onSubmit: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = submitHint(canSubmit, blockers, ownershipBlock),
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    ownershipBlock.isNotEmpty() -> MaterialTheme.colorScheme.error
                    canSubmit -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Button(
                modifier = Modifier.height(48.dp),
                onClick = onSubmit,
                enabled = canSubmit,
            ) {
                if (submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.CloudUpload,
                        contentDescription = null,
                    )
                }
                Text(
                    modifier = Modifier.padding(start = 6.dp),
                    text = stringResource(R.string.module_upload_submit),
                )
            }
        }
    }
}

/** 提交中 / 出结果后的那一段。契约没有字节回调：只报总数，不编百分比。 */
@Composable
private fun OutcomeMaterial(state: ModuleUploadFormState) {
    Column(
        modifier = Modifier.padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.submitting) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Column {
                    Text(
                        text = stringResource(R.string.module_upload_submitting),
                        style = MaterialTheme.typography.bodyMedium,
                    )
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

            SectionLabelMaterial(
                icon = Icons.Outlined.Description,
                text = stringResource(R.string.module_upload_section_info),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 标签列固定 76dp：七个字段的取值从同一条竖线起，不再参差。
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
                        modifier = Modifier.padding(top = 4.dp),
                        text = stringResource(R.string.module_upload_checking),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (item.status != null) {
                    val copy = ModuleUploadRules.statusCopy(item.status)
                    Text(
                        modifier = Modifier.padding(top = 4.dp),
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

            SectionLabelMaterial(
                icon = Icons.Outlined.Link,
                text = stringResource(R.string.module_upload_section_source),
            )
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
    // 走长期令牌的这一次上传不需要改签名 —— 整段收起，别让人白抄一次。
    if (state.sessionMatchesUid) return
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabelMaterial(
                icon = Icons.Filled.VerifiedUser,
                text = stringResource(R.string.module_upload_proof_title),
            )

            if (proof == null) {
                // 只要还有一个包的 sha 没算完，就一行口令都不显示。
                Text(
                    text = stringResource(R.string.module_upload_proof_pending),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // 这一串是要拿去抄进酷安签名的东西，给它一个「可以复制的东西」的容器，
                // 而不是一行裸文本旁边挂个远处的按钮。
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            modifier = Modifier.weight(1f),
                            text = proof.text,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(ClipData.newPlainText("proof", proof.text)),
                                    )
                                }
                            },
                        ) {
                            Text(stringResource(R.string.module_upload_proof_copy))
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.module_upload_proof_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { onSignatureConfirmedChange(!state.signatureValid) },
                    verticalAlignment = Alignment.Top,
                ) {
                    Checkbox(
                        checked = state.signatureValid,
                        onCheckedChange = null,
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
private fun UidStatusRowMaterial(
    state: ModuleUploadFormState,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    when (val uidState = state.uidState) {
        // 已验证：头像 + 勾选描边动画 + 昵称，右侧给「退出验证」（只有真握着令牌时才给）。
        is CoolapkUidState.Verified -> Row(verticalAlignment = Alignment.CenterVertically) {
            VerifiedAvatar(
                avatarUrl = uidState.avatar.ifBlank { state.session?.avatar.orEmpty() },
                tint = MaterialTheme.colorScheme.primary,
                placeholder = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Text(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
                text = stringResource(R.string.module_upload_uid_verified, uidState.nickname),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (state.sessionActive) {
                TextButton(onClick = onSignOut, enabled = !state.submitting) {
                    Text(stringResource(R.string.module_upload_session_signout))
                }
            }
        }

        CoolapkUidState.Checking -> Text(
            text = stringResource(
                if (state.sessionChecking) {
                    R.string.module_upload_session_checking
                } else {
                    R.string.module_upload_uid_checking
                },
            ),
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
private fun SectionLabelMaterial(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            modifier = Modifier.size(14.dp),
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 一行 `标签 / 取值`：标签列定宽，取值从同一条竖线起。 */
@Composable
private fun InfoLineMaterial(label: String, value: String, mono: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            modifier = Modifier.width(76.dp),
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            modifier = Modifier.weight(1f),
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            maxLines = if (mono) 1 else 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
