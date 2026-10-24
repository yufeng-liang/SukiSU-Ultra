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
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.component.CoolapkUidState
import com.sukisu.ultra.ui.theme.LocalEnableBlur
import com.sukisu.ultra.ui.util.BatchLimits
import kotlinx.coroutines.launch
import com.sukisu.ultra.ui.util.BlurredBar
import com.sukisu.ultra.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Miuix 风格的上传表单：每个 zip 一张卡（只读信息 + 出处）、一个 UID、一句确认、
 * 一串整批共用的口令，最后才是提交。
 *
 * 所有「能不能提交」的判断都在 [ModuleUploadRules.canSubmit]，这里只把它的输入画出来。
 * 提交期间整页只读：上传可能持续几十秒，中途改字段会让屏幕上的表单与实际传上去的
 * 元数据对不上。
 */
@Composable
fun ModuleUploadFormMiuix(
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
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    val limitProblem = ModuleUploadRules.limitProblem(
        count = state.items.size,
        sizes = state.items.map { it.sizeBytes },
        limits = limits,
    )

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.module_upload_form_title),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            val layoutDirection = LocalLayoutDirection.current
                            Icon(
                                modifier = Modifier.graphicsLayer {
                                    if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                },
                                imageVector = MiuixIcons.Back,
                                contentDescription = null,
                            )
                        }
                    },
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
            overscrollEffect = null,
        ) {
            item {
                // 墙：这一批每一枚一张 zip 图标卡，横向滑、点哪张编辑哪张。
                // 只占一行高度 —— 十枚包也不会把表单撑成十几屏。
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    UploadZipWallMiuix(
                        items = state.items,
                        selectedPath = state.selectedPath,
                        enabled = !state.submitting,
                        onSelect = onSelectItem,
                        onPickMore = onPickMore,
                    )
                    val index = state.items.indexOfFirst { it.path == state.selectedPath }
                    if (state.items.size > 1) {
                        Text(
                            modifier = Modifier.padding(top = 8.dp),
                            text = stringResource(
                                R.string.module_upload_wall_editing,
                                if (index >= 0) index + 1 else 1,
                                state.items.size,
                            ),
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            // 舞台上只有当前这一枚：改出处只可能改它，所以不给「应用到全部」以外的批处理动作。
            val current = state.items.firstOrNull { it.path == state.selectedPath }
                ?: state.items.firstOrNull()
            if (current != null) {
                item(key = "detail-" + current.path) {
                    UploadZipCardMiuix(
                        item = current,
                        enabled = !state.submitting,
                        onRemove = { onRemove(current.path) },
                        onSourceChange = { onSourceChange(current.path, it) },
                        onApplyToAll = { onApplySourceToAll(current.source) },
                    )
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionLabelMiuix(stringResource(R.string.module_upload_section_identity))
                        TextField(
                            value = state.uid,
                            onValueChange = onUidChange,
                            enabled = !state.submitting,
                            label = stringResource(R.string.module_upload_uid),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        UidStatusRowMiuix(
                            state = state,
                            onRetry = onRetryUid,
                            onSignOut = onSignOut,
                        )
                        if (state.sessionMatchesUid) {
                            Text(
                                text = stringResource(R.string.module_upload_session_hint),
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Text(
                            text = stringResource(R.string.module_upload_uid_public),
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = stringResource(R.string.module_upload_uid_level_hint),
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            item {
                ProofSectionMiuix(
                    state = state,
                    enabled = !state.submitting,
                    onSignatureConfirmedChange = onSignatureConfirmedChange,
                )
            }

            item {
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Checkbox(
                                state = if (state.consent) {
                                    androidx.compose.ui.state.ToggleableState.On
                                } else {
                                    androidx.compose.ui.state.ToggleableState.Off
                                },
                                onClick = { if (!state.submitting) onConsentChange(!state.consent) },
                                enabled = !state.submitting,
                            )
                            Text(
                                modifier = Modifier.padding(start = 8.dp),
                                text = stringResource(R.string.module_upload_consent),
                                fontSize = 13.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        // 只差这一个勾时才提示：一打开表单就挂红字是噪音。
                        if (state.uidVerified && (state.signatureValid || state.sessionMatchesUid) && !state.consent) {
                            Text(
                                text = stringResource(R.string.module_upload_consent_required),
                                fontSize = 12.sp,
                                color = colorScheme.error,
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
                        fontSize = 12.sp,
                        color = colorScheme.error,
                    )
                }
            }

            item {
                SubmitSectionMiuix(
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
private fun UploadZipCardMiuix(
    item: UploadZip,
    enabled: Boolean,
    onRemove: () -> Unit,
    onSourceChange: (String) -> Unit,
    onApplyToAll: () -> Unit,
) {
    val problem = ModuleUploadRules.sourceProblem(item.source)
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = item.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    text = stringResource(R.string.module_upload_remove),
                    onClick = onRemove,
                    enabled = enabled,
                )
            }

            // 只读信息：索引里的字段必须与 zip 里的 module.prop 一致，不给编辑。
            SectionLabelMiuix(stringResource(R.string.module_upload_section_info))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colorScheme.surfaceContainer)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                InfoLineMiuix(stringResource(R.string.module_contribution_id), item.id)
                InfoLineMiuix(
                    stringResource(R.string.module_contribution_version),
                    "${item.version} (${item.versionCode})",
                )
                InfoLineMiuix(stringResource(R.string.module_contribution_name), item.name)
                if (item.author.isNotBlank()) {
                    InfoLineMiuix(stringResource(R.string.module_contribution_author), item.author)
                }
                if (item.description.isNotBlank()) {
                    InfoLineMiuix(
                        stringResource(R.string.module_contribution_description),
                        item.description,
                    )
                }
                InfoLineMiuix(
                    stringResource(R.string.module_upload_size),
                    ModuleUploadRules.formatBytes(item.sizeBytes),
                )
                InfoLineMiuix(stringResource(R.string.module_upload_sha), item.sha256, mono = true)
                if (item.checking) {
                    Text(
                        modifier = Modifier.padding(top = 6.dp),
                        text = stringResource(R.string.module_upload_checking),
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                } else if (item.status != null) {
                    val copy = ModuleUploadRules.statusCopy(item.status)
                    Text(
                        modifier = Modifier.padding(top = 6.dp),
                        text = stringResource(copy.title) + " · " + stringResource(copy.summary),
                        fontSize = 12.sp,
                        color = if (item.status == com.sukisu.ultra.ui.util.CheckStatus.RECORDED) {
                            colorScheme.error
                        } else {
                            colorScheme.onSurfaceVariantSummary
                        },
                    )
                }
            }

            SectionLabelMiuix(stringResource(R.string.module_upload_section_source))
            TextField(
                value = item.source,
                onValueChange = onSourceChange,
                enabled = enabled,
                label = stringResource(R.string.module_upload_source),
                singleLine = true,
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
                    fontSize = 12.sp,
                    color = if (problem == ModuleUploadRules.SourceProblem.OK) {
                        colorScheme.onSurfaceVariantSummary
                    } else {
                        colorScheme.error
                    },
                )
                TextButton(
                    text = stringResource(R.string.module_upload_apply_all),
                    onClick = onApplyToAll,
                    enabled = enabled && item.source.isNotBlank(),
                )
            }
        }
    }
}

@Composable
private fun ProofSectionMiuix(
    state: ModuleUploadFormState,
    enabled: Boolean,
    onSignatureConfirmedChange: (Boolean) -> Unit,
) {
    // 走长期令牌的这一次上传不需要改签名 —— 这一整段收起（连同「去酷安改签名」那句）。
    // 判据与提交那一步同一份：令牌绑的就是输入框里这个 UID，就别让人白抄一次签名。
    if (state.sessionMatchesUid) return
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val proof = state.proof
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.module_upload_proof_title),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (proof != null) {
                    TextButton(
                        text = stringResource(R.string.module_upload_proof_copy),
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("proof", proof.text)))
                            }
                        },
                    )
                }
            }

            if (proof == null) {
                // 只要还有一个包的 sha 没算完，就一行口令都不显示：
                // 半截哈希算出来的口令用户抄走也是废的。
                Text(
                    text = stringResource(R.string.module_upload_proof_pending),
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            } else {
                Text(
                    text = proof.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = stringResource(R.string.module_upload_proof_hint),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(
                        state = if (state.signatureValid) {
                            androidx.compose.ui.state.ToggleableState.On
                        } else {
                            androidx.compose.ui.state.ToggleableState.Off
                        },
                        onClick = { onSignatureConfirmedChange(!state.signatureValid) },
                        enabled = enabled,
                    )
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = stringResource(R.string.module_upload_proof_confirm),
                        fontSize = 13.sp,
                    )
                }
                // 勾过之后集合又变了：明确告诉用户要重抄，而不是只把勾去掉。
                if (state.confirmedSetKey != null && !state.signatureValid) {
                    Text(
                        text = stringResource(R.string.module_upload_proof_changed),
                        fontSize = 12.sp,
                        color = colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SubmitSectionMiuix(
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
                CircularProgressIndicator(progress = null, size = 20.dp, strokeWidth = 2.dp)
                Column {
                    Text(
                        text = stringResource(R.string.module_upload_submitting),
                        fontSize = 14.sp,
                    )
                    // 契约没有字节回调：只报总数与不确定进度，不编一个百分比。
                    Text(
                        text = stringResource(
                            R.string.module_upload_submitting_bytes,
                            state.items.size,
                            ModuleUploadRules.formatBytes(state.totalBytes),
                        ),
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        when (val outcome = state.outcome) {
            is UploadOutcome.Failure -> Text(
                text = outcome.message,
                fontSize = 13.sp,
                color = colorScheme.error,
            )

            is UploadOutcome.Success -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = outcome.message,
                    fontSize = 13.sp,
                    color = colorScheme.primary,
                )
                outcome.prUrl?.let { url ->
                    Text(
                        text = stringResource(R.string.module_upload_result_pr, url),
                        fontSize = 12.sp,
                        color = colorScheme.primary,
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
            cornerRadius = 12.dp,
        ) {
            Icon(
                imageVector = Icons.Rounded.CloudUpload,
                contentDescription = null,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text(text = stringResource(R.string.module_upload_submit))
        }
    }
}

@Composable
private fun UidStatusRowMiuix(
    state: ModuleUploadFormState,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    when (val uidState = state.uidState) {
        // 已验证：头像（有就画、没有就一个底圈）+ 勾选描边动画 + 昵称，右侧给「退出验证」。
        // 这里不用「一行小字」是因为「这个人是谁」比「这个字段填对了」更重要 ——
        // 用户要一眼认出「现在是我」，而不是读一行灰字。
        is CoolapkUidState.Verified -> Row(verticalAlignment = Alignment.CenterVertically) {
            VerifiedAvatar(
                avatarUrl = uidState.avatar.ifBlank { state.session?.avatar.orEmpty() },
                tint = colorScheme.primary,
                placeholder = colorScheme.surfaceContainer,
            )
            Text(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
                text = stringResource(R.string.module_upload_uid_verified, uidState.nickname),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.primary,
            )
            // 只有手里真有一张长期令牌时才给「退出验证」：没有令牌的话这个按钮点了
            // 只是把 UID 清空，用户会以为「验证被取消了」，而其实什么都没发生。
            if (state.sessionActive) {
                TextButton(
                    text = stringResource(R.string.module_upload_session_signout),
                    onClick = onSignOut,
                    enabled = !state.submitting,
                )
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
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )

        CoolapkUidState.NotFound -> Text(
            text = stringResource(R.string.module_upload_uid_not_found),
            fontSize = 13.sp,
            color = colorScheme.error,
        )

        CoolapkUidState.Unavailable -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.module_upload_uid_unavailable),
                fontSize = 13.sp,
                color = colorScheme.error,
            )
            TextButton(
                text = stringResource(R.string.network_retry),
                onClick = onRetry,
                enabled = !state.submitting,
            )
        }

        CoolapkUidState.SelfReported -> Text(
            text = stringResource(R.string.module_upload_uid_self_reported),
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )

        CoolapkUidState.Empty -> Text(
            text = if (state.uid.isNotBlank()) {
                stringResource(R.string.module_upload_uid_invalid)
            } else {
                stringResource(R.string.module_upload_uid_hint)
            },
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun SectionLabelMiuix(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
private fun InfoLineMiuix(label: String, value: String, mono: Boolean = false) {
    Row {
        Text(
            text = "$label: ",
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
        Text(
            modifier = if (mono) Modifier.weight(1f) else Modifier,
            text = value,
            fontSize = 12.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            maxLines = if (mono) 1 else 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
