package com.sukisu.ultra.ui.viewmodel

import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sukisu.ultra.R
import com.sukisu.ultra.data.repository.ModuleRepoRepository
import com.sukisu.ultra.data.repository.ModuleRepoRepositoryImpl
import com.sukisu.ultra.data.repository.RepoSource
import com.sukisu.ultra.data.repository.RepoSourceRepository
import com.sukisu.ultra.data.repository.RepoSourceRepositoryImpl
import com.sukisu.ultra.data.repository.SettingsRepository
import com.sukisu.ultra.data.repository.SettingsRepositoryImpl
import com.sukisu.ultra.data.repository.defaultRepoCandidates
import com.sukisu.ultra.data.repository.isSameSource
import com.sukisu.ultra.ksuApp
import com.sukisu.ultra.ui.component.SearchStatus
import com.sukisu.ultra.ui.screen.modulerepo.ModuleRepoUiState
import com.sukisu.ultra.ui.screen.modulerepo.RepoCandidateUi
import com.sukisu.ultra.ui.screen.modulerepo.RepoSort
import com.sukisu.ultra.ui.util.PinyinUtil
import com.sukisu.ultra.ui.util.hasAnyNetwork
import java.text.Collator
import java.util.Locale

class ModuleRepoViewModel(
    private val repo: ModuleRepoRepository = ModuleRepoRepositoryImpl(),
    private val sourceRepo: RepoSourceRepository = RepoSourceRepositoryImpl(),
    private val settingsRepo: SettingsRepository = SettingsRepositoryImpl()
) : ViewModel() {

    companion object {
        private const val TAG = "ModuleRepoViewModel"
    }

    typealias RepoModule = com.sukisu.ultra.data.model.RepoModule

    private val _uiState = MutableStateFlow(ModuleRepoUiState())
    val uiState: StateFlow<ModuleRepoUiState> = _uiState.asStateFlow()

    private val searchQuery = MutableStateFlow("")

    init {
        val ordinal = settingsRepo.moduleRepoSortOrder
        val initial = RepoSort.entries.getOrElse(ordinal) { RepoSort.UPDATED }
        val sources = sourceRepo.loadSources()
        _uiState.update {
            it.copy(
                sortOrder = initial,
                offline = !hasAnyNetwork(ksuApp),
                sources = sources,
                candidates = candidatesFor(sources),
            )
        }

        viewModelScope.launchSearchQueryCollector(searchQuery, ::applySearchText)
    }

    /**
     * The known repositories, with the ones already configured marked so the dialog can stop
     * offering to add them again.
     */
    private fun candidatesFor(sources: List<RepoSource>): List<RepoCandidateUi> =
        defaultRepoCandidates.map { candidate ->
            RepoCandidateUi(
                name = candidate.name,
                url = candidate.url,
                moduleCount = candidate.moduleCount,
                isAdded = sources.any { source -> isSameSource(source.url, candidate.url) },
            )
        }

    /** Re-reads the configured sources and refreshes which candidates are already added. */
    private fun reloadSources() {
        val sources = sourceRepo.loadSources()
        _uiState.update { it.copy(sources = sources, candidates = candidatesFor(sources)) }
    }

    private fun sortModules(list: List<RepoModule>, order: RepoSort): List<RepoModule> {
        if (list.isEmpty()) return list
        return when (order) {
            RepoSort.UPDATED -> list.sortedByDescending { it.latestReleaseTime }
            RepoSort.CREATED -> list.sortedByDescending { it.createdAt }
            RepoSort.NAME -> {
                val collator = Collator.getInstance(Locale.getDefault())
                list.sortedWith(compareBy(collator) { it.moduleName })
            }

            RepoSort.STARS -> list.sortedByDescending { it.stargazerCount }
        }
    }

    private fun filterModules(modules: List<RepoModule>, text: String): List<RepoModule> {
        if (text.isEmpty()) return emptyList()

        return modules.filter {
            it.moduleId.contains(text, true) ||
                    it.moduleName.contains(text, true) ||
                    it.authors.contains(text, true) ||
                    it.summary.contains(text, true) ||
                    it.sourceName.contains(text, true) ||
                    PinyinUtil.toPinyin(it.moduleName).contains(text, true)
        }
    }

    private suspend fun applySearchText(text: String) {
        _uiState.update {
            it.copy(
                searchStatus = it.searchStatus.copy(
                    resultStatus = searchLoadingStatusFor(text)
                )
            )
        }

        if (text.isEmpty()) {
            _uiState.update { state ->
                state.copy(
                    searchResults = emptyList(),
                    searchStatus = state.searchStatus.copy(resultStatus = SearchStatus.ResultStatus.DEFAULT)
                )
            }
            return
        }

        val result = withContext(Dispatchers.IO) {
            sortModules(filterModules(_uiState.value.modules, text), _uiState.value.sortOrder)
        }

        _uiState.update {
            it.copy(
                searchResults = result,
                searchStatus = it.searchStatus.copy(resultStatus = searchResultStatusFor(text, result.isEmpty()))
            )
        }
    }

    private fun refreshSearchResults() {
        val state = _uiState.value
        val text = state.searchStatus.searchText
        val results = sortModules(filterModules(state.modules, text), state.sortOrder)
        _uiState.update {
            it.copy(
                searchResults = results,
                searchStatus = it.searchStatus.copy(resultStatus = searchResultStatusFor(text, results.isEmpty()))
            )
        }
    }

    private var pendingRefresh = false

    fun refresh() {
        if (_uiState.value.isRefreshing) {
            pendingRefresh = true
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRefreshing = true,
                    error = null,
                    offline = !hasAnyNetwork(ksuApp)
                )
            }
            val result = repo.fetchModules()

            withContext(Dispatchers.Main) {
                result.onSuccess { outcome ->
                    val current = _uiState.value
                    // When every source fails, keep the previous list instead of blanking the page.
                    val keepOld = outcome.modules.isEmpty() && outcome.sourceErrors.isNotEmpty() && current.modules.isNotEmpty()
                    val order = current.sortOrder
                    val sorted = if (keepOld) {
                        current.modules
                    } else {
                        withContext(Dispatchers.Default) { sortModules(outcome.modules, order) }
                    }
                    // 新的抓取结果落地时把「已关掉的通知」清空：用户关掉的是这一次的失败，不是表示
                    // 以后都不想看见，所以每次刷新都重新通知一遍。
                    _uiState.update {
                        it.copy(
                            modules = sorted,
                            sourceErrors = resolveSourceErrorKeys(outcome.sourceErrors, current.sources),
                            dismissedSourceErrors = emptySet(),
                            offline = !hasAnyNetwork(ksuApp)
                        )
                    }
                    // 失败原因由列表里的错误卡片逐条给出（哪个源、什么原因），这里不再弹一遍 Toast：
                    // 同一件事说两次，而且 Toast 只带得走一条消息，信息量反而不如卡片。
                    refreshSearchResults()
                    _uiState.update { it.copy(isRefreshing = false, hasLoadedOnce = true) }
                    runPendingRefresh()
                }.onFailure { e ->
                    Log.e(TAG, "fetch modules failed", e)
                    Toast.makeText(
                        ksuApp,
                        ksuApp.getString(R.string.network_offline), Toast.LENGTH_SHORT
                    ).show()
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            hasLoadedOnce = true,
                            error = e,
                            offline = !hasAnyNetwork(ksuApp)
                        )
                    }
                    runPendingRefresh()
                }
            }
        }
    }

    fun setSortOrder(order: RepoSort) {
        if (_uiState.value.sortOrder == order) return
        settingsRepo.moduleRepoSortOrder = order.ordinal
        viewModelScope.launch {
            val state = _uiState.value
            val (sortedModules, sortedSearch) = withContext(Dispatchers.Default) {
                sortModules(state.modules, order) to sortModules(state.searchResults, order)
            }
            _uiState.update {
                it.copy(
                    sortOrder = order,
                    modules = sortedModules,
                    searchResults = sortedSearch,
                )
            }
        }
    }

    fun updateSearchStatus(status: SearchStatus) {
        val previous = _uiState.value.searchStatus
        _uiState.update { it.copy(searchStatus = status) }
        if (previous.searchText != status.searchText) {
            searchQuery.value = status.searchText
        }
    }

    fun updateSearchText(text: String) {
        updateSearchStatus(_uiState.value.searchStatus.copy(searchText = text))
    }

    fun addSource(rawUrl: String, name: String? = null) {
        if (_uiState.value.isAddingSource) return
        viewModelScope.launch {
            _uiState.update { it.copy(isAddingSource = true, addingSourceUrl = rawUrl) }
            val result = sourceRepo.addSource(rawUrl, name)
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isAddingSource = false, addingSourceUrl = null) }
                reloadSources()
                result.onSuccess {
                    // 先记下这次添加成功（内容就是用户填的原字符串），对话框看到它变化才清空输入框；
                    // 失败路径不动它，用户填的地址就留在输入框里等着改或重试。
                    _uiState.update { it.copy(lastAddedSourceUrl = rawUrl) }
                    Toast.makeText(ksuApp, ksuApp.getString(R.string.module_repo_source_added), Toast.LENGTH_SHORT).show()
                    refresh()
                }.onFailure { e ->
                    Toast.makeText(ksuApp, e.message ?: e.javaClass.simpleName, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun removeSource(id: String) {
        sourceRepo.removeSource(id)
        reloadSources()
        refresh()
    }

    fun setSourceEnabled(id: String, enabled: Boolean) {
        sourceRepo.setSourceEnabled(id, enabled)
        reloadSources()
        refresh()
    }

    fun renameSource(id: String, name: String) {
        if (name.isBlank()) return
        val trimmed = name.trim()
        val previousName = _uiState.value.sources.firstOrNull { it.id == id }?.name ?: return
        sourceRepo.renameSource(id, trimmed)
        // A renamed source also appears as the alternate of every module another source won,
        // so the rename has to reach those entries too.
        fun renamed(m: RepoModule): RepoModule {
            val alternates = m.alternateSourceNames.map { if (it == previousName) trimmed else it }
            return when {
                m.sourceId == id -> m.copy(sourceName = trimmed, alternateSourceNames = alternates)
                alternates != m.alternateSourceNames -> m.copy(alternateSourceNames = alternates)
                else -> m
            }
        }
        val sources = sourceRepo.loadSources()
        _uiState.update { st ->
            st.copy(
                sources = sources,
                candidates = candidatesFor(sources),
                modules = st.modules.map(::renamed),
                searchResults = st.searchResults.map(::renamed),
                // 错误的键现在是源地址（见 resolveSourceErrorKeys），改名不影响它；展示用的名字是
                // 渲染时按当前源列表现算的，所以这里不用再跟着改名把键修一遍。
                sourceErrors = st.sourceErrors,
            )
        }
    }

    /**
     * 数据层是按源「名字」上报失败的，而名字允许重复（同一个 host 下的两个索引默认就同名），
     * 重名时后者会把前者顶掉，界面上根本看不出是哪个源失败了。进界面状态前先把名字换成源地址，
     * 让每个源各占一个键。名字对不上任何源（源已被删除或改过名）或对应多个源时保留原键，
     * 由界面在文案里补上能区分的地址。
     */
    /** 关掉一条失败通知，直到下一次抓取结果落地为止。 */
    fun dismissSourceError(message: String) {
        _uiState.update { it.copy(dismissedSourceErrors = it.dismissedSourceErrors + message) }
    }

    private fun resolveSourceErrorKeys(
        sourceErrors: Map<String, String>,
        sources: List<RepoSource>,
    ): Map<String, String> {
        if (sourceErrors.isEmpty()) return sourceErrors
        val resolved = LinkedHashMap<String, String>(sourceErrors.size)
        sourceErrors.forEach { (name, message) ->
            val matched = sources.filter { it.name == name }
            resolved[if (matched.size == 1) matched.first().url else name] = message
        }
        return resolved
    }

    private fun runPendingRefresh() {
        if (pendingRefresh) {
            pendingRefresh = false
            refresh()
        }
    }
}
