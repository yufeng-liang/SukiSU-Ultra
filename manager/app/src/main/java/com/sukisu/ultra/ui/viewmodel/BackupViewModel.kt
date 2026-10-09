package com.sukisu.ultra.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sukisu.ultra.R
import com.sukisu.ultra.data.backup.BackupEntry
import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin
import com.sukisu.ultra.data.backup.BackupRepository
import com.sukisu.ultra.data.backup.BackupRunResult
import com.sukisu.ultra.data.backup.ModuleBackupMeta
import com.sukisu.ultra.data.backup.RestoreOutcome
import com.sukisu.ultra.data.backup.WebDavPreset
import com.sukisu.ultra.data.backup.WebDavPresets
import com.sukisu.ultra.data.backup.reasonOf
import com.sukisu.ultra.data.repository.ModuleRepositoryImpl
import com.sukisu.ultra.data.repository.SettingsRepositoryImpl
import com.sukisu.ultra.ui.screen.settings.backup.BackupListFormatter
import com.sukisu.ultra.ui.screen.settings.backup.BackupRowLabels
import com.sukisu.ultra.ui.screen.settings.backup.BackupUiState
import com.sukisu.ultra.ui.util.BackupText
import com.sukisu.ultra.ui.util.formatRepoTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 备份页的状态。本地与云端共用一个列表，由 [BackupUiState.origin] 决定读写哪个后端——
 * 云端不能只是"配好地址"，得有真的入口，否则 [BackupRepository] 里的云端方法全是死代码。
 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BackupRepository(application)
    private val settings = SettingsRepositoryImpl()

    private val _uiState = MutableStateFlow(
        BackupUiState(
            cloudUrl = settings.webDavUrl,
            cloudUser = settings.webDavUser,
            cloudPass = settings.webDavPassword,
            cloudConfigured = repository.cloudConfigured(),
            // 上次存的就是某个模板时，把那个预设的高亮恢复回来。
            selectedPreset = WebDavPresets.match(settings.webDavUrl),
            autoBackupEnabled = settings.backupAutoAfterInstall,
            autoBackupRecord = repository.lastAutoBackup(),
        )
    )
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    private var entries: List<BackupEntry> = emptyList()
    private var metas: Map<String, ModuleBackupMeta> = emptyMap()

    init {
        refresh()
        loadModules()
    }

    /**
     * 读已安装模块，供勾选。
     *
     * 默认全选：多数人就是"全都要"，让人从零开始勾只会制造一次多余的点击。
     * 读失败时把 [BackupUiState.modulesUnavailable] 置真，界面据此说明"将备份全部模块"——
     * 静默失败会让人以为勾选功能坏了，而且 [backupNow] 会退化成"全部备份"，界面必须说实话。
     */
    private fun loadModules() = viewModelScope.launch {
        ModuleRepositoryImpl().getModules().fold(
            onSuccess = { modules ->
                _uiState.update { state ->
                    // 保住用户已经取消勾选的那些：重新读列表不该把选择重置掉。
                    val known = state.modules.mapTo(mutableSetOf()) { it.id }
                    val kept = state.selectedModuleIds.intersect(modules.mapTo(mutableSetOf()) { it.id })
                    state.copy(
                        modules = modules,
                        modulesUnavailable = false,
                        selectedModuleIds = if (known.isEmpty()) {
                            modules.mapTo(mutableSetOf()) { it.id }
                        } else {
                            kept
                        },
                    )
                }
            },
            onFailure = { _uiState.update { it.copy(modulesUnavailable = true) } },
        )
    }

    fun toggleModules() = _uiState.update { it.copy(modulesExpanded = !it.modulesExpanded) }

    fun toggleModule(id: String) = _uiState.update { state ->
        state.copy(
            selectedModuleIds = if (id in state.selectedModuleIds) {
                state.selectedModuleIds - id
            } else {
                state.selectedModuleIds + id
            },
        )
    }

    fun setAllModules(selected: Boolean) = _uiState.update { state ->
        state.copy(
            selectedModuleIds = if (selected) {
                state.modules.mapTo(mutableSetOf()) { it.id }
            } else {
                emptySet()
            },
        )
    }

    /**
     * 自动备份开关。
     *
     * 偏好项本来就存在、默认开，只是以前没有界面——用户没法拒绝一个会在后台打包并上传的行为。
     */
    fun setAutoBackup(enabled: Boolean) {
        settings.backupAutoAfterInstall = enabled
        _uiState.update { it.copy(autoBackupEnabled = enabled) }
    }

    fun selectOrigin(origin: BackupOrigin) {
        // 用户主动换来源：上一次操作的结果已经过期，清掉，别让它和新的列表状态混在一起。
        _uiState.update { it.copy(origin = origin, message = null) }
        refresh()
    }

    fun selectKind(kind: BackupKind) {
        _uiState.update { it.copy(kind = kind, message = null) }
        // 上次读模块列表失败（当时没 root、ksud 没起来）就再试一次：切到模块这一栏时
        // 用户马上要看的就是那份列表，不能一直停在"读不到"。
        if (kind == BackupKind.MODULE && _uiState.value.modules.isEmpty()) loadModules()
        refresh()
    }

    /**
     * 展开/收起云端表单。
     *
     * 表单默认收起，只占一行（标题右边就是当前填的地址）；点这一行才铺开。
     */
    fun toggleCloud() = _uiState.update { it.copy(cloudExpanded = !it.cloudExpanded) }

    /**
     * 点了服务商预设：填好地址，把"去哪生成应用密码/要开什么"那句话显示出来，并确保表单是展开的。
     *
     * 提示不清除——用户改地址（Nextcloud 的模板带 `USERNAME`，必须改）时那句话正好是最需要的。
     */
    fun selectPreset(preset: WebDavPreset) {
        _uiState.update {
            it.copy(
                cloudUrl = preset.urlTemplate,
                cloudPresetHintRes = preset.hintRes,
                cloudExpanded = true,
                selectedPreset = preset,
            )
        }
    }

    /**
     * 重新列当前来源与类型。
     *
     * 注意这里**不碰 message**：操作的结果（备份摘要、失败原因）刚写进 message，紧接着就 refresh，
     * 如果 refresh 顺手清掉它，用户在一次主线程 turn 里看不到任何提示——失败会被当成成功。
     * message 只由 [clearMessage]（消费掉之后）、用户主动换来源/类型/服务器，或下一次操作覆盖。
     */
    fun refresh() = viewModelScope.launch {
        val origin = _uiState.value.origin
        val kind = _uiState.value.kind
        // 顺带把自动备份记录重新读一遍：装完模块的自动备份是在别的界面跑完的，
        // 用户回到这一页时才看得到它。
        _uiState.update { it.copy(loading = true, autoBackupRecord = repository.lastAutoBackup()) }
        if (origin == BackupOrigin.CLOUD && !repository.cloudConfigured()) {
            showEmpty(origin, kind, string(R.string.backup_cloud_required))
            return@launch
        }
        val listed = repository.list(origin, kind).getOrElse { error ->
            showEmpty(origin, kind, describe(error))
            return@launch
        }
        // 慢的那次可能后落地：来源/类型已经变了就丢弃这份结果，别用它覆盖新来源的列表。
        if (_uiState.value.origin != origin || _uiState.value.kind != kind) return@launch
        entries = listed
        metas = loadMetas(origin, listed)
        _uiState.update {
            it.copy(
                loading = false,
                rows = BackupListFormatter.rows(listed, metas, rowLabels()),
                // 空列表要说清"为什么空"：模块是"还没备份过"，boot 是"本机根本没有原厂镜像"。
                emptyText = emptyText(origin, kind).takeIf { listed.isEmpty() },
            )
        }
    }

    fun backupNow() = viewModelScope.launch {
        val state = _uiState.value
        val origin = state.origin
        val kind = state.kind
        _uiState.update { it.copy(loading = true, message = null) }
        // 只有模块能挑；boot 那一栏传 null（源里有什么就备什么）。
        // 列表读不出来时也传 null：那是一次读失败，不该变成"什么都没备"。
        val selected = when {
            kind != BackupKind.MODULE -> null
            state.modulesUnavailable -> null
            else -> state.selectedModuleIds
        }
        val result = repository.backup(origin, kind, selected)
        _uiState.update { it.copy(loading = false, message = summary(result)) }
        refresh()
    }

    fun restore(fileName: String) = viewModelScope.launch {
        val origin = _uiState.value.origin
        val entry = entries.firstOrNull { it.fileName == fileName } ?: return@launch
        _uiState.update { it.copy(loading = true, message = null) }
        val outcome = repository.restore(origin, entry)
        _uiState.update {
            it.copy(
                loading = false,
                message = outcome.fold(
                    onSuccess = { value -> outcomeMessage(value, entry) },
                    onFailure = { error -> describe(error) },
                ),
            )
        }
        refresh()
    }

    fun exportTo(uri: Uri, fileName: String) = viewModelScope.launch {
        val origin = _uiState.value.origin
        val entry = entries.firstOrNull { it.fileName == fileName } ?: return@launch
        _uiState.update { it.copy(loading = true, message = null) }
        val result = repository.exportToSaf(origin, entry, uri)
        _uiState.update {
            it.copy(
                loading = false,
                message = result.fold(
                    onSuccess = { string(R.string.backup_exported, entry.entryId) },
                    onFailure = { error -> describe(error) },
                ),
            )
        }
    }

    /**
     * 导入的目标后端**永远是本地**（[BackupRepository.importFromSaf] 写死 localStorage），
     * 所以在云端页点导入之后要切回本地页，否则用户看到"已导入"却在当前列表里找不到那一项。
     * 失败也切：操作本身是本地操作，错误提示该出现在它真正动过的那个列表上。
     */
    fun importFrom(uri: Uri) = viewModelScope.launch {
        // kind 也要跟着回写：导入是按这个 kind 落库的，中途用户换了类型页签的话，
        // refresh() 会去列新类型，导入的那一项就"消失"了。
        val kind = _uiState.value.kind
        _uiState.update { it.copy(loading = true, message = null) }
        val result = repository.importFromSaf(uri, kind)
        _uiState.update {
            it.copy(
                loading = false,
                origin = BackupOrigin.LOCAL,
                kind = kind,
                message = result.fold(
                    onSuccess = { string(R.string.backup_imported) },
                    onFailure = { error -> describe(error) },
                ),
            )
        }
        refresh()
    }

    fun saveCloud(url: String, user: String, pass: String) = viewModelScope.launch {
        settings.webDavUrl = url.trim()
        settings.webDavUser = user.trim()
        settings.webDavPassword = pass
        settings.backupCloudEnabled = url.isNotBlank()
        _uiState.update {
            it.copy(
                cloudUrl = url.trim(),
                cloudUser = user.trim(),
                cloudPass = pass,
                cloudConfigured = repository.cloudConfigured(),
                // 换了服务器，之前那次操作的结果已经不对应当前配置了。
                message = null,
            )
        }
        if (_uiState.value.origin == BackupOrigin.CLOUD) refresh()
    }

    /** 输入框编辑态。三个字段各自更新，互不覆盖。 */
    fun editCloud(url: String? = null, user: String? = null, pass: String? = null) = _uiState.update {
        it.copy(
            cloudUrl = url ?: it.cloudUrl,
            cloudUser = user ?: it.cloudUser,
            cloudPass = pass ?: it.cloudPass,
        )
    }

    fun saveCloudFromState() = saveCloud(_uiState.value.cloudUrl, _uiState.value.cloudUser, _uiState.value.cloudPass)

    /** 测的是输入框里当前的内容：改完地址直接点"测试连接"不该去测上一次保存的地址。 */
    fun testCloud() = viewModelScope.launch {
        _uiState.update { it.copy(loading = true, message = null) }
        val state = _uiState.value
        val result = repository.testCloud(state.cloudUrl, state.cloudUser, state.cloudPass)
        _uiState.update {
            it.copy(
                loading = false,
                message = result.fold(
                    onSuccess = { string(R.string.backup_cloud_test_ok) },
                    onFailure = { error -> describe(error) },
                ),
            )
        }
    }

    /**
     * 提示已经弹过了。
     *
     * 带上 [shown] 做条件清空：弹窗是挂起直到消失的，这期间用户可能又触发一次操作、把新的结果写进
     * message。无条件清空会把那条新消息一起抹掉，而它还没被显示过——root 工具里丢掉一条失败提示
     * 就等于让用户以为成功了。只有当前 message 还是自己消费掉的那条时才清。
     */
    fun clearMessage(shown: String) = _uiState.update {
        if (it.message == shown) it.copy(message = null) else it
    }

    /**
     * 列表读不出来（或云端没配好）。
     *
     * 这里**不覆盖**已有的 message，而是并在一起（见 [BackupListFormatter.mergeMessages]）：
     * `backupNow()` 刚把"written 3 · 某个模块没打包成功"写进 message 就调 refresh()，
     * 如果列表随后也失败，直接覆盖掉就等于告诉用户"什么都没发生"，而备份其实已经写进去了。
     */
    private fun showEmpty(origin: BackupOrigin, kind: BackupKind, text: String) {
        // 慢的那次可能后落地：来源或类型已经变了就别动列表，否则会把当前类型的行清空、并挂上别的错误。
        if (_uiState.value.origin != origin || _uiState.value.kind != kind) return
        entries = emptyList()
        metas = emptyMap()
        _uiState.update {
            it.copy(
                loading = false,
                rows = emptyList(),
                // 列表读不出来时不留空状态文案：那句"还没有备份"会把"没读到"说成"没有"。
                emptyText = null,
                message = BackupListFormatter.mergeMessages(it.message, text),
            )
        }
    }

    /**
     * 列表为空时该说的那句话。
     *
     * boot 与模块要分开：模块空 = 还没备份过（可操作），boot 空 = 本机没有原厂镜像
     * （ksud 只在打补丁时留下它，用户做什么都不会有），这两件事给同一句话就是误导。
     */
    private fun emptyText(origin: BackupOrigin, kind: BackupKind): String = when {
        kind == BackupKind.BOOT && origin == BackupOrigin.CLOUD -> string(R.string.backup_empty_boot_cloud)
        kind == BackupKind.BOOT -> string(R.string.backup_empty_boot_local)
        origin == BackupOrigin.CLOUD -> string(R.string.backup_empty_module_cloud)
        else -> string(R.string.backup_empty_module_local)
    }

    /** 列表里那几段必须跟着语言走的文案，见 [BackupListFormatter.rows]。 */
    private fun rowLabels() = BackupRowLabels(
        bootTitle = string(R.string.backup_boot_row_title),
        disabled = string(R.string.backup_row_disabled),
        formatTime = ::formatRepoTime,
    )

    /**
     * 一次备份的摘要：计数 + 去重后的失败原因。
     *
     * "请用应用密码"那类提示不再单独拼在前面——它已经是凭据被拒这条原因的正文，
     * 拼一遍就变成同一句话出现两次。
     */
    private fun summary(result: BackupRunResult): String = BackupText.summary(context(), result)

    /**
     * 失败原因走数据层的结构化原因再翻译：异常文本只作为第三方细节出现在括号里，
     * 且已经过脱敏（OkHttp 在建 Request 阶段抛的消息会把用户填进 URL 的内容原样带出来）。
     */
    private fun describe(error: Throwable): String = BackupText.reason(context(), reasonOf(error))

    private fun outcomeMessage(outcome: RestoreOutcome, entry: BackupEntry): String =
        if (outcome.success) {
            string(R.string.backup_restored, entry.entryId)
        } else {
            BackupText.restoreFailure(context(), outcome.reason)
        }

    private fun context(): Application = getApplication()

    private fun string(resId: Int, vararg formatArgs: Any): String =
        getApplication<Application>().getString(resId, *formatArgs)

    /**
     * 读边车 meta 用于列表展示。
     *
     * 只读模块的：boot 的边车 meta 是另一套结构（sha1/sha256），硬按模块 meta 解析只会得到
     * 一个名字为空的壳，而 boot 行的标题本来就不看 meta（见 [BackupListFormatter.rows]）。
     */
    private suspend fun loadMetas(origin: BackupOrigin, list: List<BackupEntry>): Map<String, ModuleBackupMeta> =
        list.filter { it.kind == BackupKind.MODULE }.mapNotNull { entry ->
            val metaName = entry.metaFileName ?: return@mapNotNull null
            repository.readMeta(origin, entry)?.let { metaName to it }
        }.toMap()
}
