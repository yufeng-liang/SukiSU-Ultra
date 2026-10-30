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

package com.sukisu.ultra.ui.screen.modulerepo

import com.sukisu.ultra.data.repository.COOLAPK_COMMUNITY_SOURCE_ID
import com.sukisu.ultra.ui.util.formatRepoTime
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 酷安社区索引条目带的出处信息。
 *
 * 酷安那边只有一份「谁在哪个帖子贴了什么」的索引：没有 GitHub 仓库、没有星标、没有 README。
 * 所以这类模块的详情页只展示出处、上传者、提交时间，其余 GitHub 区块整块不渲染。字段都可能缺
 * （老帖子被删、上传者没填 UID），缺哪条就整行不画，不留空壳。
 */
internal data class CoolapkModuleMeta(
    val postUrl: String? = null,
    val uploaderName: String? = null,
    val coolapkUid: String? = null,
    val submittedAt: String? = null,
)

/** 这条模块是不是来自酷安社区索引。 */
internal fun RepoModuleArg.isCoolapkCommunityModule(): Boolean =
    sourceId == COOLAPK_COMMUNITY_SOURCE_ID

/**
 * 把这条模块的索引字段折成详情页要的出处信息。
 *
 * 只有 [RepoModuleArg.source]（上传者当时贴的帖子地址）在，才算「有出处」：其它仓库的索引
 * 没有这套字段，酷安索引里的老条目也可能缺——这时返回 null，详情页不画信息页，也不留空壳。
 * 昵称、UID、时间都是可选的，缺哪条由界面整行不画。
 */
internal fun RepoModuleArg.coolapkMeta(): CoolapkModuleMeta? {
    val postUrl = source.takeIf { it.isNotBlank() } ?: return null
    return CoolapkModuleMeta(
        postUrl = postUrl,
        uploaderName = uploader.takeIf { it.isNotBlank() },
        // 昵称在酷安站内不唯一，只有 UID 能稳定跳主页，所以按钮的有无看 UID。
        coolapkUid = coolapkUid.takeIf { it.isNotBlank() },
        submittedAt = submittedAt.takeIf { it > 0L }?.let(::epochToIso),
    )
}

/**
 * 索引里的提交时间给了 epoch 数值，但可能是毫秒也可能是秒（看生成脚本）。
 *
 * 2001 年之后的毫秒时间戳都超过 1e12，而秒级时间戳要到公元 33658 年才到这个量级，按数量级
 * 分开就够用。输出 ISO-8601，交给 [formatRepoTime] 按语言环境排版，解析侧不必再管格式。
 */
private fun epochToIso(value: Long): String {
    val millis = if (value >= 1_000_000_000_000L) value else value * 1000L
    return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis))
}
