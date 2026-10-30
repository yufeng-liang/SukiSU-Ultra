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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.sukisu.ultra.R
import com.sukisu.ultra.data.model.RepoModule
import com.sukisu.ultra.data.repository.COOLAPK_COMMUNITY_SOURCE_ID
import com.sukisu.ultra.data.repository.RepoSource

// 商店页顶部的来源筛选：胶囊显示什么、列表该只看哪一段。
// 两套皮肤共用这一份取数逻辑，各自的视觉（Material 的 FilterChip / Miuix 的胶囊）留在
// ModuleRepoMaterial.kt 与 ModuleRepoMiuix.kt 里。

/**
 * 一个筛选胶囊。
 *
 * [sourceId] 为 null 表示「全部」。[count] 为 null 表示这次不该显示数字：该来源抓取失败时，
 * 数字看起来会像「这个源是空的」，而它其实只是没读到。
 */
internal data class RepoSourceFilterOption(
    val sourceId: String?,
    val label: String,
    val count: Int?,
)

/**
 * 顶部「酷安社区模块」入口卡对应的源。
 *
 * 用户可以从来源管理里把这个源删掉，而它只会在首次读列表时种一次，删掉之后不会再回来，
 * 所以这里返回 null 时入口卡就不能再渲染（否则「开启」是个按不动的死控件）。
 */
internal fun coolapkCommunitySource(sources: List<RepoSource>): RepoSource? =
    sources.firstOrNull { it.id == COOLAPK_COMMUNITY_SOURCE_ID }

/**
 * 按当前状态算一遍胶囊。
 *
 * 数字只在「这个来源这次抓取成功了」时才给：失败通知按源地址记账（[sourceErrors] 的键，
 * 见 ModuleRepoViewModel 里的 resolveSourceErrorKeys），同名源不止一个时会退回源名。
 */
@Composable
internal fun rememberRepoSourceFilters(
    modules: List<RepoModule>,
    sources: List<RepoSource>,
    sourceErrors: Map<String, String>,
): List<RepoSourceFilterOption> {
    // stringResource 不能在 remember 的 lambda 里调，先在这里把文案取出来，再当成 key 的一部分
    val allLabel = stringResource(R.string.module_repo_filter_all)
    val coolapkTitle = stringResource(R.string.module_repo_coolapk_title)
    return remember(modules, sources, sourceErrors, allLabel, coolapkTitle) {
        buildList {
            add(RepoSourceFilterOption(sourceId = null, label = allLabel, count = modules.size))
            sources.filter { it.enabled }.forEach { source ->
                // 数据层在源名唯一时会把键换成源地址；同名的源不止一个时只能保留源名，
                // 所以两个键都认——宁可少显示一个数字，也别把「没读到」当成「空源」。
                val loadFailed = sourceErrors.containsKey(source.url) ||
                    sourceErrors.containsKey(source.name)
                add(
                    RepoSourceFilterOption(
                        sourceId = source.id,
                        label = source.filterLabel(coolapkTitle),
                        count = if (loadFailed) null else modules.count { it.sourceId == source.id },
                    )
                )
            }
        }
    }
}

/** 酷安那个源在界面上用本地化标题，别的源用自己（可能被用户改过）的名字。 */
internal fun RepoSource.filterLabel(coolapkTitle: String): String =
    if (id == COOLAPK_COMMUNITY_SOURCE_ID) coolapkTitle else name

/** 胶囊上的最终文字：只有加载成功才带「名字 · N」。 */
@Composable
internal fun RepoSourceFilterOption.chipText(): String = if (count == null) {
    label
} else {
    stringResource(R.string.module_repo_filter_source_count, label, count)
}

/**
 * 列表里实际显示的那一段。
 *
 * 只过滤显示，不回写 ViewModel 的 modules：那是所有源的并集，筛选只是一种看法。用 remember
 * 缓存，避免滚动、输入这些无关重组每次都重新扫一遍列表。
 */
@Composable
internal fun rememberSourceFilteredModules(
    modules: List<RepoModule>,
    selectedSourceId: String?,
): List<RepoModule> = remember(modules, selectedSourceId) {
    if (selectedSourceId == null) modules else modules.filter { it.sourceId == selectedSourceId }
}
