package com.sukisu.ultra.ui.screen.upload

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sukisu.ultra.R
import com.sukisu.ultra.data.model.Module
import com.sukisu.ultra.data.repository.ModuleRepository
import com.sukisu.ultra.data.repository.ModuleRepositoryImpl
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
import com.sukisu.ultra.ui.util.makeNonce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    // ── 列表 ────────────────────────────────────────────────────────────────

    /** 进入页面：拉模块列表 + 服务端限制，然后开查。重复进入不重跑。 */
    fun onEnter() {
        if (entered) return
        entered = true
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
            val modules = withContext(Dispatchers.IO) {
                repo.getModules().getOrElse { emptyList() }
            }
            _listState.update {
                val rows = modules.map { module -> module.toRow(it.rows) }
                it.copy(
                    loading = false,
                    refreshing = false,
                    rows = rows,
                    remaining = rows.count { row -> row.status == null },
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
        _listState.update { state ->
            state.copy(
                rows = state.rows.map { row ->
                    when {
                        row.id !in chunk.map { it.id } -> row
                        else -> row.copy(
                            status = result[row.id] ?: CheckStatus.UNKNOWN,
                            checking = false,
                        )
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

    /** 全局「选择 zip 上传」：多个 uri 一起 stage，能读的都进表单，读不出的逐条提示。 */
    fun stageMany(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
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
                outcome = null,
                notice = null,
            )
        }
        // 第一次进表单时把上次核实通过的 UID 填回来并重新核实：
        // 记的是"上次用过的那串数字"，不是"上次核实通过的结论"——昵称仍以这次反查为准。
        if (before.items.isEmpty() && before.uid.isBlank()) {
            val remembered = ModuleUploadPrefs.lastUid()
            if (remembered.isNotBlank()) updateUid(remembered)
        }
        _step.value = UploadStep.FORM
        checkManual(unique.values.toList())
    }

    /**
     * 手选包也要查收录状态：列表那一份查的是**已安装版本**，与用户刚选的这个 zip
     * 可能不是同一版。查不出来就是 [CheckStatus.UNKNOWN]，不猜成「未收录」。
     */
    private fun checkManual(zips: List<UploadZip>) {
        val query = zips.mapNotNull { zip ->
            ModuleContributionEntry.checkableVersionCode(zip.versionCode)?.let { zip.id to it }
        }
        if (query.isEmpty()) return
        val ids = query.map { it.first }.toSet()
        _formState.update { state ->
            state.copy(items = state.items.map { if (it.id in ids) it.copy(checking = true) else it })
        }
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
                        if (item.id in ids) {
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
        // 移除即删副本：staging 里的文件只为这一批活着，留着就是一份没人认领的 cache。
        File(path).delete()
        _formState.update { state ->
            val remaining = state.items.filterNot { it.path == path }
            state.copy(
                items = remaining,
                totalBytes = remaining.sumOf { it.sizeBytes },
                outcome = state.outcome.keepUnlessSuccess(),
            )
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

    /** 回到列表；表单内容保留，用户再挑一个包时接着上一批。 */
    fun backToList() {
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
        if (!snapshot.canSubmit(limits) || snapshot.submitting) return
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
            )

            applySubmitResult(result, snapshot.nonce)
        }
    }

    private fun applySubmitResult(result: BatchSubmitResult, submittedNonce: String) {
        if (result.ok) {
            val count = result.modules.size
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
        _formState.value.items.forEach { item -> File(item.path).delete() }
        super.onCleared()
    }

    private val res get() = ksuApp.resources

    private companion object {
        /** 与旧投稿对话框一致：输入停止 500ms 后再核实 UID。 */
        const val UID_DEBOUNCE_MS = 500L

        /** Worker used-nonce 分支的机器可读码（与旧入口同一条约定）。 */
        const val PROOF_USED_CODE = "proof_used"
    }
}

/** 把 `ksud module list` 的一行变成上传列表的一行，尽量保留上一次查到的状态。 */
private fun Module.toRow(previous: List<UploadRow>): UploadRow {
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
    )
}
