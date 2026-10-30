/*
 * Copyright (C) 2025 SukiSU Ultra
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sukisu.ultra.ui.screen.upload

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.util.ProgressItem
import com.sukisu.ultra.ui.util.ProgressNodeState
import com.sukisu.ultra.ui.util.ProgressStage
import com.sukisu.ultra.ui.util.ProgressVersion
import com.sukisu.ultra.ui.util.allowsStoreLink
import com.sukisu.ultra.ui.util.nodeStateOf
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** 进度弹层的高度上限：与来源管理对话框同一个尺度，四步 × 几版之后就滚。 */
private val MaxProgressDialogHeight = 420.dp

/**
 * 竖排四步进度条（Miuix）。
 *
 * 与 Material 版同一份判据（[nodeStateOf]），只换配色与字号：Miuix 没有现成的
 * Stepper 控件，圆点与连线都是 Box + background 画的 —— 四步的语义比控件形状更重要。
 */
@Composable
internal fun UploadProgressStepsMiuix(
    item: ProgressItem?,
    modifier: Modifier = Modifier,
) {
    val labels = listOf(
        stringResource(R.string.module_upload_progress_step_submitted),
        stringResource(R.string.module_upload_progress_step_reviewing),
        stringResource(R.string.module_upload_progress_step_merged),
        stringResource(R.string.module_upload_progress_step_published),
    )
    val rejectedLabel = stringResource(R.string.module_upload_progress_step_rejected)
    // Miuix 没有 outlineVariant：没到的格统一用 outline 压到半透明，观感与 Material 的
    // 浅灰一致，又不必猜一个不存在的色板字段。
    val upcoming = colorScheme.outline.copy(alpha = 0.45f)

    Column(modifier = modifier) {
        labels.forEachIndexed { index, label ->
            val node = item.nodeStateOf(index)
            val isLast = index == labels.lastIndex
            val text = if (node == ProgressNodeState.FAILED) rejectedLabel else label
            val contentColor = when (node) {
                ProgressNodeState.CURRENT -> colorScheme.primary
                ProgressNodeState.FAILED -> colorScheme.error
                ProgressNodeState.DONE -> colorScheme.onSurfaceVariantSummary
                ProgressNodeState.UPCOMING -> upcoming
            }
            val dotColor = when (node) {
                ProgressNodeState.CURRENT -> colorScheme.primary
                ProgressNodeState.FAILED -> colorScheme.error
                ProgressNodeState.DONE -> colorScheme.onSurfaceVariantSummary
                ProgressNodeState.UPCOMING -> upcoming
            }
            val lineColor =
                if (node == ProgressNodeState.DONE || node == ProgressNodeState.CURRENT) {
                    colorScheme.onSurfaceVariantSummary
                } else {
                    colorScheme.outline.copy(alpha = 0.3f)
                }

            Row(verticalAlignment = Alignment.Top) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(if (node == ProgressNodeState.CURRENT || node == ProgressNodeState.FAILED) 10.dp else 7.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    if (!isLast) {
                        Box(
                            modifier = Modifier
                                .width(1.5.dp)
                                .height(20.dp)
                                .background(lineColor),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = text,
                    fontSize = 14.sp,
                    fontWeight = if (node == ProgressNodeState.CURRENT || node == ProgressNodeState.FAILED) {
                        FontWeight(550)
                    } else {
                        FontWeight(400)
                    },
                    color = contentColor,
                    modifier = Modifier.padding(top = 1.dp, bottom = if (isLast) 0.dp else 10.dp),
                )
            }
        }

        val reason = item?.reason
        if (item?.stage == ProgressStage.REJECTED && !reason.isNullOrBlank()) {
            Text(
                text = reason,
                fontSize = 12.sp,
                color = colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, top = 6.dp),
            )
        }
        if (item?.unverified == true) {
            Text(
                text = stringResource(R.string.module_upload_progress_unverified),
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 24.dp, top = 6.dp),
            )
        }
    }
}

/**
 * 进度下面那两枚按钮（Miuix）：PR 链接所有阶段都给，「去商店看看」只在已收录时出现。
 */
@Composable
internal fun UploadProgressActionsMiuix(
    item: ProgressItem?,
    onOpenPr: (String) -> Unit,
    onOpenStore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prUrl = item?.prUrl
    if (prUrl == null && !item.allowsStoreLink()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (prUrl != null) {
            TextButton(
                text = stringResource(R.string.module_upload_open_pr),
                onClick = { onOpenPr(prUrl) },
            )
        }
        if (item.allowsStoreLink()) {
            TextButton(
                text = stringResource(R.string.module_upload_progress_open_store),
                onClick = onOpenStore,
            )
        }
    }
}

/**
 * 上传记录页的进度弹层（Miuix）。
 *
 * 用 OverlayDialog 而不是另起一屏：进度是「看一眼就走」的补充信息，
 * 把它塞进路由会让返回键的语义从「回记录」变成「回进度」。
 */
@Composable
internal fun UploadProgressDialogMiuix(
    versions: List<ProgressVersion>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onOpenPr: (String) -> Unit,
    onOpenStore: () -> Unit,
) {
    OverlayDialog(
        show = true,
        title = stringResource(R.string.module_upload_progress_title),
        onDismissRequest = onDismiss,
        content = {
            Column(
                modifier = Modifier
                    .heightIn(max = MaxProgressDialogHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (loading) {
                    Text(
                        text = stringResource(R.string.module_upload_mine_loading),
                        fontSize = 13.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
                if (error != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = error,
                            fontSize = 12.sp,
                            color = colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = stringResource(R.string.module_upload_progress_retry),
                            onClick = onRetry,
                        )
                    }
                }
                if (versions.isEmpty() && !loading) {
                    Text(
                        text = stringResource(R.string.module_upload_progress_unavailable),
                        fontSize = 14.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
                versions.forEach { version ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(
                                R.string.module_upload_progress_version,
                                version.id,
                                version.versionCode.toInt(),
                            ),
                            fontSize = 13.sp,
                            fontWeight = FontWeight(550),
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                        UploadProgressStepsMiuix(item = version.progress)
                        UploadProgressActionsMiuix(
                            item = version.progress,
                            onOpenPr = onOpenPr,
                            onOpenStore = onOpenStore,
                        )
                    }
                }
                TextButton(
                    text = stringResource(R.string.close),
                    onClick = onDismiss,
                )
            }
        },
    )
}
