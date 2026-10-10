package com.sukisu.ultra.ui.screen.modulerepo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import com.sukisu.ultra.R
import com.sukisu.ultra.data.model.RepoModule
import com.sukisu.ultra.data.repository.RepoSource
import com.sukisu.ultra.ui.component.SearchStatus

enum class RepoSort {
    UPDATED,
    CREATED,
    NAME,
    STARS,
}

data class ModuleRepoUiState(
    val isRefreshing: Boolean = false,
    /** True once a fetch has settled, so an empty list stops reading as "still loading". */
    val hasLoadedOnce: Boolean = false,
    val sortOrder: RepoSort = RepoSort.UPDATED,
    val offline: Boolean = false,
    val modules: List<RepoModule> = emptyList(),
    val searchStatus: SearchStatus = SearchStatus(""),
    val searchResults: List<RepoModule> = emptyList(),
    val error: Throwable? = null,
    val sources: List<RepoSource> = emptyList(),
    val sourceErrors: Map<String, String> = emptyMap(),
    /**
     * 本次抓取结果里被用户手动关掉的失败通知（按通知里那条消息记）。下一次抓取结果落到状态里时
     * 会清空：关掉针对的是这一次的失败，不代表以后都不想看见。
     */
    val dismissedSourceErrors: Set<String> = emptySet(),
    val isAddingSource: Boolean = false,
    /** The address being added, so the candidate row it came from can show that it is in flight. */
    val addingSourceUrl: String? = null,
    /** 最近一次新增成功的地址：对话框据此在成功后清空输入框，失败时把用户填的内容留在原处。 */
    val lastAddedSourceUrl: String? = null,
    /** Known repositories offered for one-tap adding, most modules first. */
    val candidates: List<RepoCandidateUi> = emptyList(),
)

/**
 * A candidate repository as the dialog shows it: [moduleCount] is a snapshot taken when the list
 * was last reviewed, and [isAdded] hides the add action for a repository that is already
 * configured, under any of the addresses that reach it.
 */
@Immutable
data class RepoCandidateUi(
    val name: String,
    val url: String,
    val moduleCount: Int,
    val isAdded: Boolean,
)

@Immutable
data class ModuleRepoActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onSearchTextChange: (String) -> Unit,
    val onClearSearch: () -> Unit,
    val onSearchStatusChange: (SearchStatus) -> Unit,
    val onSetSortOrder: (RepoSort) -> Unit,
    val onOpenRepoDetail: (RepoModule) -> Unit,
    /** [name] is the display name to store, or null to derive one from the address. */
    val onAddSource: (url: String, name: String?) -> Unit,
    val onRemoveSource: (String) -> Unit,
    val onSetSourceEnabled: (String, Boolean) -> Unit,
    val onRenameSource: (String, String) -> Unit,
    /** 关掉一条失败通知；参数是通知里显示的那条失败消息。 */
    val onDismissSourceError: (String) -> Unit,
)

@Immutable
data class ModuleRepoDetailUiState(
    val module: RepoModuleArg,
    val readmeHtml: String?,
    val readmeLoaded: Boolean,
    val detailReleases: List<ReleaseArg>,
    val webUrl: String,
    val sourceUrl: String,
)

@Immutable
data class ModuleRepoDetailActions(
    val onBack: () -> Unit,
    val onOpenWebUrl: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onInstallModule: (android.net.Uri) -> Unit,
)

/**
 * 列表为空的原因。空态必须按原因分别说：没配过源和源被全部停用是两件不同的事，后者只差用户去
 * 把开关打开，笼统说一句「暂无内容」等于什么都没说。
 */
internal enum class RepoEmptyReason {
    /** 一个源都没配过。 */
    NO_SOURCES,

    /** 配了源，但全都停用了，抓取自然什么都不会有。 */
    ALL_SOURCES_DISABLED,

    /** 源都在跑，只是没抓到模块——没什么可解释的，界面只给出口。 */
    NOTHING_FOUND,
}

/** 只按源的配置判断原因；「有没有模块」由调用方自己看，因为这两个问题的答案不该互相顶替。 */
internal fun repoEmptyReason(sources: List<RepoSource>): RepoEmptyReason = when {
    sources.isEmpty() -> RepoEmptyReason.NO_SOURCES
    sources.none { it.enabled } -> RepoEmptyReason.ALL_SOURCES_DISABLED
    else -> RepoEmptyReason.NOTHING_FOUND
}

/** 空态的说明文字；null 表示没有可解释的原因。 */
@Composable
internal fun repoEmptyHint(reason: RepoEmptyReason): String? = when (reason) {
    RepoEmptyReason.NO_SOURCES -> stringResource(R.string.module_repo_sources_empty)
    RepoEmptyReason.ALL_SOURCES_DISABLED -> stringResource(R.string.module_repo_sources_all_disabled)
    RepoEmptyReason.NOTHING_FOUND -> null
}

/** 空态按钮的文字：源被全部停用时该先去把源打开，而不是再加一个源。 */
@Composable
internal fun repoEmptyAction(reason: RepoEmptyReason): String = when (reason) {
    RepoEmptyReason.ALL_SOURCES_DISABLED -> stringResource(R.string.module_repo_manage_sources)
    else -> stringResource(R.string.module_repo_add_repo)
}
