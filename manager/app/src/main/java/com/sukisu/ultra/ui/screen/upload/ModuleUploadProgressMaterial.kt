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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.util.ProgressItem
import com.sukisu.ultra.ui.util.ProgressNodeState
import com.sukisu.ultra.ui.util.ProgressStage
import com.sukisu.ultra.ui.util.ProgressVersion
import com.sukisu.ultra.ui.util.allowsStoreLink
import com.sukisu.ultra.ui.util.nodeStateOf

/** 进度弹层的高度上限：四步 × 几版之后就该滚了，再高会把标题挤出屏幕。 */
private val MaxProgressDialogHeight = 420.dp

/**
 * 竖排四步进度条（Material）。
 *
 * 从上到下：已提交 → 待审核 → 已合并 → 已收录。当前格高亮并画实心圆点，走过的格
 * 用次级文字色 + 连线，没到的格用 outline 色；被拒时停在「待审核」并把那一格染成
 * 错误色，理由在下面单独一行（最多 3 行，超出省略）。
 *
 * [item] 为 null 表示这次没查到：四格全灰，由调用方在别处说明「查不到」——
 * 这里不自己画提示，否则结果卡（没有重试按钮）与弹层（有）会各画一遍不同的话。
 */
@Composable
internal fun UploadProgressStepsMaterial(
    item: ProgressItem?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val labels = listOf(
        stringResource(R.string.module_upload_progress_step_submitted),
        stringResource(R.string.module_upload_progress_step_reviewing),
        stringResource(R.string.module_upload_progress_step_merged),
        stringResource(R.string.module_upload_progress_step_published),
    )
    val rejectedLabel = stringResource(R.string.module_upload_progress_step_rejected)

    Column(modifier = modifier) {
        labels.forEachIndexed { index, label ->
            val node = item.nodeStateOf(index)
            val isLast = index == labels.lastIndex
            val text = if (node == ProgressNodeState.FAILED) rejectedLabel else label
            val contentColor = when (node) {
                ProgressNodeState.CURRENT -> colors.primary
                ProgressNodeState.FAILED -> colors.error
                ProgressNodeState.DONE -> colors.onSurfaceVariant
                ProgressNodeState.UPCOMING -> colors.outline
            }
            val dotColor = when (node) {
                ProgressNodeState.CURRENT -> colors.primary
                ProgressNodeState.FAILED -> colors.error
                ProgressNodeState.DONE -> colors.onSurfaceVariant
                ProgressNodeState.UPCOMING -> colors.outlineVariant
            }
            val lineColor =
                if (node == ProgressNodeState.DONE || node == ProgressNodeState.CURRENT) {
                    colors.onSurfaceVariant
                } else {
                    colors.outlineVariant
                }

            Row(verticalAlignment = Alignment.Top) {
                // 左侧一列：圆点 + 到下一格的连线。连线跟着「这一格走过没」变色，
                // 这样一眼能看出走到哪一段，而不用去数高亮的是第几个字。
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 5.dp)
                            .size(if (node == ProgressNodeState.CURRENT || node == ProgressNodeState.FAILED) 11.dp else 8.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    if (!isLast) {
                        Box(
                            modifier = Modifier
                                .width(1.5.dp)
                                .height(18.dp)
                                .background(lineColor),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (node == ProgressNodeState.CURRENT || node == ProgressNodeState.FAILED) {
                        FontWeight.SemiBold
                    } else {
                        FontWeight.Normal
                    },
                    color = contentColor,
                    modifier = Modifier.padding(top = 1.dp, bottom = if (isLast) 0.dp else 10.dp),
                )
            }
        }

        // 被拒的理由：服务端原话，最多 3 行。它是这一步唯一能让用户知道「为什么」的东西，
        // 所以不塞进节点内，单独一行排在步骤条下面。
        val reason = item?.reason
        if (item?.stage == ProgressStage.REJECTED && !reason.isNullOrBlank()) {
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, top = 6.dp),
            )
        }
        if (item?.unverified == true) {
            Text(
                text = stringResource(R.string.module_upload_progress_unverified),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, top = 6.dp),
            )
        }
    }
}

/**
 * 进度下面那两枚按钮（Material）。
 *
 * PR 链接所有阶段都给（还没开 PR 时 [ProgressItem.prUrl] 为 null，自然不画）；
 * 「去商店看看」只在**已收录**时出现（[allowsStoreLink]），否则用户点进去搜不到自己那一版。
 */
@Composable
internal fun UploadProgressActionsMaterial(
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
            TextButton(onClick = { onOpenPr(prUrl) }) {
                Text(stringResource(R.string.module_upload_open_pr))
            }
        }
        if (item.allowsStoreLink()) {
            TextButton(onClick = onOpenStore) {
                Text(stringResource(R.string.module_upload_progress_open_store))
            }
        }
    }
}

/**
 * 上传记录页的进度弹层（Material）。
 *
 * 一次可以带多行（[versions]），每行是「某一版投稿 + 它的进度」。数据只有一半时
 * （`/progress` 没回来、或这一版不在最近 12 枚里）照样画：步骤条全灰 + 一句说明，
 * 比整块消失更容易理解。
 */
@Composable
internal fun UploadProgressDialogMaterial(
    versions: List<ProgressVersion>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onOpenPr: (String) -> Unit,
    onOpenStore: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.module_upload_progress_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = MaxProgressDialogHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (error != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRetry) {
                            Text(stringResource(R.string.module_upload_progress_retry))
                        }
                    }
                }
                if (versions.isEmpty() && !loading) {
                    Text(
                        text = stringResource(R.string.module_upload_progress_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        UploadProgressStepsMaterial(item = version.progress)
                        UploadProgressActionsMaterial(
                            item = version.progress,
                            onOpenPr = onOpenPr,
                            onOpenStore = onOpenStore,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
