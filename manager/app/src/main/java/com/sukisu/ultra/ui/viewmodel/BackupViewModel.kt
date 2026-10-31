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
import com.sukisu.ultra.data.backup.CloudProfile
import com.sukisu.ultra.data.backup.CloudProfileBook
import com.sukisu.ultra.data.backup.CloudProfileBookJson
import com.sukisu.ultra.data.backup.CloudSlots
import com.sukisu.ultra.data.backup.CredentialCipher
import com.sukisu.ultra.data.backup.KernelKmi
import com.sukisu.ultra.data.backup.KeystoreKeyProvider
import com.sukisu.ultra.data.backup.ModuleBackupMeta
import com.sukisu.ultra.data.backup.OriginEntry
import com.sukisu.ultra.data.backup.RestoreOutcome
import com.sukisu.ultra.data.backup.WebDavPreset
import com.sukisu.ultra.data.backup.reasonOf
import com.sukisu.ultra.data.repository.ModuleRepositoryImpl
import com.sukisu.ultra.data.repository.SettingsRepositoryImpl
import com.sukisu.ultra.ui.screen.settings.backup.BackupGroup
import com.sukisu.ultra.ui.screen.settings.backup.BackupGrouping
import com.sukisu.ultra.ui.screen.settings.backup.BackupLabels
import com.sukisu.ultra.ui.screen.settings.backup.BackupListFormatter
import com.sukisu.ultra.ui.screen.settings.backup.BackupResult
import com.sukisu.ultra.ui.screen.settings.backup.BackupResultRow
import com.sukisu.ultra.ui.screen.settings.backup.BackupRow
import com.sukisu.ultra.ui.screen.settings.backup.BackupTab
import com.sukisu.ultra.ui.screen.settings.backup.BackupRowLabels
import com.sukisu.ultra.ui.screen.settings.backup.BackupRunState
import com.sukisu.ultra.ui.screen.settings.backup.RestoreRunState
import com.sukisu.ultra.ui.screen.settings.backup.BackupTargetResult
import com.sukisu.ultra.ui.screen.settings.backup.BackupUiState
import com.sukisu.ultra.ui.screen.settings.backup.PartitionKmiCheck
import com.sukisu.ultra.ui.screen.settings.backup.entryToggleSelection
import com.sukisu.ultra.ui.screen.settings.backup.oneCopyPerContent
import com.sukisu.ultra.ui.screen.settings.backup.pruneSelection
import com.sukisu.ultra.ui.screen.settings.backup.snapshotKernelReleaseOf
import com.sukisu.ultra.ui.util.BackupText
import com.sukisu.ultra.ui.util.formatSessionTime
import com.sukisu.ultra.ui.util.isoToEpochMillis
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 备份页的状态。本机与云端**共用一个列表**（[BackupUiState.origins] 勾了几个就列几个），
 * 每行自带来源，恢复/导出回到它自己那一侧——云端不能只是"配好地址"，得有真的入口，
 * 否则 [BackupRepository] 里的云端方法全是死代码。
 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BackupRepository(application)
    private val settings = SettingsRepositoryImpl()

    /**
     * 各家服务商各记一份配置，只有一份生效。
     *
     * 开页面时先把它读回来：上一次填到一半就切走的地址、用户名和密码都还在
     * （见 [CloudProfileBook]）。**不是**只在内存里存——用户切走页面时这个 ViewModel 就没了，
     * 而"下次回来还在"正是他要的。
     */
    private var profiles: CloudProfileBook = CloudProfileBook.migrated(
        CloudProfileBookJson.parse(settings.backupCloudProfiles, decrypt = ::decryptStoredPassword),
        savedUrl = settings.webDavUrl,
    )

    /** 生效的那一份（`settings` 里的三个值），[CloudProfileBook] 拿它当"生效槽位"的真相。 */
    private fun liveProfile() = CloudProfile(
        url = settings.webDavUrl,
        user = settings.webDavUser,
        password = settings.webDavPassword,
    )

    private val _uiState = MutableStateFlow(
        run {
            val live = liveProfile()
            // 没记过"最后在看哪一家"时按生效的那家开局；生效的地址对不上任何模板（手填的）
            // 就落到手填槽位——这正是他上次配的那家。
            val slot = profiles.last ?: profiles.active ?: CloudSlots.CUSTOM
            val shown = profiles.profileFor(slot, live)
            val cloudReady = repository.cloudConfigured()
            // 自动备份位置改成了下拉框，而框里没有"哪都不写"这一档：老版本（两个独立开关）
            // 留下的"两个都关"要落到"只写本机"。而且必须**写回偏好**——只改界面状态的话，
            // 数据层照旧一处都不写（BackupRepository 直接读偏好），下拉框就成了谎话。
            if (!settings.backupAutoLocal && !settings.backupAutoCloud) {
                settings.backupAutoLocal = true
            }
            BackupUiState(
                cloudUrl = shown.url,
                cloudSavedUrl = live.url,
                cloudUser = shown.user,
                cloudPass = shown.password,
                cloudSlot = slot,
                cloudRememberedSlots = profiles.remembered(live),
                cloudConfigured = cloudReady,
                // 备份位置开局就跟"云端配没配好"对齐：配好了就默认本地+云端（两处都写是
                // 备份的常态），没配就只留本地——否则下拉框会显示一个按下去什么也写不出的
                // 「云端」。
                origins = if (cloudReady) {
                    setOf(BackupOrigin.LOCAL, BackupOrigin.CLOUD)
                } else {
                    setOf(BackupOrigin.LOCAL)
                },
                // 提示跟着**槽位**走，不跟地址走：Nextcloud 的模板带 `USERNAME` 占位符，
                // 用户改完地址那一刻恰恰是最需要"去哪生成应用密码"这句话的时候。
                cloudPresetHintRes = CloudSlots.presetOf(slot)?.hintRes,
                autoBackupEnabled = settings.backupAutoAfterInstall,
                autoBackupLocal = settings.backupAutoLocal,
                autoBackupCloud = settings.backupAutoCloud,
                autoBackupRecord = repository.lastAutoBackup(),
                moduleRetention = settings.backupRetention,
                partitionRetention = settings.backupPartitionRetention,
                partitionCompressed = settings.backupPartitionCompressed,
            )
        }
    )
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    /** 速度采样的上一次取值；每个目标重新起算。 */
    private var lastSampleAt = 0L
    private var lastSampleBytes = 0L

    /**
     * 草稿落盘的合并窗口。
     *
     * 每敲一个键就写一次盘是没必要的（还要顺带做一次 AES），但只在离开页面时写又会在进程被杀
     * 时丢掉最后几下——400ms 之后写一次，两种毛病都没有。
     */
    private var persistJob: Job? = null

    /**
     * 草稿落盘。
     *
     * [immediate] 用在两个"再晚就丢了"的时刻：换服务商（用户接下来看到的会是另一家，这一刻
     * 必须已经存住刚才那家）和 [onCleared]（协程作用域马上就没了，防抖那次永远不会跑）。
     */
    private fun persistProfiles(immediate: Boolean = false) {
        persistJob?.cancel()
        val raw = CloudProfileBookJson.render(profiles, encrypt = ::encryptForStorage)
        if (immediate) {
            settings.backupCloudProfiles = raw
            return
        }
        persistJob = viewModelScope.launch {
            delay(PROFILE_PERSIST_DELAY_MS)
            settings.backupCloudProfiles = raw
        }
    }

    /**
     * 页面被销毁时把草稿补写一次。
     *
     * 用户"填了没保存就切到其他页面"正是走到这里：不补这一次，防抖里那次提交会随
     * [viewModelScope] 一起被取消，回来时那几行字就是空的。
     */
    override fun onCleared() {
        settings.backupCloudProfiles = CloudProfileBookJson.render(profiles, encrypt = ::encryptForStorage)
        super.onCleared()
    }

    /** 加密失败（系统密钥库不可用）就返回 null，那一格密码不落盘——明文绝不写进 prefs。 */
    private fun encryptForStorage(plaintext: String): String? =
        runCatching { CredentialCipher.encrypt(KeystoreKeyProvider.key(), plaintext) }.getOrNull()

    private fun decryptStoredPassword(envelope: String): String =
        runCatching { CredentialCipher.decrypt(KeystoreKeyProvider.key(), envelope) }.getOrDefault("")

    /** 把 [profiles] 的变化同步进界面：三个输入框、当前槽位、芯片上的点。 */
    private fun applyProfileBook(slot: String, shown: CloudProfile, book: CloudProfileBook = profiles) =
        _uiState.update {
            it.copy(
                cloudSlot = slot,
                cloudUrl = shown.url,
                cloudUser = shown.user,
                cloudPass = shown.password,
                cloudPresetHintRes = CloudSlots.presetOf(slot)?.hintRes,
                cloudRememberedSlots = book.remembered(liveProfile()),
            )
        }

    /**
     * 正在跑的那次备份；【取消】靠它把这次传输真的掐断。
     *
     * 数据层已经把 [kotlinx.coroutines.CancellationException] 原样上抛（不再当成一次"失败"），
     * 所以取消了就是取消，不会在结果里多出一条莫名其妙的错误。
     */
    private var backupJob: Job? = null
    private var restoreJob: Job? = null

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

    /**
     * 勾选/取消一个模块。
     *
     * 顺手把「模块」那一栏的开关对齐：勾到一个不剩时把它关掉，重新勾上一个时再打开。不留这
     * 一步的话，最后那一个被取消之后 [BackupUiState.canBackUp] 会一直为假——「立即备份」变
     * 灰，而用户想说的其实是"这次不备模块"，不是"什么都别备"。
     */
    fun toggleModule(id: String) = _uiState.update { state ->
        val selected = if (id in state.selectedModuleIds) {
            state.selectedModuleIds - id
        } else {
            state.selectedModuleIds + id
        }
        state.copy(
            selectedModuleIds = selected,
            kinds = if (selected.isEmpty()) {
                state.kinds - BackupKind.MODULE
            } else {
                state.kinds + BackupKind.MODULE
            },
        )
    }

    /**
     * 「模块」那一行的勾选框：既是"这次备不备模块"，也是"全不全选"。
     *
     * 两件事在界面上是同一格（见 `ModuleRowMaterial`），所以在这里也必须一起翻：只改勾选不
     * 改开关，会出现"全不选但这一栏还开着"的死局；只改开关不改勾选，重新打开时又会剩下一份
     * 上一次的子集，看着像全选其实不是。
     */
    fun setAllModules(selected: Boolean) = _uiState.update { state ->
        state.copy(
            selectedModuleIds = if (selected) {
                state.modules.mapTo(mutableSetOf()) { it.id }
            } else {
                emptySet()
            },
            kinds = if (selected) {
                state.kinds + BackupKind.MODULE
            } else {
                state.kinds - BackupKind.MODULE
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

    /**
     * 自动备份写到哪几处（下拉框那一档：本地 / 云端 / 本地+云端）。
     *
     * 与手动备份的「备份位置」是两套互不影响的选择；这里只是把"哪几档"翻译成底下原来那两个
     * 布尔偏好，落盘方式不变（数据层读的还是偏好，所以下拉框显示的和真正会写的一直是同一套）。
     */
    fun setAutoBackupOrigins(origins: Set<BackupOrigin>) {
        val state = _uiState.value
        val local = BackupOrigin.LOCAL in origins
        // 云端没配好时下拉框里根本没有"云端"这一档，此时选"本地"不该把存着的云端偏好抹掉：
        // 地址一配好，用户上次的选择要照原样回来（见 [BackupUiState.autoBackupCloud]）。
        val cloud = if (state.cloudConfigured) {
            BackupOrigin.CLOUD in origins
        } else {
            state.autoBackupCloud
        }
        if (state.autoBackupLocal == local && state.autoBackupCloud == cloud) return
        settings.backupAutoLocal = local
        settings.backupAutoCloud = cloud
        _uiState.update { it.copy(autoBackupLocal = local, autoBackupCloud = cloud) }
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

    fun setPartitionRetention(value: Int) {
        settings.backupPartitionRetention = value
        _uiState.update { it.copy(partitionRetention = settings.backupPartitionRetention) }
    }

    /**
     * 分区快照是否压缩。
     *
     * 只写设置、不重跑备份：下一次导出才读它（源在每次 export 里现读一次），改完立刻生效。
     */
    fun setPartitionCompressed(compressed: Boolean) {
        settings.backupPartitionCompressed = compressed
        _uiState.update { it.copy(partitionCompressed = settings.backupPartitionCompressed) }
    }

    /** 保留额度那一块的展开/收起。 */
    fun toggleRetention() = _uiState.update { it.copy(retentionExpanded = !it.retentionExpanded) }

    /**
     * 勾选/取消一个来源。
     *
     * 来源只决定「立即备份」写到哪，不影响恢复列表（列表始终全量列举，见 [refresh]），
     * 所以这里不刷新。允许一个都不勾，但**不允许**在用户什么都没勾时按立即备份，
     * 见 [BackupUiState.canBackUp]。
     */
    fun toggleOrigin(origin: BackupOrigin) {
        _uiState.update { state ->
            state.copy(
                origins = if (origin in state.origins) state.origins - origin else state.origins + origin,
            )
        }
    }

    /**
     * 整批指定备份位置（下拉框那三项：本地 / 云端 / 本地+云端）。
     *
     * 下拉框里没有"一个都不选"这一项，所以这里也不收空集合——那会让界面显示「本地」而实际
     * 哪儿都不写；真收到空的就按「本地」处理。重选当前那一项直接返回。这里**不需要刷新**：
     * 恢复列表与勾选解耦（见 [refresh]），换位置影响的只是"下次备份写到哪"，不再付一次
     * 云端清单的网络往返。
     */
    fun setOrigins(origins: Set<BackupOrigin>) {
        val next = origins.ifEmpty { setOf(BackupOrigin.LOCAL) }
        if (_uiState.value.origins == next) return
        _uiState.update { it.copy(origins = next) }
    }

    /**
     * 勾选/取消一种备份内容。
     *
     * 勾选只影响「立即备份」写哪些，不影响恢复列表（列表始终全量列举，见 [refresh]），
     * 所以这里不刷新。
     */
    fun toggleKind(kind: BackupKind) {
        _uiState.update { state ->
            state.copy(
                kinds = if (kind in state.kinds) state.kinds - kind else state.kinds + kind,
            )
        }
        // 上次读模块列表失败（当时没 root、ksud 没起来）就再试一次：勾上模块时用户马上要看
        // 的就是那份列表，不能一直停在"读不到"。
        if (kind == BackupKind.MODULE && _uiState.value.modules.isEmpty()) loadModules()
    }

    /**
     * 展开/收起云端表单。
     *
     * 表单默认收起，只占一行（标题右边就是当前填的地址）；点这一行才铺开。
     */
    fun toggleCloud() = _uiState.update { it.copy(cloudExpanded = !it.cloudExpanded) }

    /**
     * 点了服务商芯片：切到这一家，把它自己记着的那份填回表单。
     *
     * "切"不是"覆盖"：刚才那一家填到一半的内容先落进它自己的槽位（[CloudProfileBook.withEdit]
     * 每次编辑都在写），所以来回点几家谁也不丢。这一家要是从没填过，就把模板填进地址栏——
     * 空着的话用户得自己回忆模板长什么样。
     *
     * 落盘用 immediate：接下来屏幕上显示的是另一家，这一刻必须已经存住刚才那家。
     */
    fun selectPreset(preset: WebDavPreset) {
        val slot = preset.id
        val live = liveProfile()
        val remembered = profiles.profileFor(slot, live)
        // 从没碰过这一家（也没有生效的配置）时给一份模板开局。
        val shown = if (remembered.isBlank) CloudProfile(url = preset.urlTemplate) else remembered
        profiles = profiles.withEdit(slot, shown)
        persistProfiles(immediate = true)
        applyProfileBook(slot, shown)
        _uiState.update { it.copy(cloudExpanded = true) }
    }

    /**
     * 上一次 [refresh] 的结果，供调用方在刷新**之后**再说话。
     *
     * 刷新是异步的（[refresh] 返回的是 Job），调用方得先 join 再看这里，否则读到的还是刷新前
     * 的列表。刷新被下一次 [refresh] 取代（Job 被取消）时不再有意义——写它的新一轮会盖上来。
     */
    private var lastRefreshOutcome: RefreshOutcome? = null

    /** 进行中的一轮 [refresh]。新一轮开始时取消上一轮（过期结果不许落回状态）。 */
    private var refreshJob: Job? = null

    private data class RefreshOutcome(
        val origins: Set<BackupOrigin>,
        val listingFailed: Boolean,
    )

    /**
     * 重新列恢复列表。
     *
     * 列表与备份 tab 的勾选**解耦**：不管备份位置选了哪一档、内容勾了哪几样，这里都把本机
     * 和（已配置时）云端的全部可恢复类型列出来。勾选决定的是"备份写到哪、写哪些"，不该反过来
     * 决定"能恢复什么"——用户没勾分区快照只说明这次不备份它，不是不想恢复它。
     *
     * 注意这里**不碰 message**：操作的结果（备份摘要、失败原因）刚写进 message，紧接着就 refresh，
     * 如果 refresh 顺手清掉它，用户在一次主线程 turn 里看不到任何提示——失败会被当成成功。
     * message 只由 [clearMessage]（消费掉之后）或下一次操作覆盖。
     *
     * 返回的是本轮刷新的 Job：调用方要"刷新完之后"再说话就得 join 它。新一轮开始时取消上一轮
     * ——上一轮在云端清单上等网络时用户重进了页面（或配置刚变过），让它把**过期的** origins
     * 集合写进 [lastRefreshOutcome]、把已不存在的行刷回列表，就是自欺。
     */
    fun refresh() = viewModelScope.launch {
        refreshJob?.cancel()
        refreshJob = coroutineContext[Job]
        // 云端只在真的配置好了才去列：没配不是错误（静默跳过，不存在"请先配置云端"的报错——
        // 那句话是给备份动作的），配了却列不出来才是错误，由下面的 describe 把原因说出来。
        val origins = buildSet {
            add(BackupOrigin.LOCAL)
            if (repository.cloudConfigured()) add(BackupOrigin.CLOUD)
        }
        val kinds = BackupKind.LIVE_KINDS
        // 顺带把自动备份记录重新读一遍：装完模块的自动备份是在别的界面跑完的，
        // 用户回到这一页时才看得到它。
        _uiState.update { it.copy(loading = true, autoBackupRecord = repository.lastAutoBackup()) }

        val collected = mutableListOf<OriginEntry>()
        val collectedMetas = mutableMapOf<String, ModuleBackupMeta>()
        var message = _uiState.value.message
        var listingFailed = false
        for (kind in kinds.sortedBy { it.ordinal }) {
            for (origin in origins.sortedBy { it.ordinal }) {
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
        lastRefreshOutcome = RefreshOutcome(origins, listingFailed)
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
                // 空列表只有一种原因：哪儿都没有备份，空状态一句话说完。读取失败时不给空状态
                // 文案——那句"还没有备份"会把"没读到"说成"没有"。
                emptyText = if (collected.isEmpty() && !listingFailed) {
                    string(R.string.backup_empty_mixed)
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
        // 条目列表每次进都从展开起：上一组收起过，不代表这一组也不想看。
        it.copy(
            openGroupId = group.id,
            openGroupSelected = group.rows.mapTo(mutableSetOf()) { row -> row.id },
            openGroupCollapsed = emptySet(),
        )
    }

    fun closeGroup() = _uiState.update {
        it.copy(openGroupId = null, openGroupSelected = emptySet(), openGroupCollapsed = emptySet())
    }

    /** 详情页里点某一段的标题行：展开/收起那一侧的条目。 */
    fun toggleGroupOrigin(origin: BackupOrigin) = _uiState.update { state ->
        state.copy(
            openGroupCollapsed = if (origin in state.openGroupCollapsed) {
                state.openGroupCollapsed - origin
            } else {
                state.openGroupCollapsed + origin
            },
        )
    }

    /**
     * 把某一侧的条目一次全勾上 / 全取消。
     *
     * 两侧分开之后，"全选"是**按侧**算的：本机那一行管本机那几条，云端那一行管云端那几条。
     * 想只恢复云端那份，就是取消本机那一侧——而不是先全选、再一条条把本机的取消掉。
     */
    fun setOriginEntries(origin: BackupOrigin, selected: Boolean) = _uiState.update { state ->
        val ids = state.openGroup?.rows.orEmpty()
            .filter { it.origin == origin }
            .mapTo(mutableSetOf()) { it.id }
        state.copy(
            openGroupSelected = if (selected) {
                state.openGroupSelected + ids
            } else {
                state.openGroupSelected - ids
            },
        )
    }

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
            deleteRows(group.rows).fold(
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

    /**
     * 详情页里勾选/取消一行。
     *
     * 分区快照的行是联动的——点任何一行整组一起动（内核与 ksu.ko 的配对不能拆散，
     * 见 [entryToggleSelection]）；模块、仓库源行维持单选。
     */
    fun toggleGroupEntry(rowId: String) = _uiState.update { state ->
        state.copy(
            openGroupSelected = entryToggleSelection(
                selected = state.openGroupSelected,
                rows = state.openGroup?.rows.orEmpty(),
                rowId = rowId,
            ),
        )
    }

    /**
     * 恢复确认前的 KMI 比对。
     *
     * 返回 null 表示「不用警告」：选的不是分区、快照没记内核版本（旧快照/导入的归档）、
     * 当前的版本读不到、或两侧 KMI 相同——只有真的能认定「KMI 变了」才给结果，
     * 由界面弹升级版确认框（展示两个版本号，不硬拒）。
     *
     * 检查期间把恢复路径锁住（busy）：云端拉 meta 可能要几秒，这段时间按钮和行勾选全被
     * 禁用——不给「等待中又点一次恢复」留窗口（重复的确认弹窗会互相覆盖 continuation）。
     * 快照的内核版本从边车 meta 里读：本机在先、读到第一份非空就停（见
     * [snapshotKernelReleaseOf]），为一次确认把云端的三份 meta 全拉一遍不值。
     */
    suspend fun partitionKmiCheck(selection: List<BackupRow>): PartitionKmiCheck? {
        val partitions = oneCopyPerContent(selection).filter { it.kind == BackupKind.PARTITION }
        if (partitions.isEmpty()) return null
        _uiState.update { it.copy(loading = true) }
        try {
            val snapshotRelease = snapshotKernelReleaseOf(partitions) { row ->
                entryOf(row)?.let { repository.readPartitionMeta(row.origin, it) }
            } ?: return null
            val currentRelease = repository.currentKernelRelease() ?: return null
            return if (KernelKmi.changed(snapshotRelease, currentRelease) == true) {
                PartitionKmiCheck(snapshotRelease = snapshotRelease, currentRelease = currentRelease)
            } else {
                null
            }
        } finally {
            // 检查结束就松开：确认框一弹，去不去恢复由用户定。真去恢复时 restoreSelected
            // 自己会把 loading 再置起来。
            _uiState.update { it.copy(loading = false) }
        }
    }

    /**
     * 恢复 [chosen] 里列出的那些条目。
     *
     * [chosen] 是**确认框弹出的那一刻**的勾选快照（界面用 [oneCopyPerContent] 算好再传进来）：
     * 等待确认的间隙用户再改勾选，改的只是屏幕状态，实际恢复的仍是弹窗里说的那份——
     * 「弹窗说的」和「真正执行的」不许是两份东西。[deduped] 由调用方一并告知（chosen 比
     * 确认时的勾选少，说明同内容两侧只恢复了一份），结果里要专门说一句，不然用户看着
     * 两行会以为恢复了两份。
     *
     * 一条一条来、逐个记结果：中途失败不能把已经恢复成功的那几条说成没恢复，也不能因为第一条
     * 失败就放弃后面几条——用户勾了 5 个，最坏情况是「3 个好了、2 个没好」，那就照实说。
     */
    fun restoreSelected(chosen: List<BackupRow>, deduped: Boolean = false) = viewModelScope.launch {
        if (chosen.isEmpty() || restoreJob?.isActive == true) return@launch
        restoreJob = coroutineContext[Job]
        _uiState.update {
            it.copy(
                loading = true,
                message = null,
                restoreRun = RestoreRunState(current = 0, total = chosen.size, label = ""),
            )
        }
        var restored = 0
        // 恢复了什么决定要不要提示重启：仓库源配置只是整批替换 prefs，仓库页每次打开都现读，
        // 不需要重启；模块和分区快照才需要。只恢复了仓库源时提示"重启生效"是一句谎话。
        val restoredKinds = mutableSetOf<BackupKind>()
        val failures = mutableListOf<String>()
        // 同一份内容本机与云端各有一条（列表把两侧并成了一行）。按内容只恢复一次、优先本机：
        // 两边是同一份内容（读的时候还会按索引里的 sha256 校验），恢复两遍只是把同一个模块装两次、
        // 把同一张镜像刷两次，而"已恢复 2 项"会让用户以为动了两个东西。
        var cancelled = false
        try {
            chosen.forEachIndexed { index, row ->
                _uiState.update {
                    it.copy(
                        restoreRun = RestoreRunState(
                            current = index + 1,
                            total = chosen.size,
                            label = string(BackupLabels.kind(row.kind)),
                        ),
                    )
                }
                val entry = entryOf(row) ?: return@forEachIndexed
                repository.restore(row.origin, entry).fold(
                    onSuccess = { outcome ->
                        if (outcome.success) {
                            restored++
                            restoredKinds += row.kind
                        } else {
                            failures += BackupText.restoreFailure(context(), outcome.reason)
                        }
                    },
                    onFailure = { error -> failures += describe(error) },
                )
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            cancelled = true
        } finally {
            restoreJob = null
        }
        _uiState.update {
            it.copy(
                loading = false,
                restoreRun = RestoreRunState(
                    current = if (cancelled) restored + failures.size else chosen.size,
                    total = chosen.size,
                    label = "",
                    done = true,
                    cancelled = cancelled,
                    result = restoreCountMessage(restored, failures.size),
                    failures = failures.distinct(),
                    needsReboot = restored > 0 && restoredKinds.any { it != BackupKind.REPO_CONFIG },
                ),
                message = buildList {
                    if (cancelled) add(string(R.string.backup_restore_cancelled))
                    add(restoreCountMessage(restored, failures.size))
                    // 两侧各有一份时把"只恢复了一次"说出来：用户看着两行、默认还全选，不说一句
                    // 就不知道到底恢复了几份、动的是哪一份。
                    if (deduped) add(string(R.string.backup_restore_deduped))
                    // 恢复"成功"只是文件到位：模块要重启才加载，刷回去的分区也要重启才真正
                    // 离开 KSU。只说"N 项已恢复"会让用户以为已经在跑了。
                    if (restored > 0 && restoredKinds.any { it != BackupKind.REPO_CONFIG }) {
                        add(string(R.string.reboot_to_apply))
                    }
                    addAll(failures.distinct())
                }.joinToString(BackupListFormatter.SEPARATOR),
            )
        }
        // 恢复会改动源本身，列表要跟着更新。
        refresh()
    }

    fun cancelRestore() {
        val running = restoreJob ?: return
        if (running.isActive) {
            running.cancel()
        }
    }

    fun dismissRestoreRun() {
        if (_uiState.value.restoreRun?.done == true) {
            _uiState.update { it.copy(restoreRun = null) }
        }
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
        if (_uiState.value.openGroup == null) return@launch
        val selected = _uiState.value.openGroupSelection
        if (selected.isEmpty()) return@launch
        _uiState.update { it.copy(loading = true, message = null) }
        val items = selected.mapNotNull { row -> entryOf(row)?.let { OriginEntry(row.origin, it) } }
        val result = repository.packForShare(items)
        _uiState.update {
            it.copy(
                loading = false,
                pendingShare = result.getOrNull(),
                message = result.exceptionOrNull()?.let { error -> describe(error) },
            )
        }
    }

    fun consumeShare() = _uiState.update { it.copy(pendingShare = null) }

    /**
     * 删掉这些行。
     *
     * 合并来源之后，同一组（乃至同一份勾选）都可能横跨本机与云端：按每一行自己的来源分组，
     * 各删各的那一侧。一侧失败不影响另一侧，失败原因照实往上抛。
     */
    private suspend fun deleteRows(rows: List<BackupRow>): Result<Unit> {
        var failure: Throwable? = null
        rows.groupBy { it.origin }.forEach { (origin, sameSide) ->
            val entries = sameSide.mapNotNull { entryOf(it) }
            if (entries.isEmpty()) return@forEach
            repository.delete(origin, entries).onFailure { if (failure == null) failure = it }
        }
        return failure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    /**
     * 删掉详情页里勾中的那些条目。
     *
     * 跟着勾选走，不是"删整组"：这一组本机与云端各有一份时，只勾了本地的人要删的就是本地那份，
     * 顺手把云端那份一起删掉是替他做主。一处都不剩时这一组自然消失，那就退回列表——留在详情页
     * 看一个已经不存在的东西没有意义；还剩一侧时留在原地，继续看剩下的那几条。
     */
    fun deleteChecked() = viewModelScope.launch {
        val selected = _uiState.value.openGroupSelection
        if (selected.isEmpty()) return@launch
        _uiState.update { it.copy(loading = true, message = null) }
        val result = deleteRows(selected)
        _uiState.update {
            it.copy(
                loading = false,
                openGroupSelected = if (result.isSuccess) emptySet() else it.openGroupSelected,
                message = result.fold(
                    // 报的是**这次删掉的行数**。以前这里写的是 entries.size，那是整库的条目数，
                    // 删一条也会说"已删除 37 项备份"。
                    onSuccess = { string(R.string.backup_deleted, selected.size) },
                    onFailure = { error -> describe(error) },
                ),
            )
        }
        refresh()
        _uiState.update { state ->
            state.copy(openGroupId = if (state.openGroup == null) null else state.openGroupId)
        }
    }

    /** 行 → 索引里的条目。用行自己的来源：两侧合起来列之后，只看文件名会恢复错那一侧。 */
    private fun entryOf(row: BackupRow): BackupEntry? = entries
        .firstOrNull { it.origin == row.origin && it.entry.fileName == row.fileName }
        ?.entry

    /**
     * 按勾选组合备份：每种内容**打包一次**，写到勾选的每一处。
     *
     * 目标是"内容"而不是"位置 × 内容"：同一批模块本来就要写到本机和云端，打包两遍纯属白烧
     * CPU（11 个模块几十 MB）。引擎一次调用里依次写每个后端，某一处失败不影响另一处——这正是一次
     * 备份两个位置的意义所在，所以结果仍按来源分开记（见 [BackupTargetResult]）。
     */
    fun backupNow() = viewModelScope.launch {
        val state = _uiState.value
        // 按钮是常亮的（见 `backupTabItems`）：勾得不够时按下去，得在这里把原因说出来。少了
        // 这一步，按钮亮着却什么都不发生——那比灰着更像坏了。
        if (!state.canBackUp) {
            _uiState.update { it.copy(message = string(R.string.backup_pick_a_target)) }
            return@launch
        }
        // 留住这一次备份的句柄，好让用户能取消它。从协程自己身上取，比在外面赋值少一层
        // "谁先谁后"——Main.immediate 下协程体是先跑起来、再轮到外面那行的。
        backupJob = coroutineContext[Job]
        _uiState.update { it.copy(loading = true, message = null) }
        // 只有模块能挑；分区快照那一栏传 null（源里有什么就备什么）。
        // 列表读不出来时也传 null：那是一次读失败，不该变成"什么都没备"。
        val selectedModules = when {
            BackupKind.MODULE !in state.kinds -> null
            state.modulesUnavailable -> null
            else -> state.selectedModuleIds
        }
        val origins = state.origins.sortedBy { it.ordinal }
        val targets = state.kinds.sortedBy { it.ordinal }
        val results = mutableListOf<BackupTargetResult>()
        targets.forEachIndexed { index, kind ->
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
                        kind = kind,
                        // 先按"打包中"开局：这一刻确实什么都还没开始（连源都还没被叫起来），
                        // 弹窗照不确定进度画并写"正在准备"，比"0 B / 0 B"的 0% 进度条诚实。
                        packing = true,
                    ),
                )
            }
            val byOrigin = repository.backup(
                origins = origins,
                kind = kind,
                selected = if (kind == BackupKind.MODULE) selectedModules else null,
                onProgress = { progress ->
                    val speed = sampleSpeed(progress.sentBytes)
                    sentBytes = progress.sentBytes
                    _uiState.update { current ->
                        current.copy(
                            backupRun = current.backupRun?.copy(
                                sentBytes = progress.sentBytes,
                                totalBytes = progress.totalBytes,
                                fileIndex = progress.fileIndex,
                                fileCount = progress.fileCount,
                                // 字节数是跨后端累计的、不回头，所以"现在在写哪一处"要单独记下来。
                                storage = progress.storage,
                                speedBytesPerSecond = speed,
                                // 有字节出去了就说明打包早就结束：这条兜住"源忘了报收尾"
                                // 以及开局那几帧，界面不会一直画不确定进度。
                                packing = false,
                            ),
                        )
                    }
                },
                // 打包那一段（源在产出归档）原来一条消息都没有：弹窗停在 0% 和"0 B / 0 B"上，
                // 而它常常是最慢的一段。这里把"第几个/共几个"透出去，弹窗改画不确定进度。
                onPacking = { packing ->
                    _uiState.update { current ->
                        current.copy(
                            backupRun = current.backupRun?.copy(
                                packing = !packing.finished,
                                packedIndex = packing.index,
                                packedCount = packing.total,
                            ),
                        )
                    }
                },
            )
            // 平均速度只在真的推过东西时算：一次什么都没写的备份（全跳过/没有可备份的）算出
            // 0 B/s 是噪音，而且除以 0 会把耗时那一项也变成假的。
            val elapsedMs = System.currentTimeMillis() - startedAt
            val average = if (sentBytes > 0L && elapsedMs > 0L) sentBytes * 1000L / elapsedMs else 0L
            _uiState.update { current ->
                current.copy(backupRun = current.backupRun?.copy(averageBytesPerSecond = average))
            }
            // 按来源拆开记：结果表一行一个"来源 × 内容"，而整体计数在两侧都写成功时会翻倍。
            origins.forEach { origin ->
                byOrigin[origin]?.let { results += BackupTargetResult(origin, kind, it) }
            }
        }
        // 中途被取消就不再往下走：[cancelBackupRun] 已经把弹窗切成"已取消"，这里再写一次会把
        // 它盖成一次正常完成的结果。列表刷新由取消那一侧等这个协程收尾之后去做。
        if (!isActive) return@launch
        // 结果留在弹窗里（snackbar 几秒就没了，而"哪一份失败、为什么"值得看清），
        // 所以这里不写 message。
        _uiState.update { current ->
            current.copy(
                loading = false,
                backupRun = current.backupRun?.copy(done = true, resultTable = backupResult(results)),
            )
        }
        refresh()
    }

    /** 关掉备份弹窗。 */
    fun dismissBackupRun() = _uiState.update { it.copy(backupRun = null) }

    /**
     * 取消正在跑的那次备份。
     *
     * 已经写好的那几份留着：它们是完整、已入索引的备份，回滚等于删好数据；停下的只是当前这一份。
     * 云端上传卡住时原来只有"杀进程"一条路，那样连已经传成的部分都说不清。
     *
     * 状态在这里就写成"已取消"：取消是投递给协程的，它要等到下一个挂起点才真的停，而这一手在
     * 主线程上立刻完成——用户点了就该看到它。列表则等那个协程真的收尾（[Job.join]）之后再读：
     * 它可能刚好写完了某一份，提前读会把那份漏在屏幕外。
     */
    fun cancelBackupRun() {
        val running = backupJob ?: return
        running.cancel()
        _uiState.update { current ->
            current.copy(
                loading = false,
                backupRun = current.backupRun?.copy(
                    done = true,
                    cancelled = true,
                    result = string(R.string.backup_cancelled),
                ),
            )
        }
        viewModelScope.launch {
            running.join()
            refresh()
        }
    }

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
     * 导入完刷新一次即可：恢复列表与勾选解耦（见 [refresh]），本机的新条目自己就会出现，
     * 不需要再替用户改备份 tab 的勾选。
     */
    fun importFrom(uri: Uri) = viewModelScope.launch {
        // 类型让仓库自己认（归档名 partition_ 前缀是分区快照，仓库会以认出的为准）；
        // 认不出时按模块兜底。退役的 kind（如旧的 BOOT）仓库那边会报错拒绝，传不出去。
        _uiState.update { it.copy(loading = true, message = null) }
        val result = repository.importFromSaf(uri, BackupKind.MODULE)
        _uiState.update {
            it.copy(
                loading = false,
                message = result.fold(
                    onSuccess = { string(R.string.backup_imported) },
                    onFailure = { error -> describe(error) },
                ),
            )
        }
        refresh()
    }

    fun saveCloud(url: String, user: String, pass: String) = viewModelScope.launch {
        // 让位之前先把原来生效的那家收进它自己的槽位：不收的话，用户在别家点保存，这一家的
        // 地址和密码就随着下面三行被覆盖而彻底消失，"配了多家"也就无从谈起。
        val slot = _uiState.value.cloudSlot
        profiles = profiles.withSaved(slot, liveProfile())
        settings.webDavUrl = url.trim()
        settings.webDavUser = user.trim()
        settings.webDavPassword = pass
        settings.backupCloudEnabled = url.isNotBlank()
        // 密码是加密落盘的，系统密钥库不可用时写不进去，而旧的那份已经被撤掉：只看"我写过了"就
        // 报"已保存"，用户会拿着一个空密码去连云端，然后收到一个跟根因无关的 401。回读一次，
        // 读不回来就照实说；表单里也清掉，那份密码确实没留下，不该装作留下了。
        val passwordLost = pass.isNotBlank() && settings.webDavPassword.isBlank()
        val configured = repository.cloudConfigured()
        // 生效的那份就是上面刚写进去的 settings，所以这个槽位不再需要草稿（withSaved 已清掉）；
        // 但它仍是"正在生效"的那家，[CloudProfileBook.remembered] 会让它的点继续亮着。
        persistProfiles(immediate = true)
        _uiState.update {
            it.copy(
                cloudUrl = url.trim(),
                cloudSavedUrl = url.trim(),
                cloudUser = user.trim(),
                cloudPass = if (passwordLost) "" else pass,
                cloudConfigured = configured,
                cloudRememberedSlots = profiles.remembered(liveProfile()),
                // 存好地址就把云端勾上并立刻去列一次：用户配云端就是为了看它上面已经有什么，
                // 再让他自己勾一次、再等一次刷新是多余的两步。地址被清空时反过来摘掉勾选，
                // 否则列表会一直报"请先配置"；摘完一个不剩就落回本地——「备份位置」那个下拉框
                // 没有"一个都不选"这一项，留下空集合会让它显示「本地」而实际哪儿都不写。
                origins = if (configured) {
                    it.origins + BackupOrigin.CLOUD
                } else {
                    (it.origins - BackupOrigin.CLOUD).ifEmpty { setOf(BackupOrigin.LOCAL) }
                },
                // 表单收起来：填完就没有再看那三个输入框的理由，而铺开的表单会把下面刚列出来的
                // 云端清单顶到屏幕外——用户按保存正是为了看那份清单。
                cloudExpanded = false,
                // 说一声"存下了"。原来这里清空 message，保存成功时界面上什么都不动（表单还铺着、
                // 列表还在下面），看起来像没反应，只能靠地址栏那行字自己猜。
                message = string(
                    if (passwordLost) R.string.backup_cloud_credential_failed else R.string.backup_cloud_saved,
                ),
            )
        }
        // 连不上或凭据不对时，这次刷新会把原因说出来——那正是填完地址最需要的反馈。
        // 必须 join：refresh 是异步的，不等它跑完就去数云端有几份，数的还是刷新前的列表。
        refresh().join()
        if (passwordLost) {
            // 这次刷新多半带着 401 回来，那句原因会把真正的根因（密码根本没存进去）顶掉。
            _uiState.update { it.copy(message = string(R.string.backup_cloud_credential_failed)) }
            return@launch
        }
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
        val cloudGroups = _uiState.value.groups.count { BackupOrigin.CLOUD in it.origins }
        val cloudItems = _uiState.value.groups
            .sumOf { group -> group.rows.count { it.origin == BackupOrigin.CLOUD } }
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

    /**
     * 输入框编辑态。三个字段各自更新，互不覆盖。
     *
     * 每敲一下就把这一家的草稿改成当前的样子（防抖落盘）：用户随时可能直接切走，
     * 那一刻来不及再去问"表单里现在是什么"。
     */
    fun editCloud(url: String? = null, user: String? = null, pass: String? = null) {
        val current = _uiState.value
        val edited = CloudProfile(
            url = url ?: current.cloudUrl,
            user = user ?: current.cloudUser,
            password = pass ?: current.cloudPass,
        )
        profiles = profiles.withEdit(current.cloudSlot, edited)
        persistProfiles()
        _uiState.update {
            it.copy(
                cloudUrl = edited.url,
                cloudUser = edited.user,
                cloudPass = edited.password,
                // 填了东西就不再是"什么都没配"，芯片上的点要跟着亮起来。
                cloudRememberedSlots = profiles.remembered(liveProfile()),
            )
        }
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

    /** 列表里那几段必须跟着语言走的文案，见 [BackupListFormatter.rows]。 */
    private fun rowLabels() = BackupRowLabels(
        partitionTitle = string(R.string.backup_partition_row_title),
        compressed = string(R.string.backup_partition_compressed_tag),
        uncompressed = string(R.string.backup_partition_uncompressed_tag),
        repoConfigTitle = string(R.string.backup_kind_repo),
        disabled = string(R.string.backup_row_disabled),
        originLabel = { origin -> originLabel(origin) },
    )

    private fun originLabel(origin: BackupOrigin): String = string(
        if (origin == BackupOrigin.CLOUD) R.string.backup_origin_cloud else R.string.backup_origin_local,
    )

    /**
     * 一次备份的结果，排成弹窗要的那张表。
     *
     * 一行一个目标（来源 × 内容），弹窗按来源分组、排成"内容 / 结果"两列。以前是一串
     * "本地 模块：这 10 项与已有备份内容相同，未重复备份 · 云端 模块：已备份 10 项 · …"：
     * 同一句跳过说明重复到每一行，四条并排看不出哪边写了、哪边没写。
     *
     * 失败清单不占表格的列：原因（HTTP 状态码、文件路径）比"结果"那一列宽得多，塞进去会把
     * 列宽撑歪，所以整行宽度排在表下面。两侧同时失败时文字一样，只留一遍。
     */
    private fun backupResult(results: List<BackupTargetResult>): BackupResult {
        val app = context()
        return BackupResult(
            rows = results.map { target ->
                BackupResultRow(
                    origin = target.origin,
                    kind = target.kind,
                    outcome = BackupText.render(app, BackupText.outcomeCell(target.kind, target.result)),
                )
            },
            failures = BackupText.allFailureLines(app, results.map { it.result }),
            // 表下面那块说明：只在表里真的出现了需要解释的格子时才写（"已是最新"、
            // "没有可备份的原厂镜像"），没出现就是噪音。
            note = BackupText.resultNote(app, results),
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
        /** 速度采样的最小间隔：比这更密的采样除以很小的 dt 会得到假的峰值。 */
        const val SPEED_SAMPLE_MS = 400L

        /** 云端草稿落盘的合并窗口：比一次连打更宽，又不至于让"切走页面"丢掉刚敲的字。 */
        const val PROFILE_PERSIST_DELAY_MS = 400L
    }

    /**
     * 读边车 meta 用于列表展示。
     *
     * 只读模块的：分区快照的边车 meta 是另一套结构（partition/sizeBytes），硬按模块 meta 解析
     * 只会得到一个名字为空的壳，而分区快照行的标题本来就不看 meta（见 [BackupListFormatter.rows]）。
     */
    private suspend fun loadMetas(origin: BackupOrigin, list: List<BackupEntry>): Map<String, ModuleBackupMeta> =
        list.filter { it.kind == BackupKind.MODULE }.mapNotNull { entry ->
            val metaName = entry.metaFileName ?: return@mapNotNull null
            repository.readMeta(origin, entry)?.let { metaName to it }
        }.toMap()
}
