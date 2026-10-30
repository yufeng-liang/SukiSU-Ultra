package com.sukisu.ultra.ui.screen.upload

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.component.material.ExpressiveScaffold
import com.sukisu.ultra.ui.component.material.TonalCard
import com.sukisu.ultra.ui.component.material.expressiveTopAppBarColors
import com.sukisu.ultra.ui.util.formatFileSize
import kotlinx.coroutines.launch

/**
 * 身份页（Material）：与 [ModuleUploadIdentityMiuix] 同一份状态、同一套判据，只换控件。
 *
 * 这一屏只在**手上没有有效令牌**时出现；核验通过就写下九十天的令牌并回列表，
 * 之后每次进来都看不到它（用户只管填模块信息）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleUploadIdentityMaterial(
    state: ModuleUploadFormState,
    onExit: () -> Unit,
    onUidChange: (String) -> Unit,
    onRetryUid: () -> Unit,
    onPickZip: () -> Unit,
    onConsentChange: (Boolean) -> Unit,
    onVerify: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val proof = state.verifyProof

    ExpressiveScaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.module_upload_identity_title)) },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = expressiveTopAppBarColors(),
            )
        },
        bottomBar = {
            IdentityBarMaterial(
                state = state,
                onVerify = onVerify,
            )
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp),
            contentPadding = innerPadding,
        ) {
            item {
                // 这是所有用户都要过的第一道门，进来就先说清楚为什么要这些信息。
                // 视觉借表单顶部汇总卡的语言（强调色图标 + 加粗标题），让它读起来
                // 是「这一屏的说明」，而不是又一张待填表。
                TonalCard(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    val accent = if (state.canVerify) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(
                        modifier = Modifier.padding(16.dp, 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            modifier = Modifier.size(20.dp),
                            imageVector = Icons.Filled.VerifiedUser,
                            contentDescription = null,
                            tint = accent,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.module_upload_identity_why_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.module_upload_identity_why_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                TonalCard(modifier = Modifier.padding(top = 20.dp).fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionLabelMaterial(
                            icon = Icons.Filled.VerifiedUser,
                            text = stringResource(R.string.module_upload_section_identity),
                            required = true,
                        )
                        OutlinedTextField(
                            value = state.uid,
                            onValueChange = onUidChange,
                            enabled = !state.verifying,
                            label = { Text(stringResource(R.string.module_upload_uid)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        UidStatusRowMaterial(
                            state = state,
                            onRetry = onRetryUid,
                            // 身份页没有「退出验证」——这一屏本来就是因为没有令牌才来的。
                            onSignOut = null,
                        )
                        Text(
                            text = stringResource(R.string.module_upload_identity_level),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                TonalCard(modifier = Modifier.padding(top = 20.dp).fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionLabelMaterial(
                            icon = Icons.Outlined.Folder,
                            text = stringResource(R.string.module_upload_identity_anchor_title),
                            required = true,
                        )
                        Text(
                            text = stringResource(R.string.module_upload_identity_anchor_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val item = state.verifyItem
                        if (item == null) {
                            Text(
                                text = stringResource(R.string.module_upload_identity_anchor_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                text = "${item.name} · ${item.version} (${item.versionCode})",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${item.id} · ${formatFileSize(item.sizeBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = onPickZip, enabled = !state.verifying) {
                            Text(stringResource(R.string.module_upload_identity_anchor_pick))
                        }
                    }
                }
            }

            item {
                TonalCard(modifier = Modifier.padding(top = 20.dp).fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionLabelMaterial(
                            icon = Icons.Filled.VerifiedUser,
                            text = stringResource(R.string.module_upload_proof_title),
                        )
                        if (proof == null) {
                            // 还没挑锚点包：一行口令都不该显示，否则用户抄走的是一串
                            // 「换了包就作废」的东西。
                            Text(
                                text = stringResource(R.string.module_upload_proof_pending),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        modifier = Modifier.weight(1f),
                                        text = proof.text,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    TextButton(onClick = {
                                        scope.launch {
                                            clipboard.setClipEntry(
                                                ClipEntry(ClipData.newPlainText("proof", proof.text)),
                                            )
                                        }
                                    }) {
                                        Text(stringResource(R.string.module_upload_proof_copy))
                                    }
                                }
                            }
                            Text(
                                text = stringResource(R.string.module_upload_proof_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.module_upload_proof_why),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.module_upload_proof_restore),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                TonalCard(modifier = Modifier.padding(top = 20.dp).fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = state.consent,
                                enabled = !state.verifying,
                                role = Role.Checkbox,
                                onValueChange = onConsentChange,
                            )
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = state.consent, onCheckedChange = null)
                        Text(
                            modifier = Modifier.padding(start = 10.dp),
                            text = stringResource(R.string.module_upload_identity_pledge),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

/**
 * 底部常驻栏：与表单页的 SubmitBarMaterial 同一副骨架——左侧一行状态 hint，右侧「验证身份」。
 *
 * hint 三态：验证中是中性句；还差步骤时用错误色数出差几步；条件都齐了换主色催一下。
 * 按钮不满足条件时仍然画着（只是不可点），否则用户看不出还差什么。
 */
@Composable
private fun IdentityBarMaterial(
    state: ModuleUploadFormState,
    onVerify: () -> Unit,
) {
    val missing = listOf(
        state.uid.trim().length in 4..12,
        state.verifyItem != null,
        state.consent,
    ).count { !it }
    val verifying = state.verifying
    val error = state.verifyError
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column {
            if (error != null) {
                Text(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 键盘弹起时这条栏跟着抬起来，否则「验证身份」会被输入法压住。
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 10.dp,
                        bottom = 10.dp + WindowInsets.navigationBars.union(WindowInsets.ime)
                            .asPaddingValues().calculateBottomPadding(),
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = when {
                        verifying -> stringResource(R.string.module_upload_identity_verifying)
                        missing > 0 -> stringResource(R.string.module_upload_identity_missing, missing)
                        else -> stringResource(R.string.module_upload_identity_ready)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    color = when {
                        verifying -> MaterialTheme.colorScheme.onSurfaceVariant
                        missing > 0 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
                Button(
                    onClick = onVerify,
                    enabled = state.canVerify,
                    modifier = Modifier.height(48.dp),
                ) {
                    if (verifying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Text(
                        text = stringResource(
                            if (verifying) R.string.module_upload_identity_verifying
                            else R.string.module_upload_identity_verify,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
    }
}
