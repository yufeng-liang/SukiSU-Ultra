package com.sukisu.ultra.ui.util

import com.sukisu.ultra.data.backup.sha256Hex
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 批量投稿的纯数据与纯规则。
 *
 * 与 [ModuleContribution] 分开：那一份是网络层（会碰 OkHttp、协程、超时），
 * 这一份只做字符串/集合/上限判定，JVM 单测可以直接跑。三处实现里 App 这一份是
 * 第三份口令算法（Worker `proof.ts`、网页 `proof.js`、这里），所以
 * [proofPrefixV2] 必须由黄金值单测钉住，不能只靠注释。
 */
data class BatchLimits(
    val maxModules: Int,
    val maxBytes: Long,
    val maxTotalBytes: Long,
) {
    /**
     * 三个上限必须都是正数。
     *
     * 0 或负数不是「不限」，而是「这一项没读到」：放行等于把大小闸整个拆掉，
     * 与拿不到上限同一条处理——先不放行。
     */
    val usable: Boolean
        get() = maxModules > 0 && maxBytes > 0 && maxTotalBytes > 0
}

/** 口令集合里的一枚：模块 id 与整包 sha256（64 位小写十六进制）。 */
data class ProofItem(val id: String, val zipSha: String)

/** 一枚待上传的包：文件、包内 module.prop、整包哈希、这一枚的出处。 */
data class BatchZip(
    val file: File,
    val meta: ModulePropInfo,
    val sha256: String,
    val source: String,
    /**
     * 上传时给服务端看的文件名，形如 `{id}-{versionCode}.zip`。
     *
     * 不能直接用 [file] 的名字：staging 里那份是 `File.createTempFile("upload-", ".zip")`
     * 生成的随机名，服务端如果按 part 文件名给资产命名，索引里就会出现一串 `upload-1234.zip`。
     * 留空时退回 id-versionCode（单测与旧调用点不必都改）。
     */
    val fileName: String = "",
)

/** 成功应答里的逐枚回显。 */
data class SubmittedModule(
    val id: String,
    val versionCode: Long,
    val sha256: String,
    val assetName: String,
)

/**
 * 服务端在这一次提交里签发的那一张令牌（只有服务端知道的两个值）。
 *
 * 昵称、头像、UID 不在这里 —— 应答里没有它们，App 用的是自己刚刚核实过的那一份
 * （见 [UploadSession.renewed]）。把「服务端给的」与「本地已有的」分开，是为了让
 * 这一层不依赖任何界面状态。
 */
data class SessionGrant(
    val token: String,
    val expiresAt: Long,
)

/**
 * 长期会话令牌：**一次改签名，九十天免改**。
 *
 * 服务端只在「这一次真的过了一遍签名核对」之后才签发它（走令牌来的提交不重签，
 * 否则一张令牌可以无限自我续期）。令牌本身是一个 64 位小写十六进制串，
 * 服务端 KV 里只存它的 sha256，所以泄了 KV 也拿不到能用的令牌。
 *
 * [uid] 是签发时核实过的酷安 UID。[expiresAt] 是**服务端的**到期时刻（毫秒），
 * 本地只拿来显示「还有多久」并提前判定失效；真正作数的是服务端那一份 ——
 * 本地改这个数不会延长任何东西。
 */
data class UploadSession(
    val token: String,
    val uid: String,
    val nickname: String,
    val avatar: String,
    val expiresAt: Long,
) {
    /** 形状与服务端 `SESSION_TOKEN_RE`（`/^[0-9a-f]{64}$/`）同一条。 */
    val wellFormed: Boolean
        get() = SESSION_TOKEN_RE.matches(token) && uid.isNotEmpty() && expiresAt > 0L

    /**
     * 这一份令牌此刻还算不算数。
     *
     * 留 60 秒余量：界面在到期前一秒还显示「已验证」、提交时服务端却说失效，
     * 那种错位比提前一分钟让用户重新验证更糟。
     */
    fun validAt(now: Long): Boolean = wellFormed && expiresAt - VALIDITY_SKEW_MS > now

    /**
     * 服务端续签了一张：换上新的 token 与到期时刻，身份沿用本地这份。
     *
     * 续签只可能发生在「这次真过了一遍签名核对」之后，所以身份（uid/昵称/头像）
     * 用的就是这一趟刚核实到的那一份 —— 不取应答里的（应答里本来也没有）。
     */
    fun renewed(grant: SessionGrant): UploadSession = copy(
        token = grant.token,
        expiresAt = grant.expiresAt,
    )

    private companion object {
        /**
         * 令牌形状只留一份实现：[com.sukisu.ultra.ui.util.ModuleContribution.SESSION_TOKEN_RE]。
         *
         * 这里曾经抄了第二条 `Regex("^[0-9a-f]{64}$")`。两条一模一样的正则不会立刻出错，
         * 但它们的**用途**不同（这条判本地这份能不能用，那条判发不发给服务端），
         * 服务端改形状时只改一处就会造出「本地认为合法、网络层认为该丢弃」的静默错位：
         * 界面上写着「已验证」，请求里却没带令牌，用户拿到一次莫名的 403。
         */
        val SESSION_TOKEN_RE = ModuleContribution.SESSION_TOKEN_RE

        /** 到期前的余量：宁可早一分钟失效，也不要「界面说有效、服务端说没有」。 */
        const val VALIDITY_SKEW_MS = 60_000L
    }
}

/**
 * 批量提交结果。
 *
 * [message] 在成功时留空：成功文案由界面按 locale 取，不在这里写死中文；
 * 失败时由 [ContributionBatch.errorCopy] 翻成一句人话。
 */
data class BatchSubmitResult(
    val ok: Boolean,
    val prUrl: String?,
    val modules: List<SubmittedModule>,
    val message: String,
    val code: String? = null,
    /**
     * 服务端在这一次真的过了一遍签名核对之后签发的长期令牌。
     *
     * 三态，不要合并：
     * - `null`：没签（走令牌来的这一笔不重签 / 服务端没配 KV）——**保持手里那张不变**；
     * - 非 null 且形状合法：换上新的一张；
     * - 非 null 但形状不合法：服务端回了脏数据，当作没签（同上，别把好令牌顶掉）。
     */
    val session: SessionGrant? = null,
)

object ContributionBatch {

    private val SHA256_RE = Regex("^[0-9a-f]{64}$")
    private const val PREFIX_LEN = 12

    /**
     * 规范串：按 id 升序（同 id 再按 zipSha 升序）逐枚写 `id:zipSha`，用 `,` 连接。
     *
     * 与 Worker `canonicalProofSet` / 网页 `canonicalProofSet` 逐字同算法。
     * 三条拒绝（空集、非 64 位小写十六进制、同 id 重复）都是客户端可控的边界输入；
     * 放过去只会把形状问题推到「口令对不上」那一格。
     */
    fun canonicalProofSet(items: List<ProofItem>): String {
        require(items.isNotEmpty()) { "口令集合不能为空" }
        for (item in items) {
            require(item.id.isNotEmpty()) { "口令集合里有空模块 id" }
            require(SHA256_RE.matches(item.zipSha)) {
                "这一枚的整包 sha256 不是 64 位小写十六进制：${item.id}"
            }
        }
        require(items.map { it.id }.toSet().size == items.size) { "同一套里同一个模块 id 出现两次" }
        val sorted = items.sortedWith(compareBy<ProofItem> { it.id }.thenBy { it.zipSha })
        return sorted.joinToString(",") { "${it.id}:${it.zipSha}" }
    }

    /** v2 前缀 = `sha256("sukisu-proof-v2:" + 规范串)` 的前 12 位小写十六进制。 */
    fun proofPrefixV2(items: List<ProofItem>): String =
        "sukisu-proof-v2:${canonicalProofSet(items)}"
            .toByteArray(Charsets.UTF_8)
            .sha256Hex()
            .take(PREFIX_LEN)

    /** 整串口令 = 12 位前缀 + `-` + 6 位 nonce，恒 19 字符。 */
    fun proofTextV2(items: List<ProofItem>, nonce: String): String =
        "${proofPrefixV2(items)}-$nonce"

    /**
     * manifest 文本。`version` 恒为 `batch-v1`；共享字段（uploader / coolapkUid /
     * pledge / nonce / 令牌）留在 multipart 顶层，不进这里。
     *
     * 字段顺序按契约写，便于人读；服务端解析 JSON，不依赖键序。
     */
    fun buildManifestJson(modules: List<BatchZip>): String {
        val arr = JSONArray()
        for (module in modules) {
            arr.put(
                JSONObject()
                    .put("id", module.meta.id)
                    .put("sha256", module.sha256.trim().lowercase(Locale.ROOT))
                    .put("name", module.meta.name)
                    .put("version", module.meta.version)
                    .put("versionCode", module.meta.versionCode)
                    .put("author", module.meta.author)
                    .put("description", module.meta.description)
                    .put("source", module.source.trim())
            )
        }
        return JSONObject()
            .put("version", "batch-v1")
            .put("modules", arr)
            .toString()
    }

    /**
     * 本地四闸 + 形状检查。返回 null 表示放行，否则是一句可直接显示的原因。
     *
     * 上限只从服务端自报的 [BatchLimits] 取；`limits == null` 时**不放行**：
     * 拿不到上限就等于不知道边界，不能拿一个猜出来的 8 / 8 MiB 把用户放过去，
     * 也不能让用户白传一整批再被服务端拒。
     */
    fun localRejection(modules: List<BatchZip>, limits: BatchLimits?): String? {
        if (modules.isEmpty()) return "本次要上传的整套是空的"
        val safeLimits = limits ?: return "暂时拿不到服务端上限，先不能提交"
        if (!safeLimits.usable) return "暂时拿不到服务端上限，先不能提交"
        if (modules.size > safeLimits.maxModules) {
            return "一次最多选 ${safeLimits.maxModules} 枚；请拆成两批"
        }
        val seen = mutableSetOf<String>()
        for (module in modules) {
            if (!module.file.isFile) return "有一个包不在本地了：${module.file.name}；请重新选择"
            if (module.meta.id.isBlank()) return "读不出模块 id；请重新选择这个包"
            if (!SHA256_RE.matches(module.sha256.trim().lowercase(Locale.ROOT))) {
                return "有一个包的 sha256 还没算完；请稍后再试"
            }
            if (module.source.isBlank()) return "请填写每一枚的出处链接"
            if (!seen.add(module.meta.id)) return "同一套里同一个模块 id 出现两次：${module.meta.id}"
            if (module.file.length() > safeLimits.maxBytes) {
                return "这个包超过单枚上限（${humanBytes(safeLimits.maxBytes)}）；请换小包"
            }
        }
        val total = modules.sumOf { it.file.length() }
        if (total > safeLimits.maxTotalBytes) {
            return "这一套合计超过 ${humanBytes(safeLimits.maxTotalBytes)}；请减少枚数或换小包"
        }
        return null
    }

    /**
     * 服务端失败码 → 一句人话。判据只用 [status] 与 [code]；[serverError] 只在
     * 403/400 没有稳定码时兜底显示。**不 grep 服务端中文文案做分支**。
     *
     * [limits] 用来把「上限是多少」写进话术（§1.3 的 `%1$s` / `%1$d`）；拿不到时
     * 只说「超过上限」，不编一个数字。
     */
    fun errorCopy(
        status: Int,
        code: String?,
        serverError: String?,
        limits: BatchLimits? = null,
    ): String = when (code) {
        "zip_too_large" ->
            "有一个包超过服务端当前的单枚上限${sizeHint(limits?.maxBytes)}；请换小包后重试。"

        "batch_too_large" ->
            "这一套总大小超过服务端上限${sizeHint(limits?.maxTotalBytes)}；请减少枚数或换小包后重试。"

        "too_many_modules" -> {
            val max = limits?.maxModules?.takeIf { it > 0 }
            if (max != null) {
                "一次最多上传 $max 枚（服务端当前上限）；请拆成两批。"
            } else {
                "一次上传的枚数超过服务端当前上限；请拆成两批。"
            }
        }

        "manifest_invalid" -> "上传清单格式不对（客户端问题）；请更新 App 后重试。"
        "manifest_late" -> "上传清单没有排在最前面（客户端问题）；请更新 App 后重试。"
        "batch_incomplete" -> "服务端收到的包与清单对不上（少递、多递或重复）；请重新选择 zip 后重试。"
        "sha_mismatch" -> "有一个包在读取后变了，声明哈希与实算不一致；请重新选择这个包再提交。"
        "proof_used" ->
            "这串酷安签名已经被用过；已换了一串新的，请把新的写进酷安签名后提交。"

        // 归属拒绝：`not_owner` 是 Worker 在「这一版更新权在别人手里」时回的唯一码。
        // 单独立一支，是因为 403 的通用兜底句是「请核对签名 / 等级后重试」——
        // 对这条拒绝来说那句是**误导**：签名和等级都没问题，换谁去重试都不会通过，
        // 只有原作者能发。服务端给了原话就用原话（它会指出是哪个 id），没给才说这句。
        "not_owner" -> serverError?.takeIf { it.isNotBlank() }
            ?: "这个模块的更新版本只有原作者能发；当前验证的酷安账号不是它的上传者。"

        // 下面两条同为 403，但语义是「我们这边查不了」，服务端文案里已经明写
        // 「与你的包无关」。落到通用 403 兜底会被冠上「服务端拒绝了这次上传」，
        // 等于把我们的故障说成用户的错 —— 原话照搬，只在没原话时补一句真话。
        "index_unavailable" -> serverError?.takeIf { it.isNotBlank() }
            ?: "服务端的模块索引暂时读不到，本次没有提交；与你的包无关，请稍后重试。"

        "owner_unknown" -> serverError?.takeIf { it.isNotBlank() }
            ?: "服务端暂时查不出这个模块的归属，本次没有提交；与你的包无关，请稍后联系站点维护者。"

        else -> when {
            status == 429 -> "上传太频繁了，请稍后再试。"
            status >= 500 -> "服务端没能处理这次上传，可能是批量太大或临时故障；请拆小后重试。"
            status == 403 -> serverError?.takeIf { it.isNotBlank() }
                ?.let { "服务端拒绝了这次上传：$it" }
                ?: "服务端拒绝了这次上传；请核对签名 / 等级后重试。"
            status == 413 -> "服务端拒绝了这次上传：包太大；请换小包后重试。"
            status == 400 -> serverError?.takeIf { it.isNotBlank() }
                ?: "服务端拒绝了这次上传；请检查表单后重试。"
            status == 0 -> serverError?.takeIf { it.isNotBlank() } ?: "上传未完成；请重试。"
            // 2xx 但 ok:false：服务端明确拒绝，却没有给原因。别把它说成「HTTP 200」。
            status in 200..299 -> serverError?.takeIf { it.isNotBlank() }
                ?: "服务端拒绝了这次上传；请稍后重试或反馈。"
            else -> serverError?.takeIf { it.isNotBlank() } ?: "上传失败（HTTP $status）"
        }
    }

    private fun sizeHint(bytes: Long?): String =
        if (bytes != null && bytes > 0) "（${humanBytes(bytes)}）" else ""

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MiB"
        bytes >= 1024L -> "${bytes / 1024L} KiB"
        else -> "$bytes B"
    }
}
