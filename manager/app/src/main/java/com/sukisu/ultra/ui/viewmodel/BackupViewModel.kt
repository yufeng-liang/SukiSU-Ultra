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
import com.sukisu.ultra.ui.screen.settings.backup.BackupGroup
import com.sukisu.ultra.ui.screen.settings.backup.BackupGrouping
import com.sukisu.ultra.ui.screen.settings.backup.BackupListFormatter
import com.sukisu.ultra.ui.screen.settings.backup.BackupLocation
import com.sukisu.ultra.ui.screen.settings.backup.BackupRow
import com.sukisu.ultra.ui.screen.settings.backup.BackupTab
import com.sukisu.ultra.ui.screen.settings.backup.BackupRowLabels
import com.sukisu.ultra.ui.screen.settings.backup.BackupRunState
import com.sukisu.ultra.ui.screen.settings.backup.BackupTargetResult
import com.sukisu.ultra.ui.screen.settings.backup.BackupUiState
import com.sukisu.ultra.ui.screen.settings.backup.OriginEntry
import com.sukisu.ultra.ui.screen.settings.backup.pruneSelection
import com.sukisu.ultra.ui.util.BackupText
import com.sukisu.ultra.ui.util.formatSessionTime
import com.sukisu.ultra.ui.util.isoToEpochMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 备份页的状态。本机与云端**共用一个列表**（[BackupUiState.origins] 勾了几个就列几个），
 * 每行自带来源，恢复/导出回到它自己那一侧——云端不能只是"配好地址"，得有真的入口，
 * 否则 [BackupRepository] 里的云端方法全是死代码。
 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BackupRepository(application)
    private val settings = SettingsRepositoryImpl()

    private val _uiState = MutableStateFlow(
        BackupUiState(
            cloudUrl = settings.webDavUrl,
            cloudSavedUrl = settings.webDavUrl,
            cloudUser = settings.webDavUser,
            cloudPass = settings.webDavPassword,
            cloudConfigured = repository.cloudConfigured(),
            // 上次存的就是某个模板时，把那个预设的高亮恢复回来。
            selectedPreset = WebDavPresets.match(settings.webDavUrl),
            autoBackupEnabled = settings.backupAutoAfterInstall,
            autoBackupLocal = settings.backupAutoLocal,
            autoBackupCloud = settings.backupAutoCloud,
            autoBackupRecord = repository.lastAutoBackup(),
            moduleRetention = settings.backupRetention,
            bootRetention = settings.backupBootRetention,
        )
    )
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    /** 速度采样的上一次取值；每个目标重新起算。 */
    private var lastSampleAt = 0L
    private var lastSampleBytes = 0L

    /** 当前列表里的条目（带各自的来源）：恢复/导出靠它回到正确的那一侧。 */
    private var entries: List<OriginEntry> = emptyList()
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

    /**
     * 切分页。
     *
     * 顺手把云端表单和模块列表收起来：这两块都是"点开才看"的，换页再回来时留着一堆展开的
     * 表单，刚配好的选项又被顶出屏幕了。多选也跟着退出：勾的是恢复页那份清单，换到备份页
     * 还留着一条"已选 N 份"的操作栏，只会让人以为刚才点错了。
     */
    fun selectTab(tab: BackupTab) = _uiState.update {
        it.copy(
            tab = tab,
            cloudExpanded = false,
            modulesExpanded = false,
            selectedGroupIds = emptySet(),
        )
    }

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

    /** 自动备份写到本机。与手动备份的「备份位置」互不影响。 */
    fun setAutoBackupLocal(enabled: Boolean) {
        settings.backupAutoLocal = enabled
        _uiState.update { it.copy(autoBackupLocal = enabled) }
    }

    /**
     * 自动备份写到云端。
     *
     * 云端没配好时界面把这一项锁住（见 [BackupUiState.autoBackupTargets]），所以这里基本不会
     * 被调到；真调到了也只改偏好，写不写由 `AutoBackupPolicy.targets` 再与配置做一次与运算。
     */
    fun setAutoBackupCloud(enabled: Boolean) {
        settings.backupAutoCloud = enabled
        _uiState.update { it.copy(autoBackupCloud = enabled) }
    }

    /**
     * 保留额度三件套。
     *
     * 存偏好时不另钳一次——`SettingsRepositoryImpl` 的 setter 已经钳了，而这里回写状态用的是
     * **读回来的值**（getter 同样会钳）。两处各钳各的、状态里留原值，就会出现"界面显示 30、
     * 实际按 20 执行"这种只对一半的谎言。
     *
     * 改额度不会去动已经躺在那儿的备份：超出新额度的那些由下一次备份顺带清掉。这里为此专门
     * 跑一轮删除是替用户做决定——他只是调了个数字，没说"现在就把旧的抹了"。
     */
    fun setModuleRetention(value: Int) {
        settings.backupRetention = value
        _uiState.update { it.copy(moduleRetention = settings.backupRetention) }
    }

    fun setBootRetention(value: Int) {
        settings.backupBootRetention = value
        _uiState.update { it.copy(bootRetention = settings.backupBootRetention) }
    }

    /** 保留额度那一块的展开/收起。 */
    fun toggleRetention() = _uiState.update { it.copy(retentionExpanded = !it.retentionExpanded) }

    /**
     * 勾选/取消一个来源。
     *
     * 允许一个都不勾（列表随即变成"请先勾一个"），但**不允许**在用户什么都没勾时按立即备份，
     * 见 [BackupUiState.canBackUp]。
     */
    fun toggleOrigin(origin: BackupOrigin) {
        // 用户主动换来源：上一次操作的结果已经过期，清掉，别让它和新的列表状态混在一起。
        _uiState.update { state ->
            state.copy(
                origins = if (origin in state.origins) state.origins - origin else state.origins + origin,
                message = null,
            )
        }
        refresh()
    }

    fun toggleKind(kind: BackupKind) {
        _uiState.update { state ->
            state.copy(
                kinds = if (kind in state.kinds) state.kinds - kind else state.kinds + kind,
                message = null,
            )
        }
        // 上次读模块列表失败（当时没 root、ksud 没起来）就再试一次：勾上模块时用户马上要看
        // 的就是那份列表，不能一直停在"读不到"。
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
     * 重新列勾选的来源与内容。
     *
     * 注意这里**不碰 message**：操作的结果（备份摘要、失败原因）刚写进 message，紧接着就 refresh，
     * 如果 refresh 顺手清掉它，用户在一次主线程 turn 里看不到任何提示——失败会被当成成功。
     * message 只由 [clearMessage]（消费掉之后）、用户主动换来源/内容/服务器，或下一次操作覆盖。
     */
    /**
     * 上一次 [refresh] 的结果，供调用方在刷新**之后**再说话。
     *
     * 刷新是异步的（[refresh] 返回的是 Job），调用方得先 join 再看这里，否则读到的还是刷新前
     * 的列表。刷新中途用户改了勾选时为 null——那份结果已经过期。
     */
    private var lastRefreshOutcome: RefreshOutcome? = null

    private data class RefreshOutcome(
        val origins: Set<BackupOrigin>,
        val kinds: Set<BackupKind>,
        val listingFailed: Boolean,
    )

    fun refresh() = viewModelScope.launch {
        val origins = _uiState.value.origins
        val kinds = _uiState.value.kinds
        // 顺带把自动备份记录重新读一遍：装完模块的自动备份是在别的界面跑完的，
        // 用户回到这一页时才看得到它。
        _uiState.update { it.copy(loading = true, autoBackupRecord = repository.lastAutoBackup()) }
        if (origins.isEmpty() || kinds.isEmpty()) {
            entries = emptyList()
            metas = emptyMap()
            _uiState.update {
                it.copy(
                    loading = false,
                    rows = emptyList(),
                    groups = emptyList(),
                    openGroupId = null,
                    // 列表都被清空了，勾选跟着清掉：留着也只会是一条点了没反应的操作栏。
                    selectedGroupIds = emptySet(),
                    emptyText = string(R.string.backup_pick_a_target),
                )
            }
            return@launch
        }

        val collected = mutableListOf<OriginEntry>()
        val collectedMetas = mutableMapOf<String, ModuleBackupMeta>()
        var message = _uiState.value.message
        var listingFailed = false
        for (kind in kinds.sortedBy { it.ordinal }) {
            for (origin in origins.sortedBy { it.ordinal }) {
                if (origin == BackupOrigin.CLOUD && !repository.cloudConfigured()) {
                    message = BackupListFormatter.mergeMessages(message, string(R.string.backup_cloud_required))
                    listingFailed = true
                    continue
                }
                val listed = repository.list(origin, kind)
                if (listed.isFailure) {
                    message = BackupListFormatter.mergeMessages(
                        message,
                        describe(listed.exceptionOrNull() ?: IllegalStateException()),
                    )
                    listingFailed = true
                    continue
                }
                val list = listed.getOrThrow()
                collected += list.map { OriginEntry(origin, it) }
                collectedMetas += loadMetas(origin, list)
            }
        }
        // 慢的那次可能后落地：勾选已经变了就丢弃这份结果，别用它覆盖新选择的列表。
        if (_uiState.value.origins != origins || _uiState.value.kinds != kinds) {
            lastRefreshOutcome = null
            return@launch
        }
        lastRefreshOutcome = RefreshOutcome(origins, kinds, listingFailed)
        entries = collected
        metas = collectedMetas
        // 两侧合起来列，最新的在最上面：来源不同不影响"我最近备了什么"这个问题。
        val rows = BackupListFormatter.rows(
            collected.sortedByDescending { item -> isoToEpochMillis(item.entry.createdAt) },
            collectedMetas,
            rowLabels(),
        )
        // 列表按"哪一次备份"分堆：一次备份 11 个模块不该是 11 行。
        val groups = BackupGrouping.group(rows) { iso -> formatSessionTime(context(), iso) }
        _uiState.update { current ->
            // 多选期间列表变了（云端那份在别处被删、保留策略淘汰了旧的一份）：把勾选里已经
            // 不存在的 id 剪掉，一个都不剩就退出多选，别停在一条点了没反应的"已选 0 份"上。
            val stillSelected = pruneSelection(current.selectedGroupIds, groups)
            current.copy(
                loading = false,
                rows = rows,
                groups = groups,
                selectedGroupIds = stillSelected,
                // 空列表要说清"为什么空"：模块是"还没备份过"，boot 是"本机根本没有原厂镜像"。
                // 读取失败时不给空状态文案——那句"还没有备份"会把"没读到"说成"没有"。
                emptyText = if (collected.isEmpty() && !listingFailed) {
                    emptyText(origins, kinds)
                } else {
                    null
                },
                message = message,
            )
        }
    }

    /**
     * 点开一次备份。
     *
     * 这一步只切视图，不碰任何后端：**恢复只由详情页里的按钮触发**。以前列表行点一下就是恢复，
     * 在 root 工具里误触一次就等于把设备拉回上一个状态。
     */
    fun openGroup(group: BackupGroup) = _uiState.update {
        // 默认全勾：进来多半就是"整份恢复"或"整份导出"，让人从零开始勾一次是多余的一步。
        it.copy(openGroupId = group.id, openGroupSelected = group.rows.mapTo(mutableSetOf()) { row -> row.id })
    }

    fun closeGroup() = _uiState.update { it.copy(openGroupId = null, openGroupSelected = emptySet()) }

    /** 长按一行进多选：进来的那一行就是第一个勾中的，手指已经落在它上面了。 */
    fun startSelection(group: BackupGroup) = _uiState.update {
        it.copy(selectedGroupIds = setOf(group.id))
    }

    fun toggleGroupSelection(group: BackupGroup) = _uiState.update { state ->
        val next = if (group.id in state.selectedGroupIds) {
            state.selectedGroupIds - group.id
        } else {
            state.selectedGroupIds + group.id
        }
        // 取消掉最后一个就不再是多选状态（见 BackupUiState.selecting）：留一条"已选 0 份"的
        // 操作栏，除了再点一次取消没别的用。
        state.copy(selectedGroupIds = next)
    }

    /** 退出多选，一份都不删。 */
    fun clearSelection() = _uiState.update { it.copy(selectedGroupIds = emptySet()) }

    /**
     * 删掉多选模式下勾中的那几份。
     *
     * 一份一份删、逐份记结果：勾中的可能横跨本机和云端（列表是把两侧合起来列的），所以删除要
     * 按每一份自己的来源走，也不能因为其中一份失败就把已经删掉的说成没删——和恢复同一套规矩。
     */
    fun deleteSelected() = viewModelScope.launch {
        val selected = _uiState.value.selectedGroups
        if (selected.isEmpty()) return@launch
        _uiState.update { it.copy(loading = true, message = null) }
        var deleted = 0
        val failures = mutableListOf<String>()
        selected.forEach { group ->
            val entries = group.rows.mapNotNull { entryOf(it) }
            repository.delete(group.origin, entries).fold(
                onSuccess = { deleted++ },
                onFailure = { error -> failures += describe(error) },
            )
        }
        _uiState.update {
            it.copy(
                loading = false,
                selectedGroupIds = emptySet(),
                message = (listOfNotNull(
                    deleted.takeIf { it > 0 }?.let { count -> string(R.string.backup_deleted_groups, count) },
                ) + failures.distinct()).joinToString(BackupListFormatter.SEPARATOR),
            )
        }
        // 删掉的可能是"恢复前自动留的那一份"，列表要跟着更新。
        refresh()
    }

    fun toggleGroupEntry(rowId: String) = _uiState.update { state ->
        state.copy(
            openGroupSelected = if (rowId in state.openGroupSelected) {
                state.openGroupSelected - rowId
            } else {
                state.openGroupSelected + rowId
            },
        )
    }

    fun setAllGroupEntries(selected: Boolean) = _uiState.update { state ->
        state.copy(
            openGroupSelected = if (selected) {
                state.openGroup?.rows.orEmpty().mapTo(mutableSetOf()) { it.id }
            } else {
                emptySet()
            },
        )
    }

    /**
     * 恢复勾中的那些条目。
     *
     * 一条一条来、逐个记结果：中途失败不能把已经恢复成功的那几条说成没恢复，也不能因为第一条
     * 失败就放弃后面几条——用户勾了 5 个，最坏情况是"3 个好了、2 个没好"，那就照实说。
     */
    fun restoreSelected() = viewModelScope.launch {
        val selected = _uiState.value.openGroupSelection
        if (selected.isEmpty()) return@launch
        _uiState.update { it.copy(loading = true, message = null) }
        var restored = 0
        val failures = mutableListOf<String>()
        selected.forEach { row ->
            val entry = entryOf(row) ?: return@forEach
            repository.restore(row.origin, entry).fold(
                onSuccess = { outcome ->
                    if (outcome.success) {
                        restored++
                    } else {
                        failures += BackupText.restoreFailure(context(), outcome.reason)
                    }
                },
                onFailure = { error -> failures += describe(error) },
            )
        }
        _uiState.update {
            it.copy(
                loading = false,
                message = (listOf(restoreCountMessage(restored, failures.size)) + failures.distinct())
                    .joinToString(BackupListFormatter.SEPARATOR),
            )
        }
        // 恢复会改动源本身，列表要跟着更新。
        refresh()
    }

    private fun restoreCountMessage(restored: Int, failed: Int): String = when {
        failed == 0 -> string(R.string.backup_restore_done, restored)
        restored == 0 -> string(R.string.backup_restore_all_failed, failed)
        else -> string(R.string.backup_restore_partial, restored, failed)
    }

    /**
     * 导出/分享勾中的那些条目。
     *
     * 打包交给数据层，这里只把文件放进状态：界面拿它拼 Intent（消息通道只有一条字符串）。
     */
    fun shareSelected() = viewModelScope.launch {
        val group = _uiState.value.openGroup ?: return@launch
        val selected = _uiState.value.openGroupSelection
        if (selected.isEmpty()) return@launch
        _uiState.update { it.copy(loading = true, message = null) }
        val entries = selected.mapNotNull { entryOf(it) }
        val result = repository.packForShare(group.origin, entries)
        _uiState.update {
            it.copy(
                loading = false,
                pendingShare = result.getOrNull(),
                message = result.exceptionOrNull()?.let { error -> describe(error) },
            )
        }
    }

    fun consumeShare() = _uiState.update { it.copy(pendingShare = null) }

    /** 删掉这一整组备份。删完退回列表：留在详情页看一个已经不存在的东西没有意义。 */
    fun deleteGroup() = viewModelScope.launch {
        val group = _uiState.value.openGroup ?: return@launch
        val entries = group.rows.mapNotNull { entryOf(it) }
        _uiState.update { it.copy(loading = true, message = null) }
        val result = repository.delete(group.origin, entries)
        _uiState.update {
            it.copy(
                loading = false,
                openGroupId = if (result.isSuccess) null else it.openGroupId,
                openGroupSelected = if (result.isSuccess) emptySet() else it.openGroupSelected,
                message = result.fold(
                    onSuccess = { string(R.string.backup_deleted, entries.size) },
                    onFailure = { error -> describe(error) },
                ),
            )
        }
        refresh()
    }

    /** 行 → 索引里的条目。用行自己的来源：两侧合起来列之后，只看文件名会恢复错那一侧。 */
    private fun entryOf(row: BackupRow): BackupEntry? = entries
        .firstOrNull { it.origin == row.origin && it.entry.fileName == row.fileName }
        ?.entry

    /**
     * 按勾选组合备份：位置 × 内容 每种组合各写一份。
     *
     * 本机与云端**分别调用**（[BackupRepository.backup] 每次只对一个后端），所以云端失败不会
     * 影响本地那份——这正是一次备份两个位置的意义所在。
     */
    fun backupNow() = viewModelScope.launch {
        val state = _uiState.value
        _uiState.update { it.copy(loading = true, message = null) }
        // 只有模块能挑；boot 那一栏传 null（源里有什么就备什么）。
        // 列表读不出来时也传 null：那是一次读失败，不该变成"什么都没备"。
        val selectedModules = when {
            BackupKind.MODULE !in state.kinds -> null
            state.modulesUnavailable -> null
            else -> state.selectedModuleIds
        }
        val targets = state.kinds.sortedBy { it.ordinal }.flatMap { kind ->
            state.origins.sortedBy { it.ordinal }.map { origin -> origin to kind }
        }
        val results = mutableListOf<BackupTargetResult>()
        targets.forEachIndexed { index, (origin, kind) ->
            // 每个目标重新起算进度与速度：上一个目标的速度跟这一个没关系。
            lastSampleAt = 0L
            lastSampleBytes = 0L
            val startedAt = System.currentTimeMillis()
            var sentBytes = 0L
            _uiState.update {
                it.copy(
                    backupRun = BackupRunState(
                        current = index + 1,
                        total = targets.size,
                        origin = origin,
                        kind = kind,
                    ),
                )
            }
            val result = repository.backup(
                origin = origin,
                kind = kind,
                selected = if (kind == BackupKind.MODULE) selectedModules else null,
            ) { progress ->
                val speed = sampleSpeed(progress.sentBytes)
                sentBytes = progress.sentBytes
                _uiState.update { current ->
                    current.copy(
                        backupRun = current.backupRun?.copy(
                            sentBytes = progress.sentBytes,
                            totalBytes = progress.totalBytes,
                            fileIndex = progress.fileIndex,
                            fileCount = progress.fileCount,
                            speedBytesPerSecond = speed,
                        ),
                    )
                }
            }
            // 平均速度只在真的推过东西时算：一次什么都没写的备份（全跳过/没有可备份的）算出
            // 0 B/s 是噪音，而且除以 0 会把耗时那一项也变成假的。
            val elapsedMs = System.currentTimeMillis() - startedAt
            val average = if (sentBytes > 0L && elapsedMs > 0L) sentBytes * 1000L / elapsedMs else 0L
            _uiState.update { current ->
                current.copy(backupRun = current.backupRun?.copy(averageBytesPerSecond = average))
            }
            results += BackupTargetResult(origin, kind, result)
        }
        // 结果留在弹窗里（snackbar 几秒就没了，而"哪一份失败、为什么"值得看清），
        // 所以这里不写 message。
        _uiState.update { current ->
            current.copy(
                loading = false,
                backupRun = current.backupRun?.copy(done = true, result = backupMessage(results)),
            )
        }
        refresh()
    }

    /** 关掉备份弹窗。 */
    fun dismissBackupRun() = _uiState.update { it.copy(backupRun = null) }

    /**
     * 采样算速度。
     *
     * 用相邻两次采样的差值，而不是累计平均：上传中途掉速或卡住时，累计平均会一直显示一个好看的
     * 数字，而那正是用户想知道"到底卡没卡"的时刻。间隔太近的采样不更新——除以很小的 dt 会得到
     * 假的峰值。
     */
    private fun sampleSpeed(sentBytes: Long): Long {
        val now = System.currentTimeMillis()
        val previousAt = lastSampleAt
        val previousBytes = lastSampleBytes
        if (previousAt == 0L || now - previousAt < SPEED_SAMPLE_MS) {
            return _uiState.value.backupRun?.speedBytesPerSecond ?: 0L
        }
        lastSampleAt = now
        lastSampleBytes = sentBytes
        return ((sentBytes - previousBytes).coerceAtLeast(0L) * 1000L) / (now - previousAt)
    }

    /**
     * 导入的目标后端**永远是本地**（[BackupRepository.importFromSaf] 写死 localStorage），
     * 所以导入之后要把本机勾上，否则用户看到"已导入"却在当前列表里找不到那一项。
     * 失败也一样：操作本身是本地操作，提示该出现在它真正动过的那个列表上。
     */
    fun importFrom(uri: Uri) = viewModelScope.launch {
        // 先按勾选猜一个类型交给仓库：它认得归档名（boot_ 前缀是原厂镜像），会以自己认出的为准，
        // 认不出才用这个。猜错了不至于出事，但导入完得按**实际**类型去列，否则刚导进来的那份
        // 不在列表里——"已导入"却找不到东西，和没导一样。
        val guess = _uiState.value.kinds.minByOrNull { it.ordinal } ?: BackupKind.MODULE
        _uiState.update { it.copy(loading = true, message = null) }
        val result = repository.importFromSaf(uri, guess)
        _uiState.update {
            it.copy(
                loading = false,
                origins = it.origins + BackupOrigin.LOCAL,
                kinds = it.kinds + (result.getOrNull() ?: guess),
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
        val configured = repository.cloudConfigured()
        _uiState.update {
            it.copy(
                cloudUrl = url.trim(),
                cloudSavedUrl = url.trim(),
                cloudUser = user.trim(),
                cloudPass = pass,
                cloudConfigured = configured,
                // 存好地址就把云端勾上并立刻去列一次：用户配云端就是为了看它上面已经有什么，
                // 再让他自己勾一次、再等一次刷新是多余的两步。地址被清空时反过来摘掉勾选，
                // 否则列表会一直报"请先配置"。
                origins = if (configured) it.origins + BackupOrigin.CLOUD else it.origins - BackupOrigin.CLOUD,
                // 表单收起来：填完就没有再看那三个输入框的理由，而铺开的表单会把下面刚列出来的
                // 云端清单顶到屏幕外——用户按保存正是为了看那份清单。
                cloudExpanded = false,
                // 说一声"存下了"。原来这里清空 message，保存成功时界面上什么都不动（表单还铺着、
                // 列表还在下面），看起来像没反应，只能靠地址栏那行字自己猜。
                message = string(R.string.backup_cloud_saved),
            )
        }
        // 连不上或凭据不对时，这次刷新会把原因说出来——那正是填完地址最需要的反馈。
        // 必须 join：refresh 是异步的，不等它跑完就去数云端有几份，数的还是刷新前的列表。
        refresh().join()
        // 刷新完再补一句"上面有几份"：用户配云端就是为了知道那儿有没有能恢复的东西，而列表
        // 和刚才那句"已保存"都不回答这个问题。刷新失败时上面那句原因还在，这里不能覆盖它。
        announceCloudContents()
    }

    /**
     * 刚配好的云端上有没有可恢复的东西，有就说出来。
     *
     * 刷新成功却一个字不说时，界面上只有"已保存"和一批突然出现的行——用户分不清那是他刚传上去
     * 的还是本来就在那儿，也不知道那些行能不能恢复。说一句"云端有 N 次备份"就把这件事讲完了。
     *
     * 只在**确实读到了云端**时才说：读失败时 [refresh] 已经给出了原因，那句话比"有几份"重要，
     * 不能被顶掉。云端一份都没有时也不说——那时候列表里的空状态文案正在讲同一件事。
     */
    private fun announceCloudContents() {
        val outcome = lastRefreshOutcome ?: return
        if (outcome.listingFailed || BackupOrigin.CLOUD !in outcome.origins) return
        val cloudGroups = _uiState.value.groups.count { it.origin == BackupOrigin.CLOUD }
        val cloudItems = _uiState.value.groups.filter { it.origin == BackupOrigin.CLOUD }
            .sumOf { it.rows.size }
        if (cloudGroups == 0 || cloudItems == 0) return
        _uiState.update {
            it.copy(
                message = BackupListFormatter.mergeMessages(
                    it.message,
                    string(R.string.backup_cloud_found, cloudGroups, cloudItems),
                )
            )
        }
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
     * 列表为空时该说的那句话。
     *
     * boot 与模块要分开：模块空 = 还没备份过（可操作），boot 空 = 本机没有原厂镜像
     * （ksud 只在打补丁时留下它，用户做什么都不会有），这两件事给同一句话就是误导。
     * 勾了多个来源/内容时四种组合都可能是空的，那就只说"还没有备份"。
     */
    private fun emptyText(origins: Set<BackupOrigin>, kinds: Set<BackupKind>): String =
        if (origins.size == 1 && kinds.size == 1) {
            emptyTextFor(origins.first(), kinds.first())
        } else {
            string(R.string.backup_empty_mixed)
        }

    private fun emptyTextFor(origin: BackupOrigin, kind: BackupKind): String = when {
        kind == BackupKind.BOOT && origin == BackupOrigin.CLOUD -> string(R.string.backup_empty_boot_cloud)
        kind == BackupKind.BOOT -> string(R.string.backup_empty_boot_local)
        origin == BackupOrigin.CLOUD -> string(R.string.backup_empty_module_cloud)
        else -> string(R.string.backup_empty_module_local)
    }

    /** 列表里那几段必须跟着语言走的文案，见 [BackupListFormatter.rows]。 */
    private fun rowLabels() = BackupRowLabels(
        bootTitle = string(R.string.backup_boot_row_title),
        disabled = string(R.string.backup_row_disabled),
        originLabel = { origin -> originLabel(origin) },
    )

    private fun originLabel(origin: BackupOrigin): String = string(
        if (origin == BackupOrigin.CLOUD) R.string.backup_origin_cloud else R.string.backup_origin_local,
    )

    private fun kindLabel(kind: BackupKind): String = string(
        if (kind == BackupKind.BOOT) R.string.backup_kind_boot else R.string.backup_kind_module,
    )

    /**
     * 一次备份的结果。
     *
     * 只勾了一组时就是 [BackupText.summary] 原样（"已写入 N 项 · 跳过 M 项 · 存到 X"）；
     * 勾了多组时每行前面加"来源 · 内容"——否则四条"已写入 N 项"并排，用户分不清哪条是哪边。
     */
    private fun backupMessage(results: List<BackupTargetResult>): String {
        val multiple = results.size > 1
        return results.joinToString(MULTI_TARGET_SEPARATOR) { target ->
            val body = targetBody(target, multiple)
            if (multiple) {
                string(
                    R.string.backup_target_line,
                    originLabel(target.origin),
                    kindLabel(target.kind),
                    body,
                )
            } else {
                body
            }
        }
    }

    /**
     * 单个目标的摘要。
     *
     * 一项都没写、也没跳过、也没有失败，说明源里根本没有可备的东西（boot 就是这种情况：
     * ksud 只在打补丁时留下原厂镜像）。"已写入 0 项 · 跳过 0 项"对用户等于什么都没说。
     */
    private fun targetBody(target: BackupTargetResult, multiple: Boolean): String {
        val result = target.result
        if (result.written.isEmpty() && result.skipped.isEmpty() && result.failures.isEmpty()) {
            return string(
                if (target.kind == BackupKind.BOOT) {
                    R.string.backup_nothing_boot
                } else {
                    R.string.backup_nothing_module
                },
            )
        }
        return BackupText.summary(
            context = context(),
            result = result,
            // 多目标时不再缀位置：几行并排会超出 snackbar 的两行上限，后面那行连失败原因一起
            // 被截掉；位置常驻在按钮下面那一行里，不缺这一句。
            location = if (multiple) {
                null
            } else {
                BackupLocation.short(BackupLocation.full(target.origin, _uiState.value.cloudSavedUrl))
            },
            compact = multiple,
        )
    }

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

    private companion object {
        /**
         * 多个目标的提示各占一行。
         *
         * 不用 [BackupListFormatter.SEPARATOR]：那个分隔符是"同一条消息里的几段"，用它把
         * "本地写了 11 项"和"云端写了 11 项"连起来会读成一条，而这是两件事。
         */
        const val MULTI_TARGET_SEPARATOR = "\n"

        /** 速度采样的最小间隔：比这更密的采样除以很小的 dt 会得到假的峰值。 */
        const val SPEED_SAMPLE_MS = 400L
    }

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
