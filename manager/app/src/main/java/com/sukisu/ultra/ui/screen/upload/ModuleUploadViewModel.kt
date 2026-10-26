package com.sukisu.ultra.ui.screen.upload

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sukisu.ultra.R
import com.sukisu.ultra.data.model.Module
import com.sukisu.ultra.data.modulezip.ModuleZipArchive
import com.sukisu.ultra.data.repository.ModuleRepository
import com.sukisu.ultra.data.repository.ModuleRepositoryImpl
import com.sukisu.ultra.data.repository.SettingsRepository
import com.sukisu.ultra.data.repository.SettingsRepositoryImpl
import com.sukisu.ultra.ksuApp
import com.sukisu.ultra.ui.component.CoolapkUidState
import com.sukisu.ultra.ui.component.toState
import com.sukisu.ultra.ui.screen.module.ModuleContributionEntry
import com.sukisu.ultra.ui.util.BatchLimits
import com.sukisu.ultra.ui.util.BatchSubmitResult
import com.sukisu.ultra.ui.util.BatchZip
import com.sukisu.ultra.ui.util.CheckStatus
import com.sukisu.ultra.ui.util.ContributionBatch
import com.sukisu.ultra.ui.util.CoolapkUidLookup
import com.sukisu.ultra.ui.util.ModuleContribution
import com.sukisu.ultra.ui.util.SessionCheck
import com.sukisu.ultra.ui.util.SessionGrant
import com.sukisu.ultra.ui.util.UploadSession
import com.sukisu.ultra.ui.util.formatFileSize
import com.sukisu.ultra.ui.util.makeNonce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「模块上传」的唯一状态源：列表页与表单页共用。
 *
 * 一个路由里两屏，共用一个 ViewModel 是刻意的：从列表挑完 zip 之后，
 * 表单要拿到的是**同一批已经落进 staging 的文件**，拆成两个 ViewModel 就得把
 * 文件路径与 sha 在导航参数里来回搬，任何一次重组都可能搬丢。
 *
 * 收录状态只存在内存里（与旧投稿入口同一条理由）：索引随时在变，持久化会让
 * 「已收录」在用户重启 App 之前一直生效。
 */
class ModuleUploadViewModel(
    private val repo: ModuleRepository = ModuleRepositoryImpl(),
    private val archive: ModuleZipArchive = ModuleZipArchive.default(),
    private val settings: SettingsRepository = SettingsRepositoryImpl(),
) : ViewModel() {

    private val _step = MutableStateFlow(UploadStep.LIST)
    val step: StateFlow<UploadStep> = _step.asStateFlow()

    private val _listState = MutableStateFlow(ModuleUploadListState())
    val listState: StateFlow<ModuleUploadListState> = _listState.asStateFlow()

    private val _formState = MutableStateFlow(ModuleUploadFormState())
    val formState: StateFlow<ModuleUploadFormState> = _formState.asStateFlow()

    private var checkJob: Job? = null
    private var loadJob: Job? = null
    private var lookupJob: Job? = null

    /** 已经问过服务端的 id。与「查了但没问出来」（UNKNOWN）区分开，避免刷新时重复查。 */
    private val checkedIds = mutableSetOf<String>()

    private var entered = false

    /** 提交批次号；结果回来时对不上就丢弃（见 [submit]）。 */
    private var submitGeneration = 0

    // ── 列表 ────────────────────────────────────────────────────────────────

    /** 进入页面：拉模块列表 + 服务端限制，然后开查。重复进入不重跑。 */
    fun onEnter() {
        if (entered) return
        entered = true
        // 令牌先读出来：用户一进表单就该看到「已验证」，不该等网络。
        refreshSession()
        startSessionTicker()
        loadModules(thenCheck = true)
    }

    fun refresh() {
        if (_listState.value.refreshing) return
        checkJob?.cancel()
        checkedIds.clear()
        _listState.update {
            it.copy(
                rows = it.rows.map { row -> row.copy(status = null, checking = false) },
                remaining = it.rows.size,
            )
        }
        loadModules(thenCheck = true, refreshing = true)
    }

    fun setFilter(filter: ModuleUploadRules.Filter) {
        _listState.update { it.copy(filter = filter) }
    }

    fun setQuery(query: String) {
        _listState.update { it.copy(query = query) }
    }

    fun toggleExpanded(id: String) {
        _listState.update { it.copy(expandedId = if (it.expandedId == id) null else id) }
    }

    fun consumeMessage() {
        _listState.update { it.copy(message = null) }
    }

    private fun loadModules(thenCheck: Boolean, refreshing: Boolean = false) {
        loadJob?.cancel()
        _listState.update { it.copy(loading = it.rows.isEmpty(), refreshing = refreshing) }
        loadJob = viewModelScope.launch {
            val (modules, archived, usage) = withContext(Dispatchers.IO) {
                val modules = repo.getModules().getOrElse { emptyList() }
                Triple(modules, archive.ids(), archive.usage())
            }
            _listState.update {
                val rows = modules.map { module -> module.toRow(it.rows, archived) }
                it.copy(
                    loading = false,
                    refreshing = false,
                    rows = rows,
                    remaining = rows.count { row -> row.status == null },
                    keepZip = settings.keepModuleZip,
                    archivedCount = usage.entries,
                    archivedBytes = usage.totalBytes,
                )
            }
            if (thenCheck) checkPending()
        }
    }

    /**
     * 把还没查过的模块送去查，一次最多 [ModuleUploadRules.CHECK_CAP] 条。
     *
     * 分批大小取服务端的 `maxModules`（拿不到就 8），同时最多两批在飞：
     * 单批由服务端定大小，客户端只负责别把整张表一次性压上去。
     * 任何一批失败都不改状态——失败就留在「查不了」，绝不猜成「未收录」。
     */
    fun checkPending() {
        if (checkJob?.isActive == true) return
        checkJob = viewModelScope.launch {
            _listState.update { it.copy(checking = true) }
            try {
                val limits = _listState.value.limits ?: fetchLimits()
                val pending = _listState.value.rows
                    .filter { it.status == null && it.id !in checkedIds }
                    .take(ModuleUploadRules.CHECK_CAP)
                if (pending.isEmpty()) return@launch

                pending.forEach { row ->
                    checkedIds += row.id
                }
                markChecking(pending.map { it.id }, checking = true)

                val chunkSize = ModuleUploadRules.checkChunkSize(limits)
                val gate = Semaphore(ModuleUploadRules.CHECK_CONCURRENCY)
                coroutineScope {
                    pending.chunked(chunkSize).map { chunk ->
                        async {
                            gate.withPermit { queryChunk(chunk) }
                        }
                    }.awaitAll()
                }
            } finally {
                // 被 refresh() 取消时不要在这里写状态：新的一轮已经把 checking 打开了，
                // 旧协程再关一次会让界面显示「没在查」而实际还在查。
                if (isActive) {
                    _listState.update {
                        it.copy(checking = false, remaining = it.rows.count { row -> row.status == null })
                    }
                }
            }
        }
    }

    private suspend fun fetchLimits(): BatchLimits? {
        val limits = try {
            ModuleContribution.fetchLimits()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        _listState.update { it.copy(limits = limits) }
        return limits
    }

    private suspend fun queryChunk(chunk: List<UploadRow>) {
        // 版本号超出 Int 的直接算「查不了」：截断会让两个不同版本查到同一个结果。
        val query = chunk.mapNotNull { row ->
            ModuleContributionEntry.checkableVersionCode(row.versionCode)?.let { row.id to it }
        }
        val result = if (query.isEmpty()) {
            emptyMap()
        } else {
            try {
                ModuleContribution.checkBatch(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emptyMap()
            }
        }
        // 只认「同一个 id 且同一个 versionCode」：查的这段时间里列表可能刷新过，
        // 把旧版本的结论写给新版本，等于给用户看了一个不属于这一版的读数。
        val queried = chunk.associateBy { it.id }
        _listState.update { state ->
            state.copy(
                rows = state.rows.map { row ->
                    val asked = queried[row.id]
                    if (asked != null && asked.versionCode == row.versionCode) {
                        row.copy(status = result[row.id] ?: CheckStatus.UNKNOWN, checking = false)
                    } else {
                        row
                    }
                }
            )
        }
    }

    private fun markChecking(ids: List<String>, checking: Boolean) {
        val idSet = ids.toSet()
        _listState.update { state ->
            state.copy(
                rows = state.rows.map { if (it.id in idSet) it.copy(checking = checking) else it }
            )
        }
    }

    // ── 挑 zip ──────────────────────────────────────────────────────────────

    /**
     * 从某一行的详情里挑这个模块的原始 zip。
     *
     * [expectedId] 不为空时要求 zip 里的 id 与这一行一致：不一致说明用户挑错了包，
     * 拿它上传会把这个包登记到另一个模块名下，所以只警告、不加入。
     */
    fun stageForRow(context: Context, uri: Uri, expectedId: String?) {
        // 上传在飞时不再改这一套：文件正被 OkHttp 当请求体读，换包/删包都会让请求半路烂掉。
        if (_formState.value.submitting) return
        val current = _listState.value
        if (current.pickingFor != null) return
        val maxBytes = current.limits?.takeIf { it.usable }?.maxBytes
        if (maxBytes == null) {
            // 拿不到上限就不往 cache 里搬：既没法提前判大小，提交时也一定会被拦。
            postMessage(res.getString(R.string.module_upload_limit_unknown))
            return
        }
        _listState.update { it.copy(pickingFor = expectedId) }
        viewModelScope.launch {
            try {
                when (val result = ModuleUploadStaging.stage(context, uri, maxBytes)) {
                    is ModuleUploadStaging.Result.Ok -> {
                        if (expectedId != null && result.zip.id != expectedId) {
                            File(result.zip.path).delete()
                            postMessage(
                                res.getString(
                                    R.string.module_upload_id_mismatch,
                                    expectedId,
                                    result.zip.id,
                                )
                            )
                        } else {
                            addToForm(listOf(result.zip))
                        }
                    }

                    ModuleUploadStaging.Result.UnreadableModule ->
                        postMessage(res.getString(R.string.module_upload_unreadable_prop))

                    is ModuleUploadStaging.Result.TooBig ->
                        postMessage(stageTooBigMessage(result))

                    ModuleUploadStaging.Result.Failed ->
                        postMessage(res.getString(R.string.module_upload_stage_failed))
                }
            } finally {
                _listState.update { it.copy(pickingFor = null) }
            }
        }
    }

    /**
     * 直接用安装时留存的那份原始包，不再让用户去文件管理器里找。
     *
     * 只在 [UploadRow.archived] 为 true 时才有得用；留存包被淘汰或损坏（`entry` 返回
     * null）时退回「请手动挑」的提示，不静默失败。
     */
    fun stageArchived(row: UploadRow) {
        if (_formState.value.submitting) return
        val current = _listState.value
        if (current.pickingFor != null) return
        val maxBytes = current.limits?.takeIf { it.usable }?.maxBytes
        if (maxBytes == null) {
            postMessage(res.getString(R.string.module_upload_limit_unknown))
            return
        }
        _listState.update { it.copy(pickingFor = row.id) }
        viewModelScope.launch {
            try {
                val entry = withContext(Dispatchers.IO) { archive.entry(row.id) }
                if (entry == null) {
                    // 索引说在、实际读不出来：把这一行的 archived 翻成 false，别再骗人。
                    markArchived(row.id, false)
                    postMessage(res.getString(R.string.module_upload_archived_missing, row.id))
                    return@launch
                }
                when (val result = ModuleUploadStaging.stageFile(entry.file, maxBytes)) {
                    is ModuleUploadStaging.Result.Ok -> {
                        if (result.zip.id != row.id) {
                            // 留存包里的 id 与这一行对不上：文件被换过或索引串了，宁可不用。
                            File(result.zip.path).delete()
                            markArchived(row.id, false)
                            postMessage(
                                res.getString(
                                    R.string.module_upload_id_mismatch,
                                    row.id,
                                    result.zip.id,
                                )
                            )
                        } else {
                            addToForm(listOf(result.zip))
                        }
                    }

                    ModuleUploadStaging.Result.UnreadableModule ->
                        postMessage(res.getString(R.string.module_upload_unreadable_prop))

                    is ModuleUploadStaging.Result.TooBig ->
                        postMessage(stageTooBigMessage(result))

                    ModuleUploadStaging.Result.Failed ->
                        postMessage(res.getString(R.string.module_upload_stage_failed))
                }
            } finally {
                _listState.update { it.copy(pickingFor = null) }
            }
        }
    }

    /** 只改这一行的「有没有留存包」标记，其余状态不动。 */
    private fun markArchived(id: String, archived: Boolean) {
        _listState.update { state ->
            state.copy(
                rows = state.rows.map { row ->
                    if (row.id == id) row.copy(archived = archived) else row
                }
            )
        }
    }

    /**
     * 打开/关闭「安装时留存原始 zip」。
     *
     * 只写设置，**不动已经留存的包**：关开关的语义是「以后别再留新的」，
     * 顺手把旧的删掉属于用户没要求的数据删除。要清空有 [clearArchived]。
     */
    fun setKeepModuleZip(enabled: Boolean) {
        settings.keepModuleZip = enabled
        _listState.update { it.copy(keepZip = enabled) }
    }

    /**
     * 清空所有留存的包，并把每一行的 archived 标记翻回 false。
     *
     * 清完要让界面立刻反映「占用 0 / 没有包可复用」，所以重算 usage 与标记，
     * 而不是等下次刷新。
     */
    fun clearArchived() {
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) {
                val usage = archive.usage()
                archive.clear()
                usage.totalBytes
            }
            _listState.update { state ->
                state.copy(
                    rows = state.rows.map { row -> row.copy(archived = false) },
                    archivedCount = 0,
                    archivedBytes = 0L,
                    message = res.getString(
                        R.string.module_upload_clear_archived_zip_done,
                        formatFileSize(freed),
                    ),
                )
            }
        }
    }

    /** 全局「选择 zip 上传」：多个 uri 一起 stage，能读的都进表单，读不出的逐条提示。 */
    fun stageMany(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (_formState.value.submitting) return
        val maxBytes = _listState.value.limits?.takeIf { it.usable }?.maxBytes
        if (maxBytes == null) {
            postMessage(res.getString(R.string.module_upload_limit_unknown))
            return
        }
        // SAF 多选偶尔会回同一个 uri 两次；按 uri 去重，别把同一个包复制两遍。
        val uniqueUris = uris.distinctBy { it.toString() }
        viewModelScope.launch {
            var unreadable = 0
            var failed = 0
            var tooBig: ModuleUploadStaging.Result.TooBig? = null
            val staged = mutableListOf<UploadZip>()
            try {
                uniqueUris.forEach { uri ->
                    when (val result = ModuleUploadStaging.stage(context, uri, maxBytes)) {
                        is ModuleUploadStaging.Result.Ok -> staged += result.zip
                        ModuleUploadStaging.Result.UnreadableModule -> unreadable++
                        is ModuleUploadStaging.Result.TooBig -> if (tooBig == null) tooBig = result
                        ModuleUploadStaging.Result.Failed -> failed++
                    }
                }
            } catch (e: CancellationException) {
                // 半途离开页面：这一轮已经落盘的那些副本没人接手了，删掉再走。
                staged.forEach { zip -> File(zip.path).delete() }
                throw e
            }
            if (staged.isNotEmpty()) addToForm(staged)
            val oversize = tooBig
            when {
                // 超限最具体，先说它；其余按「读不出」优先于「复制失败」。
                oversize != null -> postMessage(stageTooBigMessage(oversize))
                unreadable > 0 -> postMessage(
                    res.getString(R.string.module_upload_unreadable_prop_count, unreadable)
                )

                failed > 0 -> postMessage(res.getString(R.string.module_upload_stage_failed))
            }
        }
    }

    private fun stageTooBigMessage(result: ModuleUploadStaging.Result.TooBig): String =
        res.getString(
            R.string.module_upload_stage_too_big,
            ModuleUploadRules.formatBytes(result.maxBytes),
        )

    private fun addToForm(zips: List<UploadZip>) {
        if (_formState.value.submitting) {
            // 提交在飞：这一轮 stage 出来的副本没人接手，直接删掉，别留在 cache。
            zips.forEach { zip -> File(zip.path).delete() }
            return
        }
        val before = _formState.value
        // 同一批里同一个 id 只留最后一份：服务端按 id 归并，两条同 id 会让清单自相矛盾。
        // 被顶掉的那一份副本立刻删掉，别在 cache 里留孤儿。
        val unique = LinkedHashMap<String, UploadZip>()
        zips.forEach { zip ->
            val previous = unique.put(zip.id, zip)
            if (previous != null && previous.path != zip.path) File(previous.path).delete()
        }
        _formState.update { state ->
            // 上一批已经提交成功：再挑 zip 就是开一批新的，旧副本删掉、旧条目清空，
            // 免得新提交把上一次投过的模块又带进去一遍。
            val startingNewBatch = state.outcome is UploadOutcome.Success
            if (startingNewBatch) state.items.forEach { old -> File(old.path).delete() }
            val merged = (if (startingNewBatch) emptyList() else state.items).toMutableList()
            unique.values.forEach { zip ->
                val index = merged.indexOfFirst { it.id == zip.id }
                if (index >= 0) {
                    val old = merged[index]
                    if (old.path != zip.path) File(old.path).delete()
                    merged[index] = zip
                } else {
                    merged += zip
                }
            }
            state.copy(
                items = merged,
                totalBytes = merged.sumOf { it.sizeBytes },
                // 上一批已经烧掉的那枚 nonce 不能带进新一批：带过去第一次提交必得
                // `proof_used`，用户还得再抄一次串。
                nonce = if (startingNewBatch) makeNonce() else state.nonce,
                // 墙上高亮的那一枚：开新批就跳到第一枚；追加时不抢用户的视线，
                // 只有原来那枚不在了（被同 id 顶掉）才回落到第一枚。
                selectedPath = if (startingNewBatch) {
                    merged.firstOrNull()?.path
                } else {
                    state.selectedPath?.takeIf { path -> merged.any { it.path == path } }
                        ?: merged.firstOrNull()?.path
                },
                outcome = null,
                notice = null,
            )
        }
        // 第一次进表单时把上次核实通过的 UID 填回来并重新核实：
        // 记的是"上次用过的那串数字"，不是"上次核实通过的结论"——昵称仍以这次反查为准。
        //
        // 有长期令牌且令牌绑的就是这个 UID 时**跳过反查**：那一次问服务端已经拿回了
        // 昵称与头像（见 refreshSession），再打一次 `/user` 只是白耗一次配额；
        // 而且 `/user` 走酷安反查、可能因风控失败，那会把「已验证」降级成「没核实成」，
        // 让用户明明有令牌却提交不了。令牌失效时 refreshSession 会把它清掉，这里自然回到反查。
        if (before.items.isEmpty() && before.uid.isBlank()) {
            val remembered = ModuleUploadPrefs.lastUid()
            val session = before.session?.takeIf { it.validAt(System.currentTimeMillis()) }
            when {
                session != null && session.uid == remembered && remembered.isNotBlank() -> {
                    _formState.update {
                        it.copy(
                            uid = remembered,
                            uidState = CoolapkUidState.Verified(session.nickname),
                        )
                    }
                }

                remembered.isNotBlank() -> updateUid(remembered)
            }
        }
        _step.value = UploadStep.FORM
        checkManual(unique.values.toList())
    }

    /**
     * 手选包也要查收录状态：列表那一份查的是**已安装版本**，与用户刚选的这个 zip
     * 可能不是同一版。查不出来就是 [CheckStatus.UNKNOWN]，不猜成「未收录」。
     */
    private fun checkManual(zips: List<UploadZip>) {
        if (zips.isEmpty()) return
        val checkable = zips.filter {
            ModuleContributionEntry.checkableVersionCode(it.versionCode) != null
        }
        val query = checkable.mapNotNull { zip ->
            ModuleContributionEntry.checkableVersionCode(zip.versionCode)?.let { zip.id to it }
        }
        val byPath = checkable.associateBy { it.path }
        val allIds = zips.map { it.id }.toSet()
        _formState.update { state ->
            state.copy(
                items = state.items.map { item ->
                    when {
                        item.id !in allIds -> item
                        // 版本号超出可查范围：直接算「查不了」，别让它挂在「正在查」。
                        item.path !in byPath -> item.copy(status = CheckStatus.UNKNOWN, checking = false)
                        else -> item.copy(checking = true)
                    }
                }
            )
        }
        if (query.isEmpty()) return
        viewModelScope.launch {
            val result = try {
                ModuleContribution.checkBatch(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emptyMap()
            }
            if (!isActive) return@launch
            _formState.update { state ->
                state.copy(
                    items = state.items.map { item ->
                        // 按 path + versionCode 认这一份：同一个 id 的新版本要被换上来时，
                        // 上一版那次查询的结论不能落到它头上。
                        val asked = byPath[item.path]
                        if (asked != null && item.versionCode == asked.versionCode) {
                            item.copy(status = result[item.id] ?: CheckStatus.UNKNOWN, checking = false)
                        } else {
                            item
                        }
                    }
                )
            }
        }
    }

    private fun postMessage(text: String) {
        _listState.update { it.copy(message = text) }
    }

    // ── 表单 ────────────────────────────────────────────────────────────────

    fun removeItem(path: String) {
        // 上传在飞时不许删：这个文件正是请求体，删掉会让上传半路失败。
        if (_formState.value.submitting) return
        // 移除即删副本：staging 里的文件只为这一批活着，留着就是一份没人认领的 cache。
        File(path).delete()
        _formState.update { state ->
            val remaining = state.items.filterNot { it.path == path }
            state.copy(
                items = remaining,
                totalBytes = remaining.sumOf { it.sizeBytes },
                // 删掉的正是墙上那一枚：回落到剩下的第一枚，否则墙会停在不存在的那一枚上。
                selectedPath = state.selectedPath?.takeIf { it != path && remaining.any { item -> item.path == it } }
                    ?: remaining.firstOrNull()?.path,
                outcome = state.outcome.keepUnlessSuccess(),
            )
        }
    }

    /** 图标墙上点了另一枚：只换「正在编辑的是谁」，不动任何字段。 */
    fun selectItem(path: String) {
        _formState.update { state ->
            if (state.items.none { it.path == path }) state else state.copy(selectedPath = path)
        }
    }

    fun updateSource(path: String, value: String) {
        _formState.update { state ->
            state.copy(
                items = state.items.map { if (it.path == path) it.copy(source = value) else it },
                outcome = state.outcome.keepUnlessSuccess(),
            )
        }
    }

    /** 「应用到全部」：把这一张卡上的出处写给所有条目。 */
    fun applySourceToAll(value: String) {
        _formState.update { state ->
            state.copy(
                items = state.items.map { it.copy(source = value) },
                outcome = state.outcome.keepUnlessSuccess(),
            )
        }
    }

    /**
     * 成功提示不能被一次编辑抹掉。
     *
     * 成功之后这一批已经清空；如果编辑又把 outcome 清成 null，表单会从「已提交」
     * 退回可提交态，用户改一个字就能把同一批再投一遍。
     */
    private fun UploadOutcome?.keepUnlessSuccess(): UploadOutcome? =
        if (this is UploadOutcome.Success) this else null

    fun updateUid(raw: String) {
        val cleaned = ModuleUploadPrefs.sanitizeUid(raw)
        lookupJob?.cancel()
        if (!ModuleContribution.isValidUid(cleaned)) {
            _formState.update { it.copy(uid = cleaned, uidState = CoolapkUidState.Empty) }
            return
        }
        // 手里那张令牌绑的就是这个 UID：直接用令牌里那份身份，不再打一次 `/user`。
        // 这一条不只是省一次请求 —— `/user` 走酷安反查，风控一抖就会把「已验证」
        // 降级成「没核实成」，而令牌本身是服务端签发并仍有效的，比反查更可信。
        val session = _formState.value.session?.takeIf {
            it.validAt(System.currentTimeMillis()) && it.uid == cleaned
        }
        if (session != null) {
            _formState.update {
                it.copy(
                    uid = cleaned,
                    uidState = CoolapkUidState.Verified(session.nickname),
                )
            }
            return
        }
        _formState.update { it.copy(uid = cleaned, uidState = CoolapkUidState.Checking) }
        lookupJob = viewModelScope.launch {
            delay(UID_DEBOUNCE_MS)
            runLookup(cleaned)
        }
    }

    fun retryUidLookup() {
        val uid = _formState.value.uid
        if (!ModuleContribution.isValidUid(uid) || _formState.value.submitting) return
        lookupJob?.cancel()
        _formState.update { it.copy(uidState = CoolapkUidState.Checking) }
        lookupJob = viewModelScope.launch { runLookup(uid) }
    }

    private suspend fun runLookup(uid: String) {
        val result = try {
            ModuleContribution.lookupCoolapkUser(uid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            CoolapkUidLookup.Unavailable
        }
        // 结果回来时用户可能已经改了输入：只认当前输入仍然是这次查的那个 UID 的结论。
        if (_formState.value.uid != uid) return
        _formState.update { it.copy(uidState = result.toState()) }
        if (result is CoolapkUidLookup.Verified) ModuleUploadPrefs.rememberUid(uid)
    }

    fun setConsent(checked: Boolean) {
        _formState.update { it.copy(consent = checked) }
    }

    /**
     * 「我已写进签名」。
     *
     * 勾上时记下**此刻**的集合指纹，而不是一个 true：之后任何 id / 包 / nonce 变化
     * 都会让指纹对不上，勾选自动失效（[ModuleUploadFormState.signatureValid]）。
     */
    fun setSignatureConfirmed(checked: Boolean) {
        _formState.update { state ->
            state.copy(
                confirmedSetKey = if (checked) state.proof?.setKey else null,
            )
        }
    }

    fun consumeNotice() {
        _formState.update { it.copy(notice = null) }
    }

    // ── 长期令牌（九十天免改签名）────────────────────────────────────────────

    /**
     * 进页面时读本地那一张令牌，并顺手问一次服务端它还在不在。
     *
     * 两步都是「只升不降」：
     * - 本地读到就直接放进状态（`validAt` 已经滤掉到期/坏掉的），界面立刻是「已验证」；
     * - 服务端那一次问出结果、且拿回的 uid 与本地一致，就用服务端那份（昵称/头像/到期时刻
     *   都可能是九十天里改过的）；**问不出来什么都不改** —— 网络抖一次不该把用户的便利抹掉。
     */
    fun refreshSession() {
        val stored = ModuleUploadPrefs.readSession()
        if (stored == null) {
            _formState.update { it.copy(session = null, sessionChecking = false) }
            return
        }
        _formState.update { it.copy(session = stored, sessionChecking = true) }
        sessionJob?.cancel()
        // 这一趟的身份：退出/重开之后回来的旧结果一律不许落地（见 [signOut]）。
        val epoch = ++sessionEpoch
        sessionJob = viewModelScope.launch {
            val result = try {
                ModuleContribution.checkSession(stored.token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                SessionCheck.Unavailable
            }
            if (epoch != sessionEpoch) return@launch
            when (result) {
                is SessionCheck.Valid -> {
                    // 服务端换了身份（同一张令牌绑的 uid 变了，理论上不会发生）：以服务端为准。
                    ModuleUploadPrefs.writeSession(result.session)
                    _formState.update {
                        it.copy(session = result.session, sessionChecking = false)
                    }
                }

                // 服务端**明确**说这张失效了（或本地这串形状已坏）：必须抹掉。
                // 留着它就是这条链上的 P0 —— sessionActive 仍为真，签名区整段收起，
                // 用户拿不到「要改签名」的界面，提交必被 403 拒。
                SessionCheck.Invalid -> {
                    ModuleUploadPrefs.clearSession()
                    _formState.update {
                        it.copy(
                            session = null,
                            sessionChecking = false,
                            // 令牌没了，那串口令也没有依据了：勾选必须重新来。
                            confirmedSetKey = null,
                            // 「已验证」这个结论本来是照着令牌写的（见 updateUid 的会话捷径），
                            // 令牌一没就必须回到「未核实」。这里**不**调 updateUid：那是用户
                            // 主动改号才会走的路，它会白打一次 /user 反查，而这次是服务端
                            // 说失效 —— 反查成功也救不回令牌，失败还会盖上一层「没查成」。
                            uidState = if (it.uid.isBlank()) {
                                it.uidState
                            } else {
                                CoolapkUidState.Empty
                            },
                            notice = res.getString(R.string.module_upload_session_expired),
                        )
                    }
                }

                // 没问出来（网络/超时/脏应答）：保留本地那份（它自己会按 expiresAt 到期），
                // 只是不再显示「核实中」。网络抖一次不该把用户九十天的便利抹掉。
                SessionCheck.Unavailable -> {
                    _formState.update { it.copy(sessionChecking = false) }
                }
            }
        }
    }

    /**
     * 心跳：每 [SESSION_TICK_MS] 把 [ModuleUploadFormState.nowTick] 往前推一次。
     *
     * 存在的唯一理由见 `nowTick` 的注释：`sessionActive` 依赖「现在几点」，而时间流逝
     * 本身不会触发重组。手里没有令牌时不动状态（不让整张表单每 30 秒白重组一次）。
     */
    private fun startSessionTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(SESSION_TICK_MS)
                val now = System.currentTimeMillis()
                _formState.update { state ->
                    if (state.session == null) state else state.copy(nowTick = now)
                }
            }
        }
    }

    /**
     * 「退出验证」：本地抹掉 + 吊销服务端那一张。
     *
     * 本地先抹、再发吊销（顺序不能反）：吊销请求可能因为网络挂很久，而用户的意图是
     * 「这台设备立刻不再记住我」—— 界面必须马上回到「要改签名」那条路。
     */
    fun signOut() {
        val current = _formState.value
        val token = current.session?.token
        // 在飞的那一次 /session/check 必须先掐掉，并把 epoch 推一格：它八秒后回来时
        // 会无条件 writeSession + 把界面改回「已验证」，而服务端那张刚刚被吊销 ——
        // 用户点的是「退出验证」，看到的却是自己又被验证了一遍。
        sessionJob?.cancel()
        sessionJob = null
        sessionEpoch++
        ModuleUploadPrefs.clearSession()
        _formState.update {
            it.copy(
                session = null,
                sessionChecking = false,
                confirmedSetKey = null,
                // 令牌没了，「已验证」这个结论也就没有依据了：那条捷径（updateUid 里跳过
                // /user 的分支）是照着会话写的，会话一没就必须回到「未核实」。否则界面会
                // 一直显示「已验证」，而提交判据其实已经要求改签名 —— 说的人和做的人对不上。
                //
                // 这里**不**调 updateUid：那是「用户改了号」才会走的路，它会白打一次
                // /user 反查（烧酷安风控配额），失败还会在「我主动退出」之后盖一层
                // 「没查成」——语义完全对不上。UID 那串数字留在输入框里，用户想回来时
                // 自己点重试即可。
                uidState = if (it.uid.isBlank()) it.uidState else CoolapkUidState.Empty,
            )
        }
        if (token.isNullOrBlank()) return
        // 吊销必须挂在 NonCancellable 上：这个协程原本属于 viewModelScope，用户点完
        // 「退出验证」随手一返回，路由销毁 → 作用域取消 → 请求根本没发出去，而服务端
        // 那张令牌会继续有效九十天。本地抹掉只解决这台设备，不解决那张令牌。
        viewModelScope.launch {
            withContext(NonCancellable) {
                runCatching { ModuleContribution.revokeSession(token) }
            }
        }
    }

    /**
     * 把服务端这一趟签发的令牌收进状态与磁盘。
     *
     * 三态（见 [BatchSubmitResult.session]）：null = 没签，保持原样；合法 = 换上新的
     * （有旧的就在旧的基础上续，身份沿用）；脏数据已经在网络层被滤成 null。
     */
    private fun adoptGrant(grant: SessionGrant?) {
        if (grant == null) return
        val current = _formState.value
        val uid = current.uid.trim()
        val base = current.session?.takeIf { it.uid == uid }
            ?: UploadSession(
                token = grant.token,
                uid = uid,
                nickname = current.nickname,
                avatar = "",
                expiresAt = grant.expiresAt,
            )
        val next = base.renewed(grant)
        if (!next.wellFormed) return
        ModuleUploadPrefs.writeSession(next)
        _formState.update { it.copy(session = next) }
    }

    private var sessionJob: Job? = null

    /**
     * 会话代次：每次 [refreshSession] 领一个号，[signOut] 把它推一格。
     *
     * 协程回来时号对不上就丢弃 —— 与 [submitGeneration] 同一个套路。没有它，
     * 「进页面 → 八秒内点退出验证」就会让在飞的那次核实把令牌写回磁盘与界面。
     */
    private var sessionEpoch = 0

    /** 心跳协程（见 [startSessionTicker]）。 */
    private var tickerJob: Job? = null

    /**
     * 回到列表；表单内容保留，用户再挑一个包时接着上一批。
     *
     * 提交在飞时不放行：回列表后挑新包会让「这一批」在用户不知情时换掉，
     * 而写入结果的那一侧还以为屏幕上仍是刚提交的那一套。
     */
    fun backToList() {
        if (_formState.value.submitting) return
        _step.value = UploadStep.LIST
    }

    /**
     * 提交整批。
     *
     * 顺序：本地判据 → 人机校验 → 上传。
     *
     * 人机校验拿不到 token 就**不提交**（[TurnstileTokenProvider.getToken] 返回 null），
     * 只留一句「人机校验没完成，本次没有提交」；绝不拿空串或假 token 去试。
     *
     * 180 秒超时是整次上传的预算（在 [ModuleContribution.submitBatch] 里）：
     * 批量 zip 可能有几十 MB，但再久用户只会盯着一个不会动的进度条。
     * 契约里的 `submitBatch` 没有字节回调，所以这里只能显示总字节数
     * 与不确定进度——不编一个百分比出来（见 UI 注释）。
     *
     * TODO(batch-progress): 等 `ModuleContribution.submitBatch` 提供
     * `onProgress(sentBytes: Long)` 之后，把 [ModuleUploadFormState.totalBytes] 旁边的
     * 不确定进度换成真实字节进度；在那之前不要用估算值假装进度。
     */
    fun submit(turnstile: TurnstileTokenProvider) {
        val snapshot = _formState.value
        val limits = _listState.value.limits
        // 手里还攥着一张**已过期**的令牌：canSubmit 恒为 false（它只认 sessionMatchesUid），
        // 静默 return 会让用户对着一个灰掉的按钮猜原因。这里明说一句。
        // 正常情况下按钮本来就是禁用的，这条只在「点下去那一刻刚好跨过到期」时命中。
        if (snapshot.session != null && !snapshot.sessionActive) {
            _formState.update {
                it.copy(notice = res.getString(R.string.module_upload_session_expired_submit))
            }
            return
        }
        if (!snapshot.canSubmit(limits) || snapshot.submitting) return
        // 每次提交领一个号：结果回来时如果这一批已经不是当时那一批，就不许写进界面。
        val generation = ++submitGeneration
        viewModelScope.launch {
            _formState.update {
                it.copy(
                    submitting = true,
                    outcome = null,
                    notice = null,
                    totalBytes = snapshot.items.sumOf { item -> item.sizeBytes },
                )
            }
            val token = try {
                turnstile.getToken()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
            if (token.isNullOrBlank()) {
                _formState.update {
                    it.copy(
                        submitting = false,
                        notice = res.getString(R.string.module_upload_turnstile_failed),
                    )
                }
                return@launch
            }

            val batch = snapshot.items.map { item ->
                BatchZip(
                    file = File(item.path),
                    meta = ModuleUploadStaging.metaOf(item),
                    sha256 = item.sha256,
                    source = item.source.trim(),
                )
            }
            val result = ModuleContribution.submitBatch(
                modules = batch,
                uid = snapshot.uid,
                nickname = snapshot.nickname,
                nonce = snapshot.nonce,
                turnstileToken = token,
                limits = limits,
                // 只有「令牌绑的 UID 就是这次提交的 UID」才带上：换了号还带着旧令牌，
                // 服务端会按「签名里没有本次口令」拒，而用户完全看不出为什么。
                sessionToken = snapshot.session?.takeIf { snapshot.sessionMatchesUid }?.token,
            )

            applySubmitResult(result, snapshot.nonce, generation)
        }
    }

    private fun applySubmitResult(
        result: BatchSubmitResult,
        submittedNonce: String,
        generation: Int,
    ) {
        if (generation != submitGeneration) return
        if (result.ok) {
            val count = result.modules.size
            // 服务端这一次可能签了一张新令牌（只有真过了一遍签名核对才会签）。
            // 手里已经有一张就换上新的（续期），没有就按这一次核实到的身份建一张。
            adoptGrant(result.session)
            // 成功这一批留在屏幕上（用户要能对照自己投了哪几枚），但 outcome 是 Success
            // 之后 canSubmit 恒为 false：改出处、改 UID 都不会再放出一次提交。
            // 文件等「下一批」或离开路由时再删（见 addToForm / onCleared）。
            _formState.update {
                it.copy(
                    submitting = false,
                    confirmedSetKey = null,
                    outcome = UploadOutcome.Success(
                        message = result.message.ifBlank {
                            if (count > 0) {
                                res.getString(R.string.module_upload_submit_success_count, count)
                            } else {
                                res.getString(R.string.module_upload_submit_success)
                            }
                        },
                        prUrl = result.prUrl,
                    ),
                )
            }
            return
        }

        // 只有服务端明说「这枚口令用过」才换 nonce（code 优先，与旧入口同一条规则）：
        // 其余失败换掉会让用户刚写进签名的那串立刻作废。
        val proofUsed = result.code == PROOF_USED_CODE
        val message = when {
            result.code == ModuleContribution.CLIENT_TIMEOUT_CODE ->
                res.getString(R.string.module_upload_submit_timeout)

            result.message.isNotBlank() -> result.message
            else -> ContributionBatch.errorCopy(
                status = if (proofUsed) 403 else 0,
                code = result.code,
                serverError = null,
                limits = _listState.value.limits,
            )
        }
        _formState.update { state ->
            state.copy(
                submitting = false,
                outcome = UploadOutcome.Failure(message, proofUsed),
                nonce = if (proofUsed) makeNonce() else submittedNonce,
                // 换 nonce 后旧口令作废：勾选必须重新来。
                confirmedSetKey = if (proofUsed) null else state.confirmedSetKey,
                notice = if (proofUsed) res.getString(R.string.module_upload_proof_rotated) else null,
            )
        }
    }

    /**
     * 路由被弹掉（系统返回 / 退出页面）时，staging 里的副本没有第二个消费者了。
     * 不在这里删，就只能等 6 小时后的 `cleanupStale()`。
     */
    override fun onCleared() {
        sessionJob?.cancel()
        tickerJob?.cancel()
        _formState.value.items.forEach { item -> File(item.path).delete() }
        super.onCleared()
    }

    private val res get() = ksuApp.resources

    private companion object {
        /** 与旧投稿对话框一致：输入停止 500ms 后再核实 UID。 */
        const val UID_DEBOUNCE_MS = 500L

        /**
         * 会话心跳间隔：30 秒。
         *
         * 令牌余量是 60 秒（`UploadSession.VALIDITY_SKEW_MS`），所以最坏情况下界面比
         * 真实到期时刻晚 30 秒才翻成「要改签名」—— 而余量本来就留了一分钟，这个滞后
         * 不会让任何一次提交越过服务端判据。再密一点只是白白重组。
         */
        const val SESSION_TICK_MS = 30_000L

        /** Worker used-nonce 分支的机器可读码（与旧入口同一条约定）。 */
        const val PROOF_USED_CODE = "proof_used"
    }
}

/** 把 `ksud module list` 的一行变成上传列表的一行，尽量保留上一次查到的状态。 */
private fun Module.toRow(previous: List<UploadRow>, archived: Set<String>): UploadRow {
    val old = previous.firstOrNull { it.id == id && it.versionCode == versionCode }
    return UploadRow(
        id = id,
        name = name,
        author = author,
        version = version,
        versionCode = versionCode,
        description = description,
        enabled = enabled,
        remove = remove,
        metamodule = metamodule,
        status = old?.status,
        checking = old?.checking == true,
        archived = ModuleZipArchive.safeId(id) in archived,
    )
}
