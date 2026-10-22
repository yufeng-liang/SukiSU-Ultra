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
)

/** 成功应答里的逐枚回显。 */
data class SubmittedModule(
    val id: String,
    val versionCode: Long,
    val sha256: String,
    val assetName: String,
)

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
        "proof_used" -> "这串口令已经被用过；已换了一串新的，请把新口令重新写进签名后提交。"
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
