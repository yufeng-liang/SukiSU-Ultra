package com.sukisu.ultra.ui.screen.upload

import com.sukisu.ultra.R
import com.sukisu.ultra.ui.util.BatchLimits
import com.sukisu.ultra.ui.util.CheckStatus
import com.sukisu.ultra.ui.util.ContributionBatch
import com.sukisu.ultra.ui.util.ProofItem
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.Collator
import java.util.Locale

/**
 * 「模块上传」列表页与表单页的全部纯规则。
 *
 * 抽成纯函数是为了让 JVM 单测能直接盯住三件容易写歪的事：
 * - **筛选**：四个筛选项各自包含哪些状态（把「查不了」混进「未收录」会让用户对着
 *   一堆其实已经收录的模块重新上传）；
 * - **状态文案**：四种状态说的必须是四件不同的事，尤其中间两种；
 * - **口令失效**：集合（id + 整包 sha256）一变，用户写进酷安签名的那串就作废，
 *   界面必须把「我已写进签名」重新打回未勾选，而不是让一次注定 403 的提交发出去。
 *
 * 这些规则同时被 Material 与 Miuix 两套界面使用。任何一条只写在某一套 Composable 里，
 * 另一套主题上就会跑偏——这正是把规则放在这里的原因。
 */
object ModuleUploadRules {

    /** 列表筛选。顺序即界面上的顺序：全部 / 未收录 / 已收录 / 查不了。 */
    enum class Filter {
        ALL,
        NOT_RECORDED,
        RECORDED,
        UNKNOWN,
    }

    /** 一次收录查询最多覆盖多少个模块；超出的部分靠「继续查」按钮接着查。 */
    const val CHECK_CAP = 200

    /** 服务端没给 maxModules 时的分批大小（与契约里的兜底值一致）。 */
    const val FALLBACK_CHUNK_SIZE = 8

    /** 同时最多跑几批 `checkBatch`。 */
    const val CHECK_CONCURRENCY = 2

    /** 口令长度：12 位前缀 + `-` + 6 位 nonce。测试里钉住它，两套界面不必各自数一遍。 */
    const val PROOF_LENGTH = 19

    /** 列表里一行：模块 + 当前查到的状态（null = 还没查）。 */
    data class RowView(
        val id: String,
        val name: String,
        val status: CheckStatus?,
    )

    /** 一行状态对应的两条文案（标题 + 一句解释），由界面渲染。 */
    data class StatusCopy(val title: Int, val summary: Int)

    /**
     * 状态文案。
     *
     * `null` 是「还没查」（被 200 条上限截住或刚进页面），与 [CheckStatus.UNKNOWN]
     * 的「查了但没问出来」分开说：前者用户点一下「继续查」就好，后者只能等网络恢复。
     */
    fun statusCopy(status: CheckStatus?): StatusCopy = when (status) {
        CheckStatus.RECORDED -> StatusCopy(
            R.string.module_upload_status_recorded,
            R.string.module_upload_status_recorded_summary,
        )

        CheckStatus.VERSION_UPDATE -> StatusCopy(
            R.string.module_upload_status_version_update,
            R.string.module_upload_status_version_update_summary,
        )

        CheckStatus.NEW -> StatusCopy(
            R.string.module_upload_status_new,
            R.string.module_upload_status_new_summary,
        )

        CheckStatus.UNKNOWN -> StatusCopy(
            R.string.module_upload_status_unknown,
            R.string.module_upload_status_unknown_summary,
        )

        null -> StatusCopy(
            R.string.module_upload_status_not_checked,
            R.string.module_upload_status_not_checked_summary,
        )
    }

    /**
     * 这个状态落不落在筛选项里。
     *
     * 「未收录」只收 [CheckStatus.NEW] 与 [CheckStatus.VERSION_UPDATE]：
     * 这两种都是「索引里没有这一版」，可以上传。查不了的、还没查的一律进「查不了」，
     * 因为界面不能把「不知道」当成「没有」——那会诱导用户重复上传已经收录的模块。
     */
    fun matchesFilter(status: CheckStatus?, filter: Filter): Boolean = when (filter) {
        Filter.ALL -> true
        Filter.NOT_RECORDED -> status == CheckStatus.NEW || status == CheckStatus.VERSION_UPDATE
        Filter.RECORDED -> status == CheckStatus.RECORDED
        Filter.UNKNOWN -> status == null || status == CheckStatus.UNKNOWN
    }

    /**
     * 筛选 + 搜索 + 排序。
     *
     * 搜索只比 id 与名称：描述往往很长，把描述也算进去会让「搜 A 出来 B」。
     * 排序按名称（[Collator] 跟随系统语言），名称相同的再按 id，保证顺序稳定。
     */
    fun visibleRows(
        rows: List<RowView>,
        filter: Filter,
        query: String,
    ): List<RowView> {
        val text = query.trim()
        return rows.asSequence()
            .filter { matchesFilter(it.status, filter) }
            .filter { text.isEmpty() || it.matches(text) }
            .sortedWith(
                compareBy<RowView, String>(Collator.getInstance(Locale.getDefault())) { it.name }
                    .thenBy { it.id }
            )
            .toList()
    }

    private fun RowView.matches(text: String): Boolean =
        id.contains(text, ignoreCase = true) || name.contains(text, ignoreCase = true)

    /** 分批大小：服务端给了正数就用它，否则退回 8。 */
    fun checkChunkSize(limits: BatchLimits?): Int =
        limits?.maxModules?.takeIf { it > 0 } ?: FALLBACK_CHUNK_SIZE

    /** 单批上传的上限条数；界面据此提前拦下超量选择。 */
    fun maxModules(limits: BatchLimits?): Int =
        limits?.maxModules?.takeIf { it > 0 } ?: FALLBACK_CHUNK_SIZE

    /** 本地预检结论。界面把它翻成一句人话，提交前就能拦下。 */
    enum class LimitProblem {
        NONE,
        LIMITS_UNKNOWN,
        TOO_MANY,
        SINGLE_TOO_BIG,
        TOTAL_TOO_BIG,
    }

    /**
     * 本地限制预检（与服务端同一组阈值，来自 [BatchLimits]）。
     *
     * 返回 [LimitProblem.NONE] 表示放行。[limits] 为 null（没取到服务端限制）时
     * **不放行**（[LimitProblem.LIMITS_UNKNOWN]）：拿不到上限就等于不知道边界，
     * 不能拿一个猜出来的 8 / 8 MiB 把用户放过去，也不能让他白传一整批再被拒。
     */
    fun limitProblem(count: Int, sizes: List<Long>, limits: BatchLimits?): LimitProblem {
        val safe = limits?.takeIf { it.usable } ?: return LimitProblem.LIMITS_UNKNOWN
        if (count > safe.maxModules) return LimitProblem.TOO_MANY
        if (sizes.any { it > safe.maxBytes }) return LimitProblem.SINGLE_TOO_BIG
        if (sizes.sum() > safe.maxTotalBytes) return LimitProblem.TOTAL_TOO_BIG
        return LimitProblem.NONE
    }

    /** 出处校验结论。 */
    enum class SourceProblem {
        EMPTY,
        NOT_COOLAPK,
        OK,
    }

    /**
     * 出处必须是酷安链接。
     *
     * 只认 `coolapk.com` 与其子域（`www.` / `m.`）：`evil-coolapk.com` 这种后缀相同的
     * 冒名域名必须拒掉，否则「出处」一栏就成了一个可以指向任意站点的文本框。
     * 协议只认 http/https（`toHttpUrlOrNull` 本身也只解析这两种）。
     *
     * 没写协议时按 `https://` 补全再判，与网页端和 Worker 同一条规则：
     * 用户从酷安复制出来的链接常常是 `www.coolapk.com/feed/123`，只因为少个协议
     * 就在本地被拦下，而服务端明明会收。
     */
    fun sourceProblem(raw: String): SourceProblem {
        val text = raw.trim()
        if (text.isEmpty()) return SourceProblem.EMPTY
        val candidate = if (text.contains("://")) text else "https://$text"
        val url = candidate.toHttpUrlOrNull() ?: return SourceProblem.NOT_COOLAPK
        val host = url.host.lowercase(Locale.ROOT)
        val coolapk = host == "coolapk.com" || host.endsWith(".coolapk.com")
        return if (coolapk) SourceProblem.OK else SourceProblem.NOT_COOLAPK
    }

    /**
     * 口令要等每个包的 sha256 都算完才显示：半截哈希算出来的口令一定是废的。
     *
     * 这里连形状一起查（64 位小写十六进制、id 非空且不重复），因为
     * [ContributionBatch.canonicalProofSet] 对坏输入是 `require` 抛异常：
     * 这个属性在组合里求值，抛出去就是整屏崩掉。形状不满足时按「还没算完」处理。
     */
    fun proofReady(items: List<UploadZip>): Boolean =
        items.isNotEmpty() &&
            items.all { it.id.isNotBlank() && SHA256_RE.matches(it.sha256) } &&
            items.map { it.id }.toSet().size == items.size

    private val SHA256_RE = Regex("^[0-9a-f]{64}$")

    /**
     * 一次口令快照：口令文本 + 它绑定的集合指纹。
     *
     * 指纹用 [ContributionBatch.canonicalProofSet]（与服务端同一条规范化规则），
     * 「我已写进签名」存的是指纹而不是布尔值——集合一变，存下来的指纹自然对不上，
     * 勾选自动失效，不需要在每个改 id / 换包 / 换 nonce 的地方记得手动清一次。
     */
    data class ProofSnapshot(val text: String, val setKey: String)

    fun proofSnapshot(items: List<UploadZip>, nonce: String): ProofSnapshot {
        // 先按 id 排序再交给契约：界面里的顺序会随「移除一个再加回来」变化，
        // 而同一批模块必须永远算出同一串口令（否则勾选还在、签名里的那串却变了）。
        val proofs = items.sortedBy { it.id }.map { ProofItem(it.id, it.sha256) }
        return ProofSnapshot(
            text = ContributionBatch.proofTextV2(proofs, nonce),
            setKey = ContributionBatch.canonicalProofSet(proofs),
        )
    }

    /** 「我已写进签名」是否仍然有效。 */
    fun signatureStillValid(confirmedSetKey: String?, currentSetKey: String): Boolean =
        !confirmedSetKey.isNullOrEmpty() && confirmedSetKey == currentSetKey

    /** 把字节数说成人话（表单上的大小、提交中的总量）。纯函数，不依赖 Android 资源。 */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KiB", kb)
        return String.format(Locale.ROOT, "%.1f MiB", kb / 1024.0)
    }

    /**
     * 提交按钮的唯一判据。
     *
     * 六道门缺一不可：每个包都有 sha、UID 核实通过、每个包都有合法出处、勾了确认、
     * 「身份这一关」过了、当前不在提交中。
     *
     * 第五条是 `signatureValid || sessionActive` 二选一，不是两道都要：
     * - 手里有**有效的长期令牌**（[sessionActive]）：服务端认这张令牌，这一次不用改签名，
     *   所以 `signatureValid` 恒为假也不影响放行；
     * - 没有令牌：只能走「改签名 + 勾选确认」那条路，判据回到「签名与当前集合一致」。
     *
     * 为什么签名那条用「与当前集合一致」而不是「用户勾过复选框」：集合在勾选之后又变了的话，
     * 酷安签名里那串已经不匹配，放行只会换来一次 403 和一次白上传。
     *
     * 令牌这条路也**不是免检**：它只是把「每次改签名」换成「九十天一次」，令牌本身仍由
     * 服务端每次提交时核对（KV 里只存它的 sha256，且可以被吊销）。
     */
    fun canSubmit(
        items: List<UploadZip>,
        uidVerified: Boolean,
        consent: Boolean,
        signatureValid: Boolean,
        submitting: Boolean,
        limitProblem: LimitProblem,
        sessionActive: Boolean = false,
    ): Boolean = items.isNotEmpty() &&
        !submitting &&
        limitProblem == LimitProblem.NONE &&
        uidVerified &&
        consent &&
        (signatureValid || sessionActive) &&
        proofReady(items) &&
        // 手选包的收录状态查完再放行：正在查时别让用户抢在结果前面提交；
        // 已经收录的那一版不再重复投（要换内容走下架/更新那条路，不在本屏）。
        items.none { it.checking } &&
        items.none { it.status == CheckStatus.RECORDED } &&
        items.all { sourceProblem(it.source) == SourceProblem.OK }
}
