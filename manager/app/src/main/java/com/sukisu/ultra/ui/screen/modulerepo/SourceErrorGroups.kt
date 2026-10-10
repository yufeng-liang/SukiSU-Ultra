package com.sukisu.ultra.ui.screen.modulerepo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.sukisu.ultra.R
import com.sukisu.ultra.data.repository.RepoSource

/**
 * Sources that failed with the same message. They share one notice, whose title names them instead
 * of repeating the identical message once per source.
 */
internal data class SourceErrorGroup(
    /** 失败发生在哪个源上，用源地址表示（见 ModuleRepoViewModel.resolveSourceErrorKeys）。 */
    val keys: List<String>,
    val message: String,
)

/**
 * Groups per-source failures by their message. Insertion order is kept, so the notice order follows
 * the order the sources were reported in and does not shuffle between refreshes.
 */
internal fun groupSourceErrors(sourceErrors: Map<String, String>): List<SourceErrorGroup> =
    sourceErrors.entries
        .groupBy({ it.value }, { it.key })
        .map { (message, keys) -> SourceErrorGroup(keys, message) }

/**
 * Title of a shared failure notice: the sources it covers, followed by what they are — e.g.
 * "KernelSU 模块仓库" or "KernelSU, uonou.github.io 模块仓库". Names are joined with ", " like every
 * other list this app renders (authors, providers), so the separator is not a new translatable.
 *
 * [keys] 上报失败时标识源用的身份（地址），这里按 [sources] 现翻成用户自己起的名字：标题该写用户填过
 * 的东西，而不是他从没输入过的地址。源已被删除或改过名时翻不出来，就照原样使用键。
 */
@Composable
internal fun sourceErrorTitle(keys: List<String>, sources: List<RepoSource>): String {
    val repoLabel = stringResource(R.string.module_repos)
    return remember(keys, sources, repoLabel) {
        "${keys.joinToString(", ") { sourceErrorLabel(it, sources) }} $repoLabel"
    }
}

/**
 * 给一个失败的源起显示名：能按地址对上源就用源名；对不上（源已被删除或改名，键里只剩旧名字）就
 * 照原样显示键。多个源共用一个名字时，光有名字分不清是哪个，得把地址一起写出来。
 */
private fun sourceErrorLabel(key: String, sources: List<RepoSource>): String {
    val matched = sources.firstOrNull { it.url == key || it.id == key }
    if (matched == null) {
        val sameName = sources.filter { it.name == key }
        return if (sameName.size > 1) "$key (${sameName.joinToString(", ") { it.url }})" else key
    }
    return if (sources.count { it.name == matched.name } == 1) {
        matched.name
    } else {
        "${matched.name} ($key)"
    }
}
