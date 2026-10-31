package com.sukisu.ultra.ui.screen.upload

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
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
import com.sukisu.ultra.ui.component.SearchStatus
import com.sukisu.ultra.ui.component.toState
import com.sukisu.ultra.ui.screen.module.ModuleContributionEntry
import com.sukisu.ultra.ui.util.BatchLimits
import com.sukisu.ultra.ui.util.BatchSubmitResult
import com.sukisu.ultra.ui.util.BatchZip
import com.sukisu.ultra.ui.util.CheckStatus
import com.sukisu.ultra.ui.util.ContributionBatch
import com.sukisu.ultra.ui.util.CoolapkUidLookup
import com.sukisu.ultra.ui.util.IssueOutcome
import com.sukisu.ultra.ui.util.MineModule
import com.sukisu.ultra.ui.util.MineResult
import com.sukisu.ultra.ui.util.ModuleContribution
import com.sukisu.ultra.ui.util.ProgressOutcome
import com.sukisu.ultra.ui.util.ProofItem
import com.sukisu.ultra.ui.util.SessionCheck
import com.sukisu.ultra.ui.util.SessionGrant
import com.sukisu.ultra.ui.util.SubmittedModule
import com.sukisu.ultra.ui.util.UpdateEligibility
import com.sukisu.ultra.ui.util.UploadSession
import com.sukisu.ultra.ui.util.fetchProgress
import com.sukisu.ultra.ui.util.formatFileSize
import com.sukisu.ultra.ui.util.makeNonce
import com.sukisu.ultra.ui.util.progressKey
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
 * 「我投过的模块」（`GET /mine`）的状态。
 *
 * 为什么不是一个 `List<MineModule>` 加一个可空错误串：`/mine` 的三个落点
 * （[MineResult.Ok] / [MineResult.NeedVerify] / [MineResult.Failed]）对用户意味着
 * **完全不同的下一步**——「真的一枚都没投过」要引导去投第一枚，「令牌失效」要引导
 * 重新验证，「拉不下来」只要重试。压成「空列表 + 一句加载失败」，用户就分不清
 * 自己该做什么。
 *
 * [degraded] 尤其不能和「空列表」混为一谈：[degraded] 为真时服务端回的就是空列表，
 * 但那是**索引读不到**，不是「你没有模块」。把它显示成后者，用户会以为投稿丢了，
 * 然后重投一遍。
 */
@Immutable
data class MineState(
    val loading: Boolean = false,
    val modules: List<MineModule> = emptyList(),
    /** 服务端明确说索引读不到：这一份列表不完整，不能当成「全部」。 */
    val degraded: Boolean = false,
    /** 没有令牌，或令牌已失效：需要重新核实酷安 UID。 */
    val needVerify: Boolean = false,
    /** 网络/服务端故障：同一份状态重试即可，不需要重新验证。 */
    val failed: Boolean = false,
    /** 一次性的补充说明（例如「已经有名单，但这次没刷新成功」）；常态提示由界面按上面的标志取。 */
    val message: String? = null,
)

/**
 * 「模块上传」的唯一状态源：列表页与表单页共用。
 *
 * 一个路由里两屏，共用一个 ViewModel 是刻意的：从列表挑完 zip 之后，
 * 表单要拿到的是**同一批已经落进 staging 的文件**，拆成两个 ViewModel 就得把
 * 文件路径与 sha 在导航参数里来回搬，任何一次重组都可能搬丢。
 *
 * 收录状态在这里只做「这一趟」的活数据，跨趟、跨进程复用走
 * [ModuleUploadCheckCache]（30 分钟 TTL，手动刷新绕过）。早先这两件事是同一句话——
 * 「索引随时在变，所以只放内存」——但那样每进一次页面就要把整张表重问一遍，
 * 装上二十个模块就是三次请求，而进出这一页的频率远高于索引变化的频率。
 * 现在改成「短期可信 + 过期即问」，代价是半小时内可能看到一个已经过时的「未收录」。
 */
class ModuleUploadViewModel(
    private val repo: ModuleRepository = ModuleRepositoryImpl(),
    private val archive: ModuleZipArchive = ModuleZipArchive.default(),
    private val settings: SettingsRepository = SettingsRepositoryImpl(),
) : ViewModel() {

    // 首屏默认身份页：磁盘上有没有令牌要等 refreshSession() 读完才知道，
    // 先摆列表会闪一帧「模块列表是空的」，那对没验过身份的人是误导。
    // 有令牌时 refreshSession() 会立刻把这一步推到 LIST，最多一帧。
    private val _step = MutableStateFlow(UploadStep.IDENTITY)
    val step: StateFlow<UploadStep> = _step.asStateFlow()

    private val _listState = MutableStateFlow(ModuleUploadListState())
    val listState: StateFlow<ModuleUploadListState> = _listState.asStateFlow()

    private val _formState = MutableStateFlow(ModuleUploadFormState())
    val formState: StateFlow<ModuleUploadFormState> = _formState.asStateFlow()

    private val _mineState = MutableStateFlow(MineState())
    val mineState: StateFlow<MineState> = _mineState.asStateFlow()

    /**
     * 必须由用户确认一次的长提示（挑错包、版本对不上）。
     *
     * 与 [ModuleUploadListState.message] 分开：那条走 Toast，只给一句话说得完的结果。
     * 这里的两条都要对照两个 id / 两个版本号读，Toast 既会被截断也会自己消失。
     */
    private val _notice = MutableStateFlow<UploadNotice?>(null)
    val notice: StateFlow<UploadNotice?> = _notice.asStateFlow()

    private var checkJob: Job? = null
    private var loadJob: Job? = null
    private var lookupJob: Job? = null
    private var mineJob: Job? = null

    /** 已经问过服务端的 id。与「查了但没问出来」（UNKNOWN）区分开，避免刷新时重复查。 */
    private val checkedIds = mutableSetOf<String>()

    /**
     * 屏幕上那些归属结论是「照哪个 UID 问出来的」。
     *
     * 归属是 (版本, UID) 的二元答案：UID 一换，手上已经问到的答案就全部过期了。
     * 换个号继续沿用前一个号的「是你的」，等于把上一个人的权限借给下一个人看。
     */
    private var eligibilityUid: String = ""

    private var entered = false

    /** 提交批次号；结果回来时对不上就丢弃（见 [submit]）。 */
    private var submitGeneration = 0

    /**
     * 留存目录的世代号：每次「清空留存包」+1。
     *
     * [loadModules] 的快照（ids/usage）是在 IO 线程上读的，读的过程中用户可能已经点了
     * 「清空留存包」。没有这个号的话，那份在清空前读出来的快照会在清空之后落地，
     * 把刚归零的计数与 archived 标记又填回去——界面显示「已留存 3 个」，
     * 而磁盘上明明已经空了，点进去只会得到「包已不在」。
     *
     * 只在主线程读写（ViewModel 的 update 与启动协程都在主线程），不需要额外同步。
     */
    private var archiveEpoch = 0

    // ── 列表 ────────────────────────────────────────────────────────────────

    /** 进入页面：拉模块列表 + 服务端限制，然后开查。重复进入不重跑。 */
    fun onEnter() {
        if (entered) return
        entered = true
        // 验证串先备好：没有就现生成一串。它是这台设备的身份凭据，与令牌一起活——
        // 退出验证（[signOut]）**不**换它，否则用户每次都要回去改一遍酷安签名。
        _formState.update { it.copy(identitySecret = ModuleUploadPrefs.ensureIdentitySecret()) }
        // 令牌先读出来：用户一进表单就该看到「已验证」，不该等网络。
        refreshSession()
        startSessionTicker()
        // 顺手回收上一次进程被杀时留下的暂存副本：onCleared 在那种情况下不会执行，
        // 不在这里清就只能等下一次挑包（而挑包只在 copyIntoStaging 之前顺带清一次）。
        // 当前表单里正在引用的那几份要留着——它们正是「下一批」要接着用的文件。
        val keep = _formState.value.items.map { it.path }
        viewModelScope.launch { ModuleUploadStaging.purgeStale(keep) }
        loadModules(thenCheck = true)
    }

    /**
     * 手动刷新：**绕过缓存**重查一遍。这是用户能要求「就要此刻的结论」的唯一入口。
     *
     * 不再先把 status 清成 null：清掉之后整屏先变成一片「未查」，而这一趟要发的请求
     * 一条都不会少。留着旧标签、只把 checking 打开，界面上是「这些正在重查」，
     * 比「什么都不知道」诚实；查询失败的批也不会把已知的结论抹成「查不了」
     * （见 [queryChunk] 对缺失那一格的处理）。
     */
    fun refresh() {
        if (_listState.value.refreshing) return
        checkJob?.cancel()
        checkedIds.clear()
        loadModules(thenCheck = true, refreshing = true, forceCheck = true)
        // 顶栏那个刷新按钮对整屏负责，不只对「已装模块」负责：同一屏上还挂着
        // 「上传更新」标签，而它读的是 /mine —— 只刷本地扫描的话，标签会停在旧结论上。
        // 两件事互不依赖（一个是本地扫描、一个是带令牌的 GET /mine），并行发即可。
        refreshMine()
    }

    fun setFilter(filter: ModuleUploadRules.Filter) {
        _listState.update { it.copy(filter = filter) }
    }

    /**
     * 同步顶栏搜索状态机；搜索文本变化时写入 [ModuleUploadListState.query]，
     * 与上游 ModuleViewModel/ModuleRepoViewModel 的 updateSearchStatus 同一模式。
     */
    fun updateSearchStatus(status: SearchStatus) {
        _listState.update { it.copy(searchStatus = status, query = status.searchText) }
    }

    fun updateSearchText(text: String) {
        updateSearchStatus(_listState.value.searchStatus.copy(searchText = text))
    }

    fun toggleExpanded(id: String) {
        _listState.update { it.copy(expandedId = if (it.expandedId == id) null else id) }
    }

    fun consumeMessage() {
        _listState.update { it.copy(message = null) }
    }

    /**
     * 弹一条要用户确认的长提示（挑错包 / 版本对不上）。
     *
     * 同一时刻只留一条：连着挑错两个包时后一条顶掉前一条 —— 两条说的是同一件事
     * （这一个包不能用），叠着弹只会让用户点两次才回到表单。
     */
    private fun postNotice(title: String, body: String) {
        _notice.value = UploadNotice(title, body)
    }

    /**
     * 用户点了「知道了」。
     *
     * 与 [consumeNotice] 不是一回事：那条清的是**表单里**的一段一次性文本
     * （提交相关的提醒），这条清的是列表层弹出的确认框。
     */
    fun dismissNotice() {
        _notice.value = null
    }

    private fun loadModules(
        thenCheck: Boolean,
        refreshing: Boolean = false,
        /** 查的时候要不要无视缓存（手动刷新）。 */
        forceCheck: Boolean = false,
    ) {
        loadJob?.cancel()
        _listState.update { it.copy(loading = it.rows.isEmpty(), refreshing = refreshing) }
        val epoch = archiveEpoch
        loadJob = viewModelScope.launch {
            val (modulesResult, archived, usage) = withContext(Dispatchers.IO) {
                Triple(repo.getModules(), archive.ids(), archive.usage())
            }
            // 读快照期间用户清空了留存包：这份快照已经过期，落地只会把计数填回去。
            // 但两个进行中标志必须复位——清空流程不碰它们，早退不还回去的话，
            // 下拉刷新的转圈会一直挂在界面上。
            if (epoch != archiveEpoch) {
                _listState.update { it.copy(loading = false, refreshing = false) }
                return@launch
            }
            val modules = modulesResult.getOrNull()
            if (modules == null) {
                // 「扫描失败」不能当成「一个模块都没装」：后者会让列表被清空、
                // 让孤儿包计数变成全部留存包（顺带把「只清孤儿」变成一次全删）。
                // 保留上一次的行与计数，只把失败说出来。
                _listState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        scanFailed = true,
                        message = res.getString(R.string.module_upload_scan_failed),
                    )
                }
                return@launch
            }
            _listState.update {
                val rows = modules.map { module -> module.toRow(it.rows, archived) }
                // 留存了、但对应模块已经不在安装列表里的那几份：没有行可以复用它们，
                // 单独计数给「只清理这些」的入口用，别让用户为回收它们而清空全部。
                val installed = modules.mapTo(mutableSetOf()) { module -> ModuleZipArchive.safeId(module.id) }
                it.copy(
                    loading = false,
                    refreshing = false,
                    scanFailed = false,
                    rows = rows,
                    remaining = rows.count { row -> row.status == null },
                    keepZip = settings.keepModuleZip,
                    archivedCount = usage.entries,
                    archivedBytes = usage.totalBytes,
                    orphanCount = archived.count { id -> id !in installed },
                )
            }
            if (thenCheck) startCheck(force = forceCheck)
        }
    }

    /**
     * 把还没查过的模块送去查，一次最多 [ModuleUploadRules.CHECK_CAP] 条。
     *
     * 「还没查过」现在有两种：缓存里没有（或已过期），以及这一趟还没问过。所以进页面
     * 与手动刷新走的是同一条路——先把缓存垫上，剩下的才发请求。
     *
     * **UI 目前没有调用它**：列表页那个「继续查」按钮已经删掉（它随 `remaining` 出现/
     * 消失，让整块高度跳变，见 CheckProgressMaterial）。留在这里是因为 `CHECK_CAP` 的
     * 分页上限还在——模块数超过 200 时仍然只有前 200 个有结论，将来若要给这种极端情况
     * 一个入口，接这里就行，不必重新写一遍调度。
     */
    fun checkPending() {
        startCheck(force = false)
    }

    /**
     * 查询主体。
     *
     * [force] = true 时无视缓存、把所有行重问一遍（手动刷新那条路）；否则先拿
     * [ModuleUploadCheckCache] 里没过期的结论垫上，只把剩下的送去服务端。
     *
     * 分批大小取服务端的 `maxModules`（拿不到就 8），同时最多两批在飞：
     * 单批由服务端定大小，客户端只负责别把整张表一次性压上去。
     * 任何一批失败都不改已知状态——没问到的仍留在「查不了」，绝不猜成「未收录」。
     */
    private fun startCheck(force: Boolean) {
        if (checkJob?.isActive == true) return
        checkJob = viewModelScope.launch {
            // 这一趟的结论是不是全部来自缓存：只在「一条请求都没发」时成立。
            var servedFromCache = false
            try {
                val uid = listQueryUid()
                syncEligibilityUid(uid)
                // 缓存先垫：标签在这一刻就定下来，用户不必盯着空标签等网络。
                // 全中的话下面直接返回，`checking` 连亮都不亮——那才是「没在查」。
                val filled = if (force) 0 else applyCached(uid, System.currentTimeMillis())
                // 手动刷新是「全部重问」，判据不能还是「没有结论的那些」：那时候每一行
                // 都还挂着上一趟的旧标签，按 status == null 筛出来会是空集，一次请求都发不出去。
                val rowsNow = _listState.value.rows
                val candidates = if (force) {
                    rowsNow
                } else {
                    rowsNow.filter { it.status == null && it.id !in checkedIds }
                }
                val pending = candidates.take(ModuleUploadRules.CHECK_CAP)
                if (pending.isNotEmpty()) {
                    // 真要发请求了：这批读数不再「全部来自缓存」。
                    _listState.update { it.copy(checking = true, fromCache = false) }
                }
                // 上限即使这一趟一条都不查也得取：表单要靠它做提交前的本地预检，
                // 拿不到就是 LIMITS_UNKNOWN（见 ModuleUploadRules.limitProblem）。
                val limits = _listState.value.limits ?: fetchLimits()
                if (pending.isEmpty()) {
                    servedFromCache = filled > 0
                    return@launch
                }

                pending.forEach { row ->
                    checkedIds += row.id
                }
                markChecking(pending.map { it.id }, checking = true)

                val chunkSize = ModuleUploadRules.checkChunkSize(limits)
                val gate = Semaphore(ModuleUploadRules.CHECK_CONCURRENCY)
                coroutineScope {
                    pending.chunked(chunkSize).map { chunk ->
                        async {
                            gate.withPermit { queryChunk(chunk, uid) }
                        }
                    }.awaitAll()
                }
            } finally {
                // 被 refresh() 取消时不要在这里写状态：新的一轮已经把 checking 打开了，
                // 旧协程再关一次会让界面显示「没在查」而实际还在查。
                if (isActive) {
                    _listState.update {
                        it.copy(
                            checking = false,
                            fromCache = servedFromCache,
                            remaining = it.rows.count { row -> row.status == null },
                        )
                    }
                }
            }
        }
    }

    /**
     * 拿缓存里没过期的结论把还空着的行填上，返回填了几条。
     *
     * 只填 `status == null` 的行：已经有值的那一行，值要么来自这一趟更晚的一次查询、
     * 要么就是上一趟写进缓存的那一份——两种都不比缓存旧，覆盖没有意义。
     *
     * 归属单独判：条目里记着「当时是照哪个 UID 问的」，换了号就只留 [UploadRow.status]，
     * 归属退回 UNKNOWN。否则前一个号的「是你的」会顺着缓存漏给下一个人。
     */
    private suspend fun applyCached(uid: String, now: Long): Int {
        val cached = ModuleUploadCheckCache.read(now)
        if (cached.isEmpty()) return 0
        var filled = 0
        val rows = _listState.value.rows.map { row ->
            if (row.status != null) return@map row
            val entry = cached[row.id] ?: return@map row
            // 键是 (id, versionCode)：模块升级之后，上一版的结论不再适用于这一版。
            if (entry.versionCode != row.versionCode) return@map row
            filled++
            val sameWho = entry.uid == uid
            row.copy(
                status = entry.status,
                eligibility = if (sameWho) entry.eligibility else UpdateEligibility.UNKNOWN,
                eligibilityReason = if (sameWho) entry.reason else "",
            )
        }
        if (filled > 0) _listState.update { it.copy(rows = rows) }
        return filled
    }

    private suspend fun fetchLimits(): BatchLimits? {
        val now = System.currentTimeMillis()
        val memo = limitsMemo
        if (memo != null && memo.usable && now - limitsMemoAt < LIMITS_TTL_MS) {
            _listState.update { it.copy(limits = memo) }
            return memo
        }
        val limits = try {
            ModuleContribution.fetchLimits()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        // 只记取到的那一份：把 null 也缓存下来的话，一次网络抖动会让接下来十分钟里
        // 所有人都卡在「拿不到上限」而不能提交。
        if (limits != null) {
            limitsMemo = limits
            limitsMemoAt = now
        }
        _listState.update { it.copy(limits = limits) }
        return limits
    }

    private suspend fun queryChunk(chunk: List<UploadRow>, uid: String) {
        // 版本号超出 Int 的直接算「查不了」：截断会让两个不同版本查到同一个结果。
        val query = chunk.mapNotNull { row ->
            ModuleContributionEntry.checkableVersionCode(row.versionCode)?.let { row.id to it }
        }
        val result = if (query.isEmpty()) {
            emptyMap()
        } else {
            try {
                // 带上 uid：服务端拿它算 `update_eligible`（这一版更新权归不归我）。
                // 不带的话服务端照常回收录状态，但归属恒为 null —— 「一键更新」
                // 这条路正是靠归属拦「不是你的模块」，不传等于闸门是空的。
                ModuleContribution.checkBatch(query, uid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emptyMap()
            }
        }
        // 缓存只记服务端**真的回了**的那几格：整批失败 / 漏回的那一格是「没问出来」，
        // 把它当结论存下去，接下来半小时都会拿着一个「查不了」不再去问。
        val at = System.currentTimeMillis()
        val fresh = LinkedHashMap<String, ModuleUploadCheckCache.Entry>(chunk.size)
        chunk.forEach { row ->
            result[row.id]?.let { answer ->
                // 「查不出来」不是结论：存下来的话，applyCached 会把它当 status != null 垫上，
                // startCheck 的候选（status == null）就再也挑不到这一行，半小时内一直显示
                // 「查不了」且不再重问。服务端索引降级的时段里这会让整屏卡住。
                if (answer.status == CheckStatus.UNKNOWN) return@let
                fresh[row.id] = ModuleUploadCheckCache.Entry(
                    versionCode = row.versionCode,
                    status = answer.status,
                    uid = uid,
                    eligibility = answer.eligibility,
                    reason = answer.reason,
                    at = at,
                )
            }
        }
        ModuleUploadCheckCache.write(fresh, at)
        // 只认「同一个 id 且同一个 versionCode」：查的这段时间里列表可能刷新过，
        // 把旧版本的结论写给新版本，等于给用户看了一个不属于这一版的读数。
        val queried = chunk.associateBy { it.id }
        _listState.update { state ->
            state.copy(
                rows = state.rows.map { row ->
                    val asked = queried[row.id]
                    if (asked != null && asked.versionCode == row.versionCode) {
                        // 服务端漏了这一格（整批失败 / 没回这个 id）：保留这一行已知的
                        // 结论，没有才算「不知道为什么」。以前这里一律写 UNKNOWN，
                        // 于是一次超时就把刚查到的、或者缓存里的答案抹成「查不了」。
                        // 归属同理：缺失不等于「不是你的」——UNKNOWN 不会让归属闸拦任何东西。
                        val answer = result[row.id]
                        row.copy(
                            status = answer?.status ?: row.status ?: CheckStatus.UNKNOWN,
                            eligibility = answer?.eligibility ?: row.eligibility,
                            eligibilityReason = answer?.reason ?: row.eligibilityReason,
                            checking = false,
                        )
                    } else {
                        row
                    }
                }
            )
        }
    }

    /**
     * 列表页问归属时用的 UID。
     *
     * 优先级：表单里**核实过**的那个（用户刚刚证明了自己是谁）→ 手里这张有效令牌
     * 绑的那个（服务端签发时就绑定了，比任何本地记录硬）→ 上次核实通过时记下的那串。
     * 都没有就回空串：服务端见空 uid 会把 `update_eligible` 回成 null（UNKNOWN），
     * 归属闸保持「不知道」，而不是拿一个没核实过的号去问出个假答案。
     */
    private fun listQueryUid(): String {
        verifiedFormUid().takeIf { it.isNotEmpty() }?.let { return it }
        val session = _formState.value.session?.takeIf { it.validAt(System.currentTimeMillis()) }
        if (session != null && session.uid.isNotBlank()) return session.uid.trim()
        return ModuleUploadPrefs.lastUid().trim()
    }

    /** 表单里那一串「已经核实过」的 UID；没核实（含正在核实）就是空串。 */
    private fun verifiedFormUid(): String {
        val form = _formState.value
        return if (form.uidState is CoolapkUidState.Verified) form.uid.trim() else ""
    }

    private fun markChecking(ids: List<String>, checking: Boolean) {
        val idSet = ids.toSet()
        _listState.update { state ->
            state.copy(
                rows = state.rows.map { if (it.id in idSet) it.copy(checking = checking) else it }
            )
        }
    }

    /**
     * 换了 UID：把已经问到的归属结论全部作废。
     *
     * 为什么只作废 [UploadRow.eligibility] / [UploadZip.eligibility] 而不动 `status`：
     * 收录状态与「谁问的」无关，换个号再问一遍答案一模一样；归属才是那个会跟着人
     * 变的答案。空串**不**算换人——那是「还不知道是谁」，拿它去覆盖已经问到的答案
     * 只会把列表上「是你的」标签抹成「不知道」（表单还没核实完时就会这样）。
     */
    private fun syncEligibilityUid(uid: String) {
        val who = uid.trim()
        if (who.isEmpty() || who == eligibilityUid) return
        eligibilityUid = who
        val unknown = UpdateEligibility.UNKNOWN
        _listState.update { state ->
            state.copy(
                rows = state.rows.map {
                    if (it.eligibility == unknown && it.eligibilityReason.isEmpty()) it
                    else it.copy(eligibility = unknown, eligibilityReason = "")
                }
            )
        }
        _formState.update { state ->
            state.copy(
                items = state.items.map {
                    if (it.eligibility == unknown && it.eligibilityReason.isEmpty()) it
                    else it.copy(eligibility = unknown, eligibilityReason = "")
                }
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
                            // 走对话框而不是 Toast：这句里有两个模块 id 要对照，
                            // Toast 在窄屏上会被截断，也留不住给用户回看。
                            postNotice(
                                title = res.getString(R.string.module_upload_pick_wrong_title),
                                body = res.getString(
                                    R.string.module_upload_id_mismatch,
                                    expectedId,
                                    result.zip.id,
                                ),
                            )
                        } else {
                            addToForm(listOf(result.zip))
                        }
                    }

                    ModuleUploadStaging.Result.UnreadableModule ->
                        postMessage(res.getString(R.string.module_upload_unreadable_prop))

                    ModuleUploadStaging.Result.NoInstaller -> postNoInstallerNotice()

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
                            postNotice(
                                title = res.getString(R.string.module_upload_pick_wrong_title),
                                body = res.getString(
                                    R.string.module_upload_id_mismatch,
                                    row.id,
                                    result.zip.id,
                                ),
                            )
                        } else if (result.zip.versionCode != row.versionCode) {
                            // 留存的那份不是当前装着的这一版。真实路径有三条：用户在终端用
                            // `ksud module install` 升过级（不走 flashModule，归档不触发）、
                            // 备份恢复（archiveSource = false，故意不覆盖）、留存开关关着
                            // 的那段时间升过级。放过去就是把旧版当新版投，而入口文案明确
                            // 说「安装这个模块时已自动留存」，暗示它就是当前这版。
                            File(result.zip.path).delete()
                            postNotice(
                                title = res.getString(R.string.module_upload_version_mismatch_title),
                                body = res.getString(
                                    R.string.module_upload_archived_version_mismatch,
                                    result.zip.version,
                                    result.zip.versionCode,
                                    row.version,
                                    row.versionCode,
                                ),
                            )
                        } else {
                            addToForm(listOf(result.zip))
                        }
                    }

                    ModuleUploadStaging.Result.UnreadableModule ->
                        postMessage(res.getString(R.string.module_upload_unreadable_prop))

                    ModuleUploadStaging.Result.NoInstaller -> postNoInstallerNotice()

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
     * 「这个包没有安装脚本」的统一出口。
     *
     * 走确认框而不是 Toast：这条要说的不是「失败了」，而是「你得换一个包，以及为什么」——
     * 两段话在 Toast 里既显示不全也留不住。三条挑包路径都会撞到它，所以文案只写一份，
     * 由 [ModuleUploadStaging.Result.NoInstaller] 统一触发。
     */
    private fun postNoInstallerNotice() {
        postNotice(
            title = res.getString(R.string.module_upload_no_installer_title),
            body = res.getString(R.string.module_upload_no_installer_body),
        )
    }

    /** 打开「上传记录」：它自己也要一份最新的 /mine，不能只靠进列表时那一次。 */
    fun openHistory() {
        _step.value = UploadStep.HISTORY
        refreshMine()
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
     *
     * 两条约束：
     * - 正在为某一行挑包时不许清：挑包流程会把留存包复制到 staging，清空与它抢同一个
     *   目录，可能删掉刚被打开的那份（用户点了「用留存包」，结果拿到「包已不在」）。
     * - [archiveEpoch] 先加：清空前就已经在飞的那次 [loadModules] 拿的是旧快照，
     *   落地时必须作废，否则「已清空」的界面会被填回旧计数。
     */
    fun clearArchived() {
        if (_listState.value.pickingFor != null) return
        // 清空前先记下「当前还装着哪些模块」：清空后 survivors 里若还有它们之外的包，
        // 说明有文件没删掉，那些孤儿计数得如实反映出来。
        val installed = _listState.value.rows.mapTo(mutableSetOf()) { row -> ModuleZipArchive.safeId(row.id) }
        archiveEpoch++
        viewModelScope.launch {
            val cleared = withContext(Dispatchers.IO) {
                val clearedBytes = archive.clear()
                // 删不掉的文件（被占用等）会留在目录里：报数要按清空后的实际统计，
                // 而不是清空前 stat 出来的那个数；archived 标记也只留还给得出包的那些。
                Triple(clearedBytes, archive.ids(), archive.usage())
            }
            val (freed, survivors, remaining) = cleared
            _listState.update { state ->
                state.copy(
                    rows = state.rows.map { row -> row.copy(archived = ModuleZipArchive.safeId(row.id) in survivors) },
                    archivedCount = remaining.entries,
                    archivedBytes = remaining.totalBytes,
                    // 不能硬置 0：删不掉的文件（被占用等）会留在目录里，那些孤儿包
                    // 仍然存在，计数得跟着 survivors 走，否则入口消失、包还占着空间。
                    orphanCount = survivors.count { id -> id !in installed },
                    message = res.getString(
                        R.string.module_upload_clear_archived_zip_done,
                        formatFileSize(freed),
                    ),
                )
            }
        }
    }

    /**
     * 只回收「已卸载模块」遗留的那几份留存包，动也不动当前还装着的那些。
     *
     * 与 [clearArchived] 同一组约束（挑包途中不动、[archiveEpoch] 先加），因为两者删的是
     * 同一个目录；区别只在保留集合——这里把当前安装的 id 全留下，用户点完还能继续用。
     */
    fun clearOrphanArchived() {
        if (_listState.value.pickingFor != null) return
        // 「哪些是孤儿」只能从已安装列表算出来。列表为空不是「一个都没装」，而是
        // 还没扫到/扫失败——这时动 clearExcept(空集合) 等于把全部留存包删掉，
        // 而入口上写的是「只清已卸载模块遗留的那几份」。
        if (_listState.value.rows.isEmpty()) {
            postMessage(res.getString(R.string.module_upload_scan_failed))
            return
        }
        val installed = _listState.value.rows.mapTo(mutableSetOf()) { row -> ModuleZipArchive.safeId(row.id) }
        archiveEpoch++
        viewModelScope.launch {
            val (freed, survivors, remaining) = withContext(Dispatchers.IO) {
                val freedBytes = archive.clearExcept(installed)
                // 与 clearArchived 同理：报数按删完的实际统计，而不是删之前算的那个。
                Triple(freedBytes, archive.ids(), archive.usage())
            }
            _listState.update { state ->
                state.copy(
                    rows = state.rows.map { row -> row.copy(archived = ModuleZipArchive.safeId(row.id) in survivors) },
                    archivedCount = remaining.entries,
                    archivedBytes = remaining.totalBytes,
                    orphanCount = survivors.count { id -> id !in installed },
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
            var noInstaller = 0
            var tooBig: ModuleUploadStaging.Result.TooBig? = null
            val staged = mutableListOf<UploadZip>()
            try {
                uniqueUris.forEach { uri ->
                    when (val result = ModuleUploadStaging.stage(context, uri, maxBytes)) {
                        is ModuleUploadStaging.Result.Ok -> staged += result.zip
                        ModuleUploadStaging.Result.UnreadableModule -> unreadable++
                        ModuleUploadStaging.Result.NoInstaller -> noInstaller++
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
                // 缺口号的包要单独说：它的下一步不是「换个文件重试」，而是「去拿作者原包」，
                // 一句话塞不回 Toast 里，所以优先弹说明框。
                noInstaller > 0 -> postNoInstallerNotice()
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
            ModuleUploadRules.formatBytes(result.sizeBytes),
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
                            uidState = CoolapkUidState.Verified(session.nickname, session.avatar),
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
            // 表单这条路只认「刚刚核实过」的号：输入框里那串数字在核实通过之前
            // 只是用户的自称，拿它去问归属，服务端会按一个还没被证明的身份回答，
            // 而归属是会**拦提交**的答案（NOT_MINE 一票否决）——宁可这里回 UNKNOWN。
            val uid = verifiedFormUid()
            syncEligibilityUid(uid)
            val result = try {
                // 分批大小跟服务端自报的 maxModules 走：写死 8 的话，服务端一调到更小
                // 就是整批 400，这一整批全落成「查不了」。
                ModuleContribution.checkBatch(
                    query,
                    uid,
                    maxPerChunk = ModuleUploadRules.checkChunkSize(_listState.value.limits),
                )
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
                            // 服务端漏了这一格（整批失败 / 没回这个 id）：保留已知结论。
                            // 以前这里无条件写 UNKNOWN，于是一次超时就把刚查到的
                            // 「已收录」抹掉，canSubmit 里那道 RECORDED 闸跟着失效——
                            // 已收录的包重新变得可以提交。
                            val answer = result[item.id]
                            item.copy(
                                status = answer?.status ?: item.status ?: CheckStatus.UNKNOWN,
                                eligibility = answer?.eligibility ?: item.eligibility,
                                eligibilityReason = answer?.reason ?: item.eligibilityReason,
                                checking = false,
                            )
                        } else {
                            item
                        }
                    }
                )
            }
        }
    }

    /**
     * 归属只能带「核实过」的号去问，而核实是异步的：用户从列表挑包进表单时，
     * 那一趟 [checkManual] 可能还落在「正在核实」上，只能不带 uid 发出去
     * （服务端回 null → UNKNOWN）。核实一通过就得补问一次，否则「一键更新」
     * 这条路上归属闸永远是空的 —— 而它正是为这条路加的。
     *
     * 只补问还是 UNKNOWN 的那几枚：已经有结论的不重复问。
     */
    private fun recheckEligibility() {
        if (verifiedFormUid().isEmpty()) return
        val pending = _formState.value.items.filter {
            // 正在查的那几枚不用补：它们那一趟回来就会自己落答案，这里再发一次是同一批重复请求。
            !it.checking && it.eligibility == UpdateEligibility.UNKNOWN
        }
        if (pending.isEmpty()) return
        checkManual(pending)
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
        // 出处是一个链接，长到几百字符已经不合理；不设上限的话粘贴一整篇文章
        // 会原样进状态并跟着提交发给服务端。
        val trimmed = value.take(SOURCE_MAX_CHARS)
        _formState.update { state ->
            state.copy(
                items = state.items.map { if (it.path == path) it.copy(source = trimmed) else it },
                outcome = state.outcome.keepUnlessSuccess(),
            )
        }
    }

    /** 「应用到全部」：把这一张卡上的出处写给所有条目。 */
    fun applySourceToAll(value: String) {
        val trimmed = value.take(SOURCE_MAX_CHARS)
        _formState.update { state ->
            state.copy(
                items = state.items.map { it.copy(source = trimmed) },
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
        val previous = _formState.value.uid.trim()
        // 换了号就把「我已写进签名」的确认作废：口令只绑 (id, sha, nonce)，与 UID 无关，
        // 不清的话 A 号抄好的口令会一路亮到提交，而服务端读的是 B 号的签名 ——
        // 结果是整包传完才收到 403。令牌那条路已经用 sessionMatchesUid 绑定了身份，
        // 签名这条路必须在这里补上同一个绑定。
        val uidChanged = cleaned != previous
        // 换了号就是换了输入指纹：失败冷却按 UID 记，不清的话新号会陪着旧号的冷却一起等。
        // 红字与「等级不足」的拦截也一并清掉 —— 它们说的是**上一个号**的事，留着会让新号
        // 顶着一句跟它无关的失败说明，而且「我已升级，重新验证」按钮会跟着闪一下。
        if (uidChanged) {
            _formState.update {
                if (
                    it.verifyCooldownUntil == 0L && it.verifyFailures == 0 &&
                    it.verifyError == null && it.levelBlockedUid.isEmpty()
                ) {
                    it
                } else {
                    it.copy(
                        verifyCooldownUntil = 0L,
                        verifyFailures = 0,
                        verifyError = null,
                        levelBlockedUid = "",
                    )
                }
            }
        }
        lookupJob?.cancel()
        if (!ModuleContribution.isValidUid(cleaned)) {
            _formState.update {
                it.copy(
                    uid = cleaned,
                    uidState = CoolapkUidState.Empty,
                    confirmedSetKey = if (uidChanged) null else it.confirmedSetKey,
                )
            }
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
                    uidState = CoolapkUidState.Verified(session.nickname, session.avatar),
                    confirmedSetKey = if (uidChanged) null else it.confirmedSetKey,
                )
            }
            // 号可能刚换过：手里那几枚包的归属是照上一个号问的，补问一次。
            recheckEligibility()
            return
        }
        _formState.update {
            it.copy(
                uid = cleaned,
                uidState = CoolapkUidState.Checking,
                confirmedSetKey = if (uidChanged) null else it.confirmedSetKey,
            )
        }
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
        if (result is CoolapkUidLookup.Verified) {
            ModuleUploadPrefs.rememberUid(uid)
            // 刚核实通过：之前那一趟 checkBatch 因为没有可信 uid 只能回 UNKNOWN，补问。
            recheckEligibility()
        }
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
            // 这里也要推一格代次、也要掐掉在飞的那次核实：否则「清盘（或换了个 ViewModel
            // 实例）→ 旧 checkSession 晚到 → writeSession 把令牌写回磁盘」这条路还开着。
            // 上一版把代次只加在有令牌那一支，等于默认「没令牌时不会有人在飞」。
            sessionEpoch++
            sessionJob?.cancel()
            _formState.update { it.copy(session = null, sessionChecking = false) }
            // 手上没有令牌：身份页就是这一趟的起点。核验通过写下令牌之前，列表与表单
            // 都不该露出来（否则用户会在一个「提交必被 403 拒」的界面上白填一遍）。
            _step.value = UploadStep.IDENTITY
            return
        }
        _formState.update { it.copy(session = stored, sessionChecking = true) }
        // 磁盘上有令牌就先把人放进列表：核实要出网，慢网下（超时八秒）把人钉在身份页
        // 盯着「为什么要验证」没有道理 —— 他本来就已经验过。核实回 Invalid 会把他退回
        // 身份页，那才是他该看到的地方；回 Unavailable 则留在列表（本地那份照常到期）。
        if (_step.value == UploadStep.IDENTITY) _step.value = UploadStep.LIST
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
                    // 令牌已经确认可用：身份页不该再挡在前面（进页面时若落在身份页，回列表）。
                    if (_step.value == UploadStep.IDENTITY) _step.value = UploadStep.LIST
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
                    // 令牌失效 = 手上没有核验过的身份：退回身份页重新换一张。
                    _step.value = UploadStep.IDENTITY
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
                // 只有表单页会把 sessionActive 画出来；停在列表/记录页时每 30 秒唤醒一次
                // 进程改状态纯属浪费电。
                if (_step.value != UploadStep.FORM) continue
                val now = System.currentTimeMillis()
                val snapshot = _formState.value
                val session = snapshot.session
                // 令牌在表单页上过期：这一版表单里已经不再摆「改签名」那一段（身份验过才有
                // 表单），留着只会把人钉在「还差一项」上、找不到能勾的地方 —— 退回身份页重来。
                // 提交中不动：半路把人踢走比让他按一下重试更糟。
                if (!snapshot.submitting && session != null && !session.validAt(now)) {
                    sessionJob?.cancel()
                    sessionJob = null
                    sessionEpoch++
                    ModuleUploadPrefs.clearSession()
                    _formState.update { it.copy(session = null, nowTick = now) }
                    _step.value = UploadStep.IDENTITY
                    postMessage(res.getString(R.string.module_upload_identity_expired))
                    continue
                }
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
        // 进度是「这个账号的投稿」的投影：令牌一没，它就不再有依据（下一张令牌可能是
        // 另一个 UID）。不清的话，换账号之后记录页会先闪一遍上一个人的进度。
        progressJob?.cancel()
        progressJob = null
        closeProgress()
        _listState.update {
            it.copy(progress = emptyMap(), progressLoading = false, progressError = null)
        }
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
        // 「退出验证」的语义是「换一个上传身份」：回到身份页，而不是留在列表里一个
        // 既没有令牌、也没有核验结论的界面上（那种界面上提交按钮必然是灰的，却没人解释为什么）。
        _step.value = UploadStep.IDENTITY
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

    // ── 身份（用一次签名核验换九十天令牌）──────────────────────────────────

    /** 身份页上那条错误提示读过了就收起来，别一直压着。 */
    fun consumeVerifyError() {
        _formState.update { if (it.verifyError == null) it else it.copy(verifyError = null) }
    }

    /**
     * 「换一串」：换掉本机的身份验证串。
     *
     * 用户点它等于承认「签名里那串我不打算再用了」。换完必须重新把新承诺抄进签名 ——
     * 所以顺手把核验错误清掉、把承诺勾选也清掉（勾的是旧串那一次）。
     *
     * **不吊销手里的令牌**：令牌是服务端按旧串发的，仍然有效到到期为止；换串只影响
     * 「下次要换令牌时用哪一串」，不是「退出验证」。
     */
    fun renewIdentityCommit() {
        val state = _formState.value
        if (state.verifying) return
        _formState.update {
            it.copy(
                identitySecret = ModuleUploadPrefs.renewIdentitySecret(),
                verifyError = null,
                // 换了一串就是换了一组输入指纹，失败账本跟着归零：否则「刚失败过」的
                // 冷却是按旧串记的，用户换了串还要陪着等。
                verifyFailures = 0,
                verifyCooldownUntil = 0L,
                // 旧的「我抄好了」是对旧串说的，新串还没抄：这个勾必须重新打。
                consent = false,
            )
        }
    }

    /**
     * 「我已升级，重新验证」：解除按 UID 记的「等级不足」禁用。
     *
     * 解除后**不预支任何判断** —— 只是把按钮重新点亮，点了还是走一次真实核验。
     * 之所以要有这个入口：等级是用户能自己改的事（发帖、评论涨等级），
     * 没有它的话这个人就永远停在这一屏，除了卸载重装没有别的路。
     */
    fun clearLevelBlock() {
        _formState.update {
            if (it.levelBlockedUid.isEmpty()) {
                it
            } else {
                it.copy(
                    levelBlockedUid = "",
                    verifyError = null,
                    verifyFailures = 0,
                    verifyCooldownUntil = 0L,
                )
            }
        }
    }

    /**
     * 失败后的冷却时长：30 秒起，连续失败翻倍，5 分钟封顶。
     *
     * 起手 30 秒够「忘了贴签名」的人回去贴一次；翻倍是挡「一直点」的那种，
     * 而不是为难偶尔手滑的人。成功即清零（见 [verifyIdentity]）。
     */
    private fun cooldownMillis(failures: Int): Long = when {
        failures <= 1 -> 30_000L
        failures == 2 -> 60_000L
        failures == 3 -> 120_000L
        else -> 300_000L
    }

    /**
     * 「验证身份」：拿签名里那串承诺对应的验证串换一张九十天的令牌。
     *
     * 成败都落在 [ModuleUploadFormState.verifyError] 上，**不弹一次性 Toast 就算完**：像
     * 「签名里没找到这串承诺」这种话必须留在原地，用户要对着它去改签名。
     *
     * 成功之后这一屏就再也不会出现（除非主动退出验证）：令牌写进磁盘，进页面直接是列表。
     */
    fun verifyIdentity() {
        val state = _formState.value
        if (state.verifying || !state.canVerify) return
        val uid = state.uid.trim()
        val secret = state.identitySecret
        // 冷却期内、或这个号已经被判过等级不足，就别再打这一枪：结果必然一样，只是白烧额度。
        // 界面上的按钮已经禁用了，这里再挡一次是因为「禁用」只是观感，真正的判据在这。
        if (state.verifyCooldownUntil > System.currentTimeMillis()) return
        if (state.levelBlockedUid == uid) return
        _formState.update { it.copy(verifying = true, verifyError = null) }
        identityJob?.cancel()
        identityJob = viewModelScope.launch {
            val outcome = try {
                ModuleContribution.issueIdentitySession(
                    coolapkUid = uid,
                    secret = secret,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                IssueOutcome(message = e.message?.takeIf { it.isNotBlank() } ?: "")
            }
            _formState.update { it.copy(verifying = false) }
            if (!outcome.ok) {
                _formState.update { fail ->
                    when {
                        // 等级不足是**用户事实**：记住这个号，按钮换成「我已升级，重新验证」，
                        // 不这样做他就会一遍遍点同一颗按钮、我们一遍遍回同一个 403。
                        // 不写红字：底下那条提示本来就会用红字说同一件事，还带着解除入口，
                        // 两处都说等于同一句话在屏幕上喊两遍。
                        outcome.code == LEVEL_TOO_LOW_CODE ->
                            fail.copy(
                                verifying = false,
                                verifyError = null,
                                levelBlockedUid = uid,
                            )
                        // 形状不对 / 没生成好这类根本没发请求，冷却它没有意义。
                        outcome.code == IDENTITY_REQUIRED_CODE ->
                            fail.copy(verifying = false, verifyError = outcome.message)
                        else -> {
                            val failures = fail.verifyFailures + 1
                            fail.copy(
                                verifying = false,
                                verifyError = outcome.message,
                                verifyFailures = failures,
                                verifyCooldownUntil = System.currentTimeMillis() + cooldownMillis(failures),
                            )
                        }
                    }
                }
                return@launch
            }
            val grant = outcome.session ?: return@launch
            val identity = outcome.identity
            // 昵称必须在 adoptGrant **之前**落到状态里：adoptGrant 拿 state.nickname 拼
            // UploadSession，拼出一份昵称为空的令牌，下次 /session/check 会把它当脏应答
            // （Unavailable）—— 这次核验的效果在下次进页面时就被打回原形。
            if (identity != null) {
                _formState.update {
                    it.copy(uidState = CoolapkUidState.Verified(identity.nickname, identity.avatar))
                }
            }
            adoptGrant(grant, uid)
            ModuleUploadPrefs.rememberUid(uid)
            _formState.update {
                it.copy(
                    // 统一承诺在身份页勾过了：它是「这个人愿意为自己的上传负责」的一次性
                    // 声明，不该在每次上传时再问一遍。退出验证会把它连同令牌一起清掉。
                    consent = true,
                    verifyError = null,
                    // 验证成功就把失败账本清干净：下一次（九十天后换令牌）从零开始。
                    verifyFailures = 0,
                    verifyCooldownUntil = 0L,
                    levelBlockedUid = "",
                    // 令牌到手，那串口令与勾选都没有依据了：下次要改签名时从干净状态开始。
                    confirmedSetKey = null,
                )
            }
            _step.value = UploadStep.LIST
            postMessage(res.getString(R.string.module_upload_identity_verified))
        }
    }

    private var identityJob: Job? = null

    // ── 我投过的模块（GET /mine）────────────────────────────────────────────

    /**
     * 拉「我投过的模块」。
     *
     * 没有有效令牌时**不发请求**，直接落到 [MineState.needVerify]：服务端对没带令牌的
     * 请求只会回 `ok:false`（也就是 NeedVerify），发出去只是多等一次超时再得到同一个结论。
     *
     * 四个落点各自对应的界面动作不同，所以状态里不是一句「加载失败」而是四个标志
     * （见 [MineState] 的注释）——把「读不到索引」显示成「你没有模块」，用户会重投一遍。
     */
    fun refreshMine() {
        if (mineJob?.isActive == true) return
        val token = ModuleUploadPrefs.readSession()?.token?.takeIf { it.isNotBlank() }
        if (token == null) {
            mineJob?.cancel()
            _mineState.value = MineState(needVerify = true)
            return
        }
        _mineState.update {
            it.copy(loading = true, needVerify = false, failed = false, message = null)
        }
        mineJob = viewModelScope.launch {
            val result = try {
                ModuleContribution.fetchMine(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                MineResult.Failed
            }
            if (!isActive) return@launch
            _mineState.update { state ->
                when (result) {
                    // degraded 时服务端回的就是空列表，但语义是「索引读不到」而不是
                    // 「一枚都没投过」：modules 照实落（空），degraded 单独带出去，
                    // 绝不能在列表为空时把它抹成 false。
                    is MineResult.Ok -> state.copy(
                        loading = false,
                        modules = result.modules,
                        degraded = result.degraded,
                        needVerify = false,
                        failed = false,
                    )

                    MineResult.NeedVerify -> state.copy(
                        loading = false,
                        degraded = false,
                        needVerify = true,
                        failed = false,
                    )

                    // 失败不清空已有名单：网络抖一次不该让用户眼前一空；
                    // 但手里明明有一份旧名单时要说一句「这份没刷新」，否则它静默过期。
                    MineResult.Failed -> state.copy(
                        loading = false,
                        needVerify = false,
                        failed = true,
                        message = state.modules.takeIf { it.isNotEmpty() }
                            ?.let { res.getString(R.string.module_upload_mine_failed) },
                    )
                }
            }
        }
    }

    /** 已经弹过的那句补充说明别留到下一次。 */
    fun consumeMineMessage() {
        _mineState.update { if (it.message == null) it else it.copy(message = null) }
    }

    // ── 收录进度（GET /progress）────────────────────────────────────────────

    /**
     * 问一次「我投过的这些现在到哪一步了」。
     *
     * 没有令牌就不发请求：服务端只会回 `session_missing`，白等一次超时之后得到的
     * 还是同一句「暂时查不到」。进度是**补充信息**，令牌失效时不把用户踢回身份页 ——
     * 正在看的是自己的投稿记录，回身份页会把这一屏的上下文也丢掉。
     *
     * 失败不清空已有那一份：网络抖一次就让步骤条全空，比显示几秒前的旧读数更难理解。
     * 但 [ModuleUploadListState.progressError] 会立起来，界面据此说明「这份没刷新」。
     */
    fun refreshProgress() {
        if (progressJob?.isActive == true) return
        val token = ModuleUploadPrefs.readSession()?.token?.takeIf { it.isNotBlank() }
        if (token == null) {
            _listState.update {
                it.copy(
                    progressLoading = false,
                    progressError = res.getString(R.string.module_upload_progress_unavailable),
                )
            }
            return
        }
        _listState.update { it.copy(progressLoading = true, progressError = null) }
        progressJob = viewModelScope.launch {
            val outcome = try {
                fetchProgress(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // fetchProgress 自己承诺不抛；留这一层只是不想让将来某次改动把
                // 一个「查不到」变成一次崩溃。
                ProgressOutcome.Failed("unexpected")
            }
            if (!isActive) return@launch
            _listState.update { state ->
                when (outcome) {
                    is ProgressOutcome.Ok -> state.copy(
                        progressLoading = false,
                        progressError = null,
                        // 按 id@版本号 建索引：同一个 id 投过多版时每一版各有自己的进度。
                        progress = outcome.items.associateBy {
                            progressKey(it.id, it.versionCode.toLong())
                        },
                    )

                    is ProgressOutcome.Failed -> state.copy(
                        progressLoading = false,
                        progressError = res.getString(R.string.module_upload_progress_unavailable),
                    )
                }
            }
        }
    }

    /**
     * 打开这几个模块的进度弹层，并顺手拉一次。
     *
     * [ids] 是**模块 id**，弹层里按这个 id 列出它投过的每一版（版本号来自 /mine，
     * 进度本身来自 /progress）。用 id 而不是「id@版本号」作参数，是因为入口长在模块卡片上，
     * 用户点的是「这个模块」而不是「这一版」。
     */
    fun openProgress(ids: List<String>) {
        val unique = ids.distinct()
        if (unique.isEmpty()) return
        _listState.update { it.copy(progressDialogIds = unique) }
        refreshProgress()
        startProgressPolling()
    }

    /** 关掉进度弹层：同时停掉轮询，别让它在后台空转。 */
    fun closeProgress() {
        progressPollJob?.cancel()
        progressPollJob = null
        _listState.update { if (it.progressDialogIds == null) it else it.copy(progressDialogIds = null) }
    }

    /**
     * 弹层开着时每 30 秒再问一次。
     *
     * 只在 [ModuleUploadListState.progressDialogIds] 非 null 时转：这是一条**人正在看**的
     * 信息，关掉之后继续请求既烧流量也烧服务端配额，而且没有第二条路能让用户看到结果。
     * `refreshProgress` 自带「上一次还没回来就不重发」，所以慢网络下不会堆请求。
     */
    private fun startProgressPolling() {
        if (progressPollJob?.isActive == true) return
        progressPollJob = viewModelScope.launch {
            while (isActive) {
                delay(PROGRESS_POLL_INTERVAL_MS)
                if (_listState.value.progressDialogIds == null) break
                refreshProgress()
            }
        }
    }

    /**
     * 提交成功之后顺手取这几条的进度。
     *
     * 不等待、不影响成功本身：拿不到就是空列表，结果卡照样显示「已提交」。
     * 只留**这一次提交的那几枚**（id + versionCode 都要对上），不能把整个账号的进度
     * 铺到结果卡上——用户会以为那几枚也刚刚提交。
     */
    private fun loadSubmittedProgress(submitted: List<SubmittedModule>) {
        if (submitted.isEmpty()) return
        val token = ModuleUploadPrefs.readSession()?.token?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val items = when (val outcome = fetchProgress(token)) {
                is ProgressOutcome.Ok -> outcome.items.filter { item ->
                    submitted.any { it.id == item.id && it.versionCode == item.versionCode.toLong() }
                }

                is ProgressOutcome.Failed -> emptyList()
            }
            if (items.isEmpty()) return@launch
            _formState.update { state ->
                val success = state.outcome as? UploadOutcome.Success ?: return@update state
                state.copy(outcome = success.copy(progress = items))
            }
        }
    }

    /** 进度轮询协程（见 [startProgressPolling]）。 */
    private var progressPollJob: Job? = null

    /** 一次性进度请求（见 [refreshProgress]）。 */
    private var progressJob: Job? = null

    /**
     * 把服务端这一趟签发的令牌收进状态与磁盘。
     *
     * 三态（见 [BatchSubmitResult.session]）：null = 没签，保持原样；合法 = 换上新的
     * （有旧的就在旧的基础上续，身份沿用）。
     *
     * [submittedUid] 是**发请求时**那个 UID，不读实时状态：令牌是服务端按那一次的 uid
     * 签的，而提交期间输入框虽然被禁用，将来一旦放开就会出现「A 的令牌被记成 B 的身份」。
     */
    private fun adoptGrant(grant: SessionGrant?, submittedUid: String) {
        if (grant == null) return
        val current = _formState.value
        val uid = submittedUid.trim()
        if (uid.isEmpty()) return
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
        // 只有表单那一屏要挡：上传在飞时退出去，屏幕上的状态就没人接了。
        // 记录页没有提交动作，任何时候都该退得回来。
        if (_step.value == UploadStep.FORM && _formState.value.submitting) return
        // 离开记录页时把进度弹层与轮询一并收掉：弹层是记录页里的东西，
        // 留着它会让 30 秒一轮的请求在列表页继续跑，而屏幕上没有任何进度可看。
        closeProgress()
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
        // 两条路都不通时才拦：令牌那条（有效且绑的就是这个 UID）或签名那条（勾选后集合没变）。
        // 只看令牌的话，令牌过期/换了号而用户已经老老实实写好签名时，按钮亮着、
        // 点下去却只弹一句「请重新改一次签名」——正是他刚做完的事。
        if (!snapshot.sessionMatchesUid && !snapshot.signatureValid) {
            // 手里还攥着一张过期令牌：canSubmit 恒为 false，静默 return 会让用户对着
            // 一个灰掉的按钮猜原因。这里明说一句。
            if (snapshot.session != null) {
                _formState.update {
                    it.copy(notice = res.getString(R.string.module_upload_session_expired_submit))
                }
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
            var settled = false
            try {
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
                    settled = true
                    return@launch
                }

                val batch = snapshot.items.map { item ->
                    BatchZip(
                        file = File(item.path),
                        meta = ModuleUploadStaging.metaOf(item),
                        sha256 = item.sha256,
                        source = item.source.trim(),
                        fileName = item.fileName,
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

                applySubmitResult(result, snapshot.nonce, generation, snapshot.uid)
                settled = true
            } finally {
                // 协程被取消（离开路由）时不会有结果回来：submitting 停在 true 会让
                // backToList 永远被挡、按钮永远灰着。取消也要把界面还原成一个可用状态。
                if (!settled) {
                    _formState.update { it.copy(submitting = false) }
                }
            }
        }
    }

    private fun applySubmitResult(
        result: BatchSubmitResult,
        submittedNonce: String,
        generation: Int,
        /** 发请求时用的 UID：令牌要记在这个身份下（见 [adoptGrant]）。 */
        submittedUid: String,
    ) {
        if (generation != submitGeneration) return
        if (result.ok) {
            val count = result.modules.size
            // 服务端这一次可能签了一张新令牌（只有真过了一遍签名核对才会签）。
            // 手里已经有一张就换上新的（续期），没有就按这一次核实到的身份建一张。
            adoptGrant(result.session, submittedUid)
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
            // 进度是补充信息：拿不到不影响「已提交」这个结论，所以放在成功落定之后
            // 另起一趟，失败了也不会改 outcome。
            loadSubmittedProgress(result.modules)
            return
        }

        // 只有服务端明说「这枚口令用过」才换 nonce（code 优先，与旧入口同一条规则）：
        // 其余失败换掉会让用户刚写进签名的那串立刻作废。
        //
        // 这里是**按码精确比对**，不是「凡是失败就换」，也不是「凡是 403 就换」：
        // Worker 的归属拒绝回的是 `not_owner`（`eligibility` 为 false 时那一条），
        // 它必须落进「不换」—— 对着一次归属拒绝去重掷 nonce 再发一遍，不但白烧一次
        // 整包上传，还会把用户刚写进签名的那串作废，而真正该做的是换一个自己的模块
        // 或换 UID。同理 `restricted`、等级不足之类的码也不该触发轮换。
        val proofUsed = result.code == PROOF_USED_CODE
        val message = when {
            result.code == ModuleContribution.CLIENT_TIMEOUT_CODE ->
                res.getString(R.string.module_upload_submit_timeout)

            // 服务端也有一道「包里没有安装脚本」的闸（网页端与旧客户端绕得过本地那道），
            // 它回的是机器码。把机器码换成本地那份同样详细的解释，别把一句英文丢给用户。
            result.code == ModuleContribution.INSTALL_SCRIPT_MISSING_CODE ->
                res.getString(R.string.module_upload_no_installer_body)

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
        // 进度轮询与一次性请求也挂在这里：它们只在页面活着时有意义，
        // 留着它们会在下一个页面里继续每 30 秒打一次 `GET /progress`。
        progressPollJob?.cancel()
        progressJob?.cancel()
        // 上传在飞时先不删：请求体正是这些文件，而取消与 socket 收尾是异步的，
        // 抢在它前面删会让上传线程读到半截文件。残留副本由下一次进页面的
        // ModuleUploadStaging.purgeStale 回收（陈旧阈值 6 小时）。
        if (!_formState.value.submitting) {
            _formState.value.items.forEach { item -> File(item.path).delete() }
        }
        super.onCleared()
    }

    private val res get() = ksuApp.resources

    private companion object {
        /** 服务端判「酷安等级不够」时回的码。它和「查不到这个账号」原本共用一个码，现在是两个。 */
        const val LEVEL_TOO_LOW_CODE = "level_too_low"

        /** 本地形状校验就没过（UID 位数不对、验证串没生成好），根本没发请求。 */
        const val IDENTITY_REQUIRED_CODE = "identity_required"

        /** 出处链接的最大长度：够放任何酷安链接，又不至于让人把整篇文章粘进来。 */
        const val SOURCE_MAX_CHARS = 512

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

        /**
         * 进度弹层开着时的轮询间隔：30 秒。
         *
         * 进度变化的最小粒度是「维护者开关一次 PR」，秒级刷新没有信息增量；
         * 30 秒一次是「用户盯着看时能感觉到在更新」与「不白烧流量」之间的折中。
         */
        const val PROGRESS_POLL_INTERVAL_MS = 30_000L

        /** Worker used-nonce 分支的机器可读码（与旧入口同一条约定）。 */
        const val PROOF_USED_CODE = "proof_used"

        /**
         * 服务端限制（单批条数、单包与整批字节上限）的进程内缓存寿命。
         *
         * 它是**配置**，不是状态：一次会话问一次就够。以前每建一个 ViewModel 都问一次，
         * 而进这一页的次数远多于这份配置变化的次数。留十分钟是因为服务端要是调小了上限，
         * 本地预检不能永远比它宽松——十分钟后自然会重新取一份。
         */
        const val LIMITS_TTL_MS = 10 * 60 * 1000L

        /**
         * 上一次取到的限制。放在 companion 里是为了跨 ViewModel 复用：这一页的
         * ViewModel 随路由进出反复重建，实例字段留不住。
         *
         * 只在主线程读写（[fetchLimits] 从 `viewModelScope` 里调用，挂起前后都在主线），
         * 不需要额外同步。
         */
        var limitsMemo: BatchLimits? = null
        var limitsMemoAt = 0L
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
        // 归属必须跟着透传：`ksud module list` 每刷新一次都会重建行对象，
        // 不透传就等于每次刷新把已经问到的归属答案扔掉，列表上的归属标签
        // 与「一键更新」的归属闸都会退回「不知道」。
        eligibility = old?.eligibility ?: UpdateEligibility.UNKNOWN,
        eligibilityReason = old?.eligibilityReason.orEmpty(),
        archived = ModuleZipArchive.safeId(id) in archived,
    )
}
