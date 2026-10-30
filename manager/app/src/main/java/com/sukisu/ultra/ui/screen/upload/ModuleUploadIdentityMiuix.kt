package com.sukisu.ultra.ui.screen.upload

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.theme.LocalEnableBlur
import com.sukisu.ultra.ui.util.BlurredBar
import com.sukisu.ultra.ui.util.formatFileSize
import com.sukisu.ultra.ui.util.rememberBlurBackdrop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close2
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 身份页（Miuix）：上传之前先把「你是谁」这件事一次性办完。
 *
 * 这一屏只在**手上没有有效令牌**时出现。它把原先散落在上传表单里的三件事——填 UID、
 * 抄签名口令、勾承诺——一次性收进来，办完就写下一张九十天的令牌，之后每次进来直接是
 * 列表，用户只管填模块信息。
 *
 * 为什么口令要绑一枚具体的包：[ModuleUploadFormState.verifyItem] 的注释 —— 酷安签名是
 * 公开的，口令只绑 UID 的话，任何看见签名的人都能替别人领令牌。
 */
@Composable
fun ModuleUploadIdentityMiuix(
    state: ModuleUploadFormState,
    onExit: () -> Unit,
    onUidChange: (String) -> Unit,
    onRetryUid: () -> Unit,
    onPickZip: () -> Unit,
    onConsentChange: (Boolean) -> Unit,
    onVerify: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val proof = state.verifyProof

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.module_upload_identity_title),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onExit) {
                            Icon(
                                imageVector = MiuixIcons.Close2,
                                contentDescription = stringResource(R.string.cancel),
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            IdentityBarMiuix(
                state = state,
                onVerify = onVerify,
            )
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
                // 一进来就把「为什么要这些信息」讲清楚：这一屏是所有用户都要过的第一道门，
                // 讲不清就会像在收表。视觉借表单顶部汇总卡的语言（圆角图标块 + 加粗标题），
                // 让它读起来是「这一屏的说明」，而不是又一张待填表。
                Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.VerifiedUser,
                                contentDescription = null,
                                tint = colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.module_upload_identity_why_title),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.module_upload_identity_why_body),
                                fontSize = 13.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.padding(top = 20.dp).fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionLabelMiuix(
                            icon = Icons.Filled.VerifiedUser,
                            text = stringResource(R.string.module_upload_section_identity),
                            required = true,
                        )
                        TextField(
                            value = state.uid,
                            onValueChange = onUidChange,
                            enabled = !state.verifying,
                            label = stringResource(R.string.module_upload_uid),
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        UidStatusRowMiuix(
                            state = state,
                            onRetry = onRetryUid,
                            // 身份页没有「退出验证」——这一屏本来就是因为没有令牌才来的。
                            onSignOut = null,
                        )
                        Text(
                            text = stringResource(R.string.module_upload_identity_level),
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.padding(top = 20.dp).fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionLabelMiuix(
                            icon = MiuixIcons.File,
                            text = stringResource(R.string.module_upload_identity_anchor_title),
                            required = true,
                        )
                        Text(
                            text = stringResource(R.string.module_upload_identity_anchor_hint),
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                        val item = state.verifyItem
                        if (item == null) {
                            Text(
                                text = stringResource(R.string.module_upload_identity_anchor_empty),
                                fontSize = 13.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        } else {
                            Text(
                                text = "${item.name} · ${item.version} (${item.versionCode})",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${item.id} · ${formatFileSize(item.sizeBytes)}",
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(
                            text = stringResource(R.string.module_upload_identity_anchor_pick),
                            onClick = onPickZip,
                            enabled = !state.verifying,
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.padding(top = 20.dp).fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionLabelMiuix(
                            icon = Icons.Filled.VerifiedUser,
                            text = stringResource(R.string.module_upload_proof_title),
                        )
                        if (proof == null) {
                            // 还没挑锚点包：一行口令都不该显示，否则用户抄走的是一串
                            // 「换了包就作废」的东西。
                            Text(
                                text = stringResource(R.string.module_upload_proof_pending),
                                fontSize = 13.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        } else {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(12.dp),
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
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    TextButton(
                                        text = stringResource(R.string.module_upload_proof_copy),
                                        onClick = {
                                            scope.launch {
                                                clipboard.setClipEntry(
                                                    ClipEntry(ClipData.newPlainText("proof", proof.text)),
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.module_upload_proof_hint),
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                            Text(
                                text = stringResource(R.string.module_upload_proof_why),
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                            Text(
                                text = stringResource(R.string.module_upload_proof_restore),
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.padding(top = 20.dp).fillMaxWidth()) {
                    // 整行可点：勾选框在左侧、文字占三行时，只点那个小方块太窄。
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
                        Checkbox(
                            state = if (state.consent) {
                                androidx.compose.ui.state.ToggleableState.On
                            } else {
                                androidx.compose.ui.state.ToggleableState.Off
                            },
                            onClick = null,
                        )
                        Text(
                            modifier = Modifier.padding(start = 10.dp),
                            text = stringResource(R.string.module_upload_identity_pledge),
                            fontSize = 13.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

/**
 * 底部常驻栏：与表单页的 SubmitBarMiuix 同一副骨架——左侧一行状态 hint，右侧「验证身份」。
 *
 * hint 三态：验证中是中性句；还差步骤时用错误色数出差几步；条件都齐了换主色催一下。
 * 按钮不满足条件时**仍然画着**（只是不可点）而不是藏起来：藏起来的话，用户看不出还差什么，
 * 只会觉得这一屏没有出口。差哪一项由正文里的必填标记与这条 hint 共同承担。
 */
@Composable
private fun IdentityBarMiuix(
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
    Surface(color = colorScheme.surface) {
        Column {
            if (error != null) {
                Text(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp),
                    text = error,
                    fontSize = 12.sp,
                    color = colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 键盘弹起时这条栏要跟着抬起来，否则「验证身份」会被输入法压住；
                    // 与 navigationBars 取并集，避免两者叠加出两份留白。
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
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
                    fontSize = 12.sp,
                    maxLines = 2,
                    color = when {
                        verifying -> colorScheme.onSurfaceVariantSummary
                        missing > 0 -> colorScheme.error
                        else -> colorScheme.primary
                    },
                )
                Button(
                    onClick = onVerify,
                    enabled = state.canVerify,
                    modifier = Modifier.height(48.dp),
                    cornerRadius = 12.dp,
                ) {
                    if (verifying) {
                        CircularProgressIndicator(
                            progress = null,
                            size = 18.dp,
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        text = stringResource(
                            if (verifying) R.string.module_upload_identity_verifying
                            else R.string.module_upload_identity_verify,
                        ),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
