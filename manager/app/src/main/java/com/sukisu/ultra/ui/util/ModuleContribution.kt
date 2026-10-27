package com.sukisu.ultra.ui.util

import com.sukisu.ultra.data.backup.sha256Hex
import com.sukisu.ultra.ksuApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Locale

/** nonce 的字符集：6 位小写字母数字，与 Worker 的 `PROOF_NONCE_RE`（`/^[a-z0-9]{6}$/`）同一形状。 */
private val NONCE_ALPHABET = ('a'..'z') + ('0'..'9')

/** 共用一个实例：`SecureRandom` 自带种子与锁，每次取字符都新建一个纯属浪费（且没有更安全）。 */
private val NONCE_RANDOM = SecureRandom()

/**
 * 承诺口令的前缀：`sha256("<模块 id>:<整包 sha256>")` 的前 8 位小写十六进制。
 *
 * 与 worker/src/proof.ts、public/scripts/proof.js 是同一套公式的第三份实现——
 * 三处必须逐字符同值，服务端拿它比对用户写进酷安签名的口令。
 *
 * 为什么绑**整包**而不只绑模块 id（设计文档 §14.1）：这串口令是先公开（填进酷安签名，
 * 游客态可读）、后被消费的，所以只绑 id 的口令能被抢投——任何人读到受害者正在用的口令，
 * 就能用自己的恶意 zip 在同一个模块 id 下提交，五道闸全过，归档的却是受害者的身份。
 * 前缀里乘进包体哈希后，复用这串口令就必须提交**受害者那一模一样的包**，那已经不是攻击。
 *
 * [zipSha] 必须是提交时作为 multipart `sha256` 发出去的那一个值（[ModuleProp.zipSha256] 算的
 * 整包摘要）：界面上显示的哈希与实际提交的不同，等于又造出一个新的 bug。
 *
 * `Charsets.UTF_8` 是显式写死的，不能省：省略就是隐式平台默认字符集，中文模块 id 在
 * 设备上（Android 恒为 UTF-8）没事、换一台默认字符集不是 UTF-8 的 JVM 上就悄悄算出另一串，
 * 而这条链上三处实现只有这一处可能这样漂。
 */
fun proofPrefixFor(id: String, zipSha: String): String =
    "$id:$zipSha".toByteArray(Charsets.UTF_8).sha256Hex().take(8)

/** 一次性随机段：6 位小写字母数字。只存这一段，口令前缀现算，杜绝与模块 id 脱钩。 */
fun makeNonce(): String =
    (1..6).map { NONCE_ALPHABET[NONCE_RANDOM.nextInt(NONCE_ALPHABET.size)] }.joinToString("")

/**
 * 承诺文本版本。必须与 Worker 的 `PLEDGE_VERSIONS` 完全一致。
 *
 * 从 private 改成 internal 只为了 [com.sukisu.ultra.ui.component.ModuleContributionRulesTest]
 * 能钉住这个字面量（它与 proofText 的形状此前没有任何测试）；internal 不出模块，
 * 不构成对外的公开 API。
 */
internal const val PLEDGE_VERSION = "pledge-v1"

/**
 * 模块索引是否已收录这一版。
 *
 * [UNKNOWN] 是"没问出来"，不是"没收录"：调用方必须按"无法确认"处理，
 * 绝不能因为它不是 [RECORDED] 就当成新模块去提示分享——服务器一时不可达时
 * 那会让每个用户每次安装都看到一次分享提示。
 */
enum class CheckStatus {
    RECORDED,
    NEW,
    VERSION_UPDATE,
    UNKNOWN,
}

/**
 * 「这一版更新权归不归当前这个酷安 UID」——服务端 `update_eligible` 的**三态**。
 *
 * 为什么不是 `Boolean`：服务端把「没答案」与「不是你的」刻意分开。
 * - 没带 uid、索引里没有这一枚、请求超时、应答缺这一格 —— 都只能是 [UNKNOWN]；
 * - 只有服务端**明确**回了 `false` 才是 [NOT_MINE]。
 *
 * 把 null 当成 false 的后果是实打实的：用户填完一整页、改完签名、上传几百 MB，
 * 最后吃一个服务端 403；而把 false 当成 null 的后果相反 —— 一条本该拦住的
 * 「替别人发更新」被放过去，只能等服务端 403 兜底。两个方向都得靠这一格分辨。
 *
 * [reason] 是服务端原话，只在 [NOT_MINE] 时有意义，直接给界面显示（不替服务端编原因）。
 */
enum class UpdateEligibility {
    /** 没问出来：没带 uid / 索引里没有这一枚 / 请求失败 / 应答里没这一格。 */
    UNKNOWN,

    /** 服务端明确说：这一枚已收录的模块归当前这个 UID，可以发更新。 */
    MINE,

    /** 服务端明确说：已被别人收录，只有原作者能发更新版本。 */
    NOT_MINE,
    ;

    /** 只有这一种才该拦下提交。UNKNOWN 不许拦 —— 那是「不知道」，不是「不是你的」。 */
    val blocksSubmit: Boolean get() = this == NOT_MINE
}

/**
 * 「我的模块」里的一枚（`GET /mine` 返回的一行）。
 *
 * 每一格都来自索引（公开事实）+ `mine:` 台账。索引里没有 `source`，服务端就回空串而
 * 不是编一个 `"coolapk"` 出来 —— 所以 [version]/[name] 这类字段可能是空串，
 * 界面要按「不知道」渲染，不能当成真的空。
 */
data class MineModule(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val versionCode: Long,
    val sha256: String,
    /** 索引里那份 `time`（ms）；0 = 索引没给，不是「1970 年」。 */
    val time: Long,
)

/**
 * `GET /mine` 的四种落点。
 *
 * 分成四态而不是「列表 or 空」是因为它们的**下一步动作完全不同**：
 * - [Ok] 但 [Ok.degraded] 为真 → 说「暂时读不到」，可重试；
 * - [Ok] 且不 degraded 且列表空 → 说「你还没有投过模块」，引导去投第一个；
 * - [NeedVerify] → 引导重新验证身份（令牌失效）；
 * - [Failed] → 说网络/服务问题，可重试。
 *
 * 把后两者混成一句「加载失败」，用户就分不清「该重新验证」还是「等会儿再试」。
 */
sealed interface MineResult {
    data class Ok(val modules: List<MineModule>, val degraded: Boolean) : MineResult

    /** 令牌没给、或服务端说失效：要重新验证身份。 */
    data object NeedVerify : MineResult

    /** 网络/服务异常：可重试。 */
    data object Failed : MineResult
}

/**
 * 一次收录查询的完整答案：状态 + 归属 + 服务端原话。
 *
 * 三个字段来自**同一个应答**，所以打包成一个返回值而不是三次回调：
 * 拆开传会让调用方有机会只更新其中一格，留下「status 是新的、eligibility 是旧的」这种
 * 半新半旧的行 —— 那种行在界面上看起来完全正常，只是归属闸会按过期信息放行或误拦。
 */
data class CheckAnswer(
    val status: CheckStatus,
    val eligibility: UpdateEligibility = UpdateEligibility.UNKNOWN,
    val reason: String = "",
) {
    companion object {
        /** 什么都没问出来时的答案：[CheckStatus.UNKNOWN] 且不拦提交。 */
        val UNKNOWN = CheckAnswer(CheckStatus.UNKNOWN)

        /**
         * 解析服务端 `update_eligible` 的那一格。
         *
         * `JSONObject.optBoolean` 对 JSON `null` 与键缺失都返回 false —— 那正好把
         * 「没答案」误判成「不是你的」，所以这里必须先 `isNull` 再看类型。
         * 服务端契约是 `boolean | null`；收到字符串 `"true"` 之类的花活也算没答案，
         * 不猜。
         */
        fun parseEligibility(json: JSONObject?, key: String = "update_eligible"): UpdateEligibility {
            if (json == null) return UpdateEligibility.UNKNOWN
            if (json.isNull(key)) return UpdateEligibility.UNKNOWN
            val raw = json.opt(key) ?: return UpdateEligibility.UNKNOWN
            return when (raw) {
                is Boolean -> if (raw) UpdateEligibility.MINE else UpdateEligibility.NOT_MINE
                else -> UpdateEligibility.UNKNOWN
            }
        }
    }
}

/**
 * 投稿结果。
 *
 * [ok] 为 false 时 [message] 一定非空且是人类可读的中文原因，供界面直接显示；
 * [prUrl] 只在 [ok] 为 true 时有值（服务端成功但没回 PR 链接时也可能为 null）。
 */
data class SubmitResult(
    val ok: Boolean,
    val prUrl: String?,
    val message: String,
    /**
     * 服务端非 2xx 应答体里的机器可读失败码（`code` 字段），没有则为 null。
     *
     * 目前唯一的取值是 Worker 的 `"proof_used"`（contrib/worker/src/index.ts，used-nonce
     * 命中那条 403）：客户端靠它决定「换一枚 nonce 再发」，而不是靠 grep 中文文案 ——
     * 文案改一个字就会悄悄把旋转逻辑关掉（网页端 app.js 已改为码优先 + 文案兜底，
     * 这里是 App 侧的同一条规则，见 [com.sukisu.ultra.ui.component.ModuleContributionRules.proofWasAlreadyUsed]）。
     */
    val code: String? = null,
)

/**
 * 酷安 UID 的核实结果（`GET /user?uid=` 的四态 + 本地判定的形状不对）。
 *
 * 投稿人身份从"自己填一个署名"改成"填酷安 UID 并核实出昵称"之后，这里就是那道门的全部取值：
 * 只有 [Verified] 允许提交 —— 昵称由酷安反查得到，不是用户自己声明的字符串。
 *
 * [NotFound] 是"酷安明确说没有这个人"，属于**拦人**的结论；[Unavailable] 只是"这次没查成"，
 * 两者的界面文案必须分开：前者要用户去核对 UID，后者只需要稍后重试。
 * [SelfReported] 是后端把核实开关关掉了（`COOLAPK_VERIFY=off`），此时拿不到昵称，
 * 也就没有可提交的署名 —— 与"没查成"一样不放行，但原因不同。
 */
sealed interface CoolapkUidLookup {
    /**
     * 酷安返回了这个 UID 的昵称。唯一允许提交的状态。
     *
     * [avatar] 是酷安头像的 http(s) 地址，可能为空（酷安没给、或不是 http(s)）。
     * 它只是界面装饰：为空时界面不画头像，任何判据都不看它。
     */
    data class Verified(val nickname: String, val avatar: String = "") : CoolapkUidLookup

    /** 酷安明确说没有这个用户。 */
    object NotFound : CoolapkUidLookup

    /** 后端没有核实能力（核实开关被关掉）。 */
    object SelfReported : CoolapkUidLookup

    /** 这次没核实成（酷安不可达 / 风控 / 超时）。 */
    object Unavailable : CoolapkUidLookup
}

/**
 * 「这张长期令牌还在不在」的三态。
 *
 * 三态必须分开，不能压成 `UploadSession?`：
 * - [Valid]：服务端确认这张还能用，并回了一份身份 —— 换上它、写回磁盘；
 * - [Invalid]：服务端**明确**说这张失效了（`{ok:false}`），或者本地这串形状就不对
 *   （形状不对的令牌永远不可能有效，问服务端只会变成一次「猜中即证实」的探测）——
 *   本地必须抹掉。压成 null 的话 `sessionActive` 仍为真、签名区整段收起，
 *   提交必被服务端 403 拒，而错误文案要求用户去改的那个口令界面根本没显示；
 * - [Unavailable]：网络失败、超时、非 2xx、脏应答 —— **什么都不该改**：
 *   网络抖一次不该把用户九十天的便利抹掉，宁可下次再问。
 *
 * 这三个取值的差别就是旧实现（`ok:false` 与网络失败同一个 null 出口）的 P0。
 */
sealed interface SessionCheck {
    /** 服务端确认有效，并回了一份可以直接写进本地与界面的身份。 */
    data class Valid(val session: UploadSession) : SessionCheck

    /** 服务端明确否认（或本地形状已坏）：本地那一份必须抹掉。 */
    data object Invalid : SessionCheck

    /** 这一趟没问出来：网络/超时/脏应答。什么都不改。 */
    data object Unavailable : SessionCheck
}

/**
 * 模块投稿的网络层。
 *
 * 这个文件里的每个公开方法都**不抛异常**：网络不可达、超时、HTTP 非 2xx、
 * 服务端返回不是 JSON、JSON 里没有约定字段，一律降级成 [CheckStatus.UNKNOWN]
 * 或 [SubmitResult] 的 `ok=false`。理由是调用点全在 UI 层——本地 zip 刚装完的
 * 那个回调里抛一个 IOException，用户看到的就是崩溃或装完没反应。
 *
 * 上传怎么走：仓库里已有的 [ksuApp.okhttpClient] 是 GET/下载向的
 * （ui/util/Downloader.kt 只支持 GET），multipart 上传在这里补上，
 * 请求体的写法与 data/backup/WebDavBackupStorage.kt 的 put 一致（OkHttp 的
 * [File.asRequestBody]，流式读盘，不把整个 zip 读进内存）。
 */
object ModuleContribution {

    /**
     * 投稿服务的地址：Cloudflare Pages 网关，把请求原样转给 Worker。
     *
     * 为什么不直接用 Worker 的 `*.workers.dev`：那个后缀在国内被 DNS 投毒 + TLS SNI
     * 双重阻断（实测四路解析器给出的都是 Facebook/Twitter/Dropbox 的 IP，手工喂真实
     * CF 边缘 IP 后握手仍被 RST）。用户侧的必然表现是 `/check` 恒为 [CheckStatus.UNKNOWN]
     * ——每行模块都挂「无法确认是否已收录」——且永远提交不了，而文案指不到网络层。
     * `*.pages.dev` 可直连，网关只做转发，凭据与业务判断仍在 Worker 上。
     */
    const val BASE_URL: String = "https://sukisu-module-api.pages.dev"

    /**
     * 查询类请求（`/check`、`/user`）的超时。8 秒：安装成功之后的额外一步，等太久会让用户以为卡住了。
     * 这两个端点 Worker 侧自身很快返回（最坏是酷安一次往返），不需要投稿那种大预算。
     */
    private const val QUERY_TIMEOUT_MS = 8_000L

    /**
     * 投稿（`/submit`）的超时：**20 秒**。
     *
     * 算术（对齐 Worker 的真实常量，contrib/worker/src/coolapk.ts，2026-10-12 核实）：
     * 所有权闸最坏 = 2 个源 × `COOLAPK_SPACE_TIMEOUT_MS`（4000ms，逐源独立、第一个挂满才换第二个）
     * = **8 秒**，这还没算上传；再加上传与后端写入预算 **12 秒**
     * （8MB zip —— Worker `MAX_TOTAL_BYTES` 的上限 —— 在慢速上行下按 ~5 Mbps 量 ≈ 13 秒的一半多，
     * 按正常移动网络量 ≤8 秒，另加 Worker 解析与 GitHub 侧开 PR 的几秒）= **20 秒**。
     *
     * 为什么必须大于后端最坏值：客户端先超时而 Worker 继续跑完，会照常开 PR 并**烧掉口令**，
     * 而超时不触发 nonce 轮换（[com.sukisu.ultra.ui.component.ModuleContributionRules.proofWasAlreadyUsed]
     * 认的是服务端明说「用过」）—— 用户接下来必然吃一次「这个口令已经用过」，
     * 为一次他没造成的失败白跑一个来回。
     *
     * 为什么不更大：Cloudflare Worker 单次请求的挂钟就在 30 秒量级，客户端等更久只会
     * 更久地盯着一个不会再有结果的进度条。
     */
    private const val SUBMIT_TIMEOUT_MS = 20_000L

    /** 只认这几种取值，其余（含大小写以外的花活）一律 [CheckStatus.UNKNOWN]。 */
    private val STATUS_BY_NAME = mapOf(
        "recorded" to CheckStatus.RECORDED,
        "new" to CheckStatus.NEW,
        "version_update" to CheckStatus.VERSION_UPDATE,
        "unknown" to CheckStatus.UNKNOWN,
    )

    private val ZIP_MEDIA_TYPE = "application/zip".toMediaType()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * 批量上传的整次预算：180 秒。
     *
     * 20 秒那档是单枚 8 MiB 的预算；8 枚 64 MiB 在 5 Mbps 上就要 ~107 秒，
     * 再用 20 秒必然在客户端先断，而 Worker 会照常跑完、烧掉口令。
     * Cloudflare Workers Free **没有请求挂钟上限**（限制是 10ms CPU），
     * 所以客户端这一侧给足时间；真正的天花板在服务端 CPU，不在这个数。
     */
    private const val BATCH_SUBMIT_TIMEOUT_MS = 180_000L

    /** `/check` 的批大小兜底。服务端当前上限也是 8；调用方应按 `maxModules` 切。 */
    private const val CHECK_BATCH_MAX = 8

    private const val TURNSTILE_HEADER = "cf-turnstile-response"

    /** 客户端本地超时的机器可读码（服务端不会返回这个码）。文案由界面按 locale 取。 */
    const val CLIENT_TIMEOUT_CODE = "client_timeout"

    private val PROOF_NONCE_RE = Regex("^[a-z0-9]{6}$")

    /**
     * 长期令牌的形状：64 位小写十六进制，与 Worker `newSessionToken()` 生成的同一形状。
     *
     * 形状不对的一串**不发出去**（服务端会当没带、回落签名路），也不往本地存。
     */
    internal val SESSION_TOKEN_RE = Regex("^[0-9a-f]{64}$")

    /**
     * 合法模块 id 的形状：字母开头，之后只允许字母、数字、点、下划线、连字符。
     *
     * 与 contrib/audit/risk-rules.mjs 的 `ID_PATTERN`（`/^[a-zA-Z][a-zA-Z0-9._-]*$/`）是同一条约定：
     * 服务端侧拿它判"命名规范"，这一侧拿它挡查询串注入——两边必须一致，
     * 否则会出现"App 认为合法、审核端判定不规范"的条目。
     */
    private val ID_PATTERN = Regex("[a-zA-Z][a-zA-Z0-9._-]*")

    /** id 的长度上限。索引里 id 是归并键，超长的只可能是恶意构造。 */
    private const val MAX_ID_LENGTH = 64

    /**
     * 非 2xx 时从 body 里带出的 `error` 最多留多少个字。
     *
     * 这一句是要被对话框原样渲染成一行错误提示的，而服务端那一侧不是"给人看的一句话"的
     * 保证方：中间夹了 Cloudflare，出错时回的可能是整页 HTML、上游栈回显，几 KB 起步。
     * 不设上限，一次失败就会把口令行下面撑出一屏网页，用户既读不出原因，也复制不了口令。
     * 上限之上的部分对判据没有价值：[com.sukisu.ultra.ui.component.ModuleContributionRules.proofWasAlreadyUsed]
     * 的首选判据是机器可读的 `code`（独立于文案、不受截断影响），这句话的文案只是旧客户端
     * 兜底——截断必须保住句首的「这个口令已经用过」，兜底才仍然认得出。
     */
    private const val MAX_ERROR_REASON_CHARS = 200

    /** 酷安 UID：4~12 位纯数字（与 Worker / 网页端同一约定）。 */
    private val UID_RE = Regex("\\d{4,12}")

    /**
     * 问服务端"这个 id 的这一版收录了吗"。
     *
     * 任何失败都返回 [CheckStatus.UNKNOWN]，不抛异常。
     *
     * id 先过白名单 [isValidId] 再转义进查询串：形状不合规的 id 直接算"查不出来"，
     * 不会被拿去和索引比对，也不会让调用方把它当成新模块去提示分享。
     */
    suspend fun checkRecorded(id: String, versionCode: Int, uid: String = ""): CheckAnswer {
        if (!isValidId(id)) return CheckAnswer.UNKNOWN
        val url = checkUrl(id, versionCode, uid)
        return withContext(Dispatchers.IO) {
            val call = runCatching { client().newCall(Request.Builder().url(url).get().build()) }
                .getOrNull() ?: return@withContext CheckAnswer.UNKNOWN
            // 只吞自己那次超时；调用方发起的取消（界面关了、协程取消了）必须照常往外传，
            // 否则已经没人等结果了，这里还会继续跑完整个请求。
            try {
                withTimeout(QUERY_TIMEOUT_MS) { execute(call, QUERY_TIMEOUT_MS) }.use { response -> readAnswer(response) }
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) CheckAnswer.UNKNOWN else throw e
            } catch (e: Throwable) {
                CheckAnswer.UNKNOWN
            }
        }
    }

    /**
     * 「我的模块」（`GET /mine?token=`，契约 §3.2）。
     *
     * 服务端回三态而不是「列表」：
     * - `ok:true, degraded:false` → [MineResult.Ok]，列表可能是空的（真的一个都没投过）；
     * - `ok:true, degraded:true` → [MineResult.Ok] 但 `degraded=true`：**读不到索引**，
     *   这时候的空列表不是「你没有模块」，而是「暂时看不到」。两种都得让界面分得出来 ——
     *   把后者显示成前者，用户会以为投稿丢了，然后再投一遍；
     * - `ok:false` → [MineResult.NeedVerify]：令牌失效是常态，界面该引导重新验证，
     *   而不是报一句「网络错误」。
     *
     * 令牌只进 query（服务端就是这么定的），不进 header —— 与网页端 `api.js` 的
     * `fetchMine` 同一个形状。
     */
    suspend fun fetchMine(token: String): MineResult = withContext(Dispatchers.IO) {
        val tok = token.trim()
        if (tok.isEmpty()) return@withContext MineResult.NeedVerify
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(HOST)
            .addPathSegment("mine")
            .addQueryParameter("token", tok)
            .build()
            .toString()
            .replaceFirst("https://$HOST", BASE_URL.trimEnd('/'))
        val call = runCatching { client().newCall(Request.Builder().url(url).get().build()) }
            .getOrNull() ?: return@withContext MineResult.Failed
        try {
            withTimeout(QUERY_TIMEOUT_MS) { execute(call, QUERY_TIMEOUT_MS) }.use { response ->
                if (!response.isSuccessful) return@withContext MineResult.Failed
                val body = runCatching { response.body.string() }.getOrNull()
                    ?: return@withContext MineResult.Failed
                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: return@withContext MineResult.Failed
                if (!json.optBoolean("ok", false)) return@withContext MineResult.NeedVerify
                val arr = json.optJSONArray("modules")
                val list = ArrayList<MineModule>(arr?.length() ?: 0)
                for (i in 0 until (arr?.length() ?: 0)) {
                    val row = arr?.optJSONObject(i) ?: continue
                    val id = row.optString("id").trim()
                    if (id.isEmpty()) continue
                    list.add(
                        MineModule(
                            id = id,
                            name = row.optString("name").trim(),
                            author = row.optString("author").trim(),
                            version = row.optString("version").trim(),
                            versionCode = row.optLong("versionCode", 0L),
                            sha256 = row.optString("sha256").trim(),
                            time = row.optLong("time", 0L),
                        )
                    )
                }
                MineResult.Ok(list, degraded = json.optBoolean("degraded", false))
            }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) MineResult.Failed else throw e
        } catch (e: Throwable) {
            MineResult.Failed
        }
    }

    /**
     * 读服务端自报的上限（`GET /`）。
     *
     * 这是上限的**唯一来源**：App 里不许再抄一本 8 / 8 MiB / 64 MiB 的账。
     * 任何失败都返回 null，调用方必须按「拿不到上限」处理，不能退回猜的值。
     */
    suspend fun fetchLimits(): BatchLimits? = withContext(Dispatchers.IO) {
        val url = BASE_URL.trimEnd('/') + "/"
        val call = runCatching { client().newCall(Request.Builder().url(url).get().build()) }.getOrNull()
            ?: return@withContext null
        try {
            withTimeout(QUERY_TIMEOUT_MS) { execute(call, QUERY_TIMEOUT_MS) }.use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = runCatching { response.body.string() }.getOrNull() ?: return@withContext null
                val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
                val maxModules = json.optInt("maxModules", 0)
                val maxBytes = json.optLong("maxBytes", 0L)
                val maxTotalBytes = json.optLong("maxTotalBytes", 0L)
                if (maxModules <= 0 || maxBytes <= 0L || maxTotalBytes <= 0L) {
                    null
                } else {
                    BatchLimits(maxModules, maxBytes, maxTotalBytes)
                }
            }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) null else throw e
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 批量查收录状态（`POST /check`）。
     *
     * 服务端一次最多 8 条（与 `maxModules` 同值），这里按 [CHECK_BATCH_MAX] 再切一道，
     * 防止调用方漏切时把整张表一把塞过去。任何一批失败都只是**这一批**没有结果，
     * 调用方按 [CheckAnswer.UNKNOWN] 处理——绝不猜成 [CheckStatus.NEW]。
     *
     * [uid] 是**投稿人自己的**酷安 UID（不是模块作者的）：服务端拿它算 `update_eligible`，
     * 回答「这一版更新权归不归这个人」。不带 uid 时服务端照常回 status，但
     * `update_eligible` 恒为 null（[UpdateEligibility.UNKNOWN]）——归属闸在这条路上就是空的。
     * 所以只要调用方知道 uid，就必须传：批量这条路正是「一键更新」走的那条
     * （网页端 `api.js` 的 `checkModulesBatch` 有同一条注释与同一个坑）。
     */
    suspend fun checkBatch(
        modules: List<Pair<String, Int>>,
        uid: String = "",
    ): Map<String, CheckAnswer> =
        withContext(Dispatchers.IO) {
            if (modules.isEmpty()) return@withContext emptyMap()
            val out = LinkedHashMap<String, CheckAnswer>()
            for (chunk in modules.chunked(CHECK_BATCH_MAX)) {
                out.putAll(checkBatchChunk(chunk, uid))
            }
            out
        }

    private suspend fun checkBatchChunk(
        modules: List<Pair<String, Int>>,
        uid: String,
    ): Map<String, CheckAnswer> {
        val who = uid.trim()
        val arr = JSONArray()
        for ((id, versionCode) in modules) {
            val row = JSONObject().put("id", id).put("versionCode", versionCode)
            // 空 uid 不下发这个键，而不是下发一个空串：服务端把空串当「没给」，
            // 但让请求体里明明白白写着 `uid:""` 会诱使下一个人以为「已经带上了」。
            if (who.isNotEmpty()) row.put("uid", who)
            arr.put(row)
        }
        val payload = JSONObject().put("modules", arr).toString()
        val url = BASE_URL.trimEnd('/') + "/check"
        val call = runCatching {
            client().newCall(
                Request.Builder().url(url).post(payload.toRequestBody(JSON_MEDIA_TYPE)).build()
            )
        }.getOrNull() ?: return emptyMap()
        return try {
            withTimeout(QUERY_TIMEOUT_MS) { execute(call, QUERY_TIMEOUT_MS) }.use { response ->
                if (!response.isSuccessful) return emptyMap()
                val text = runCatching { response.body.string() }.getOrNull() ?: return emptyMap()
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
                val results = json.optJSONArray("results") ?: return emptyMap()
                val out = LinkedHashMap<String, CheckAnswer>()
                for (i in 0 until results.length()) {
                    val row = results.optJSONObject(i) ?: continue
                    val id = row.optString("id").trim()
                    if (id.isEmpty()) continue
                    val name = row.optString("status").trim().lowercase(Locale.ROOT)
                    out[id] = CheckAnswer(
                        status = STATUS_BY_NAME[name] ?: CheckStatus.UNKNOWN,
                        eligibility = CheckAnswer.parseEligibility(row),
                        reason = row.optString("reason").trim(),
                    )
                }
                out
            }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) emptyMap() else throw e
        } catch (e: Throwable) {
            emptyMap()
        }
    }

    /**
     * 一次批量提交。
     *
     * 形状：`manifest` 是**第一个** part，随后是共享字段（uploader / coolapkUid /
     * pledge / nonce），最后按 manifest 顺序追加 N 个 `name="zip"`。
     * 令牌走 header `cf-turnstile-response`（Worker 优先读 header，能在解析 body 前挡掉）。
     *
     * 任何失败都返回 `ok=false` 且带一句可显示的原因，不抛异常；超时交给调用方
     * 的 `withTimeout` 捕获（与旧 [submitModule] 同一条约定）。
     */
    suspend fun submitBatch(
        modules: List<BatchZip>,
        uid: String,
        nickname: String,
        nonce: String,
        turnstileToken: String?,
        limits: BatchLimits?,
        sessionToken: String? = null,
    ): BatchSubmitResult {
        val safeUid = uid.trim()
        val safeNickname = nickname.trim()
        val safeNonce = nonce.trim()
        // 令牌只在形状对时才发出去：形状不对的一串服务端会当没带（回落签名路），
        // 白白让用户以为「我已经验证过了」，而界面那一侧已经按令牌把签名格收起来了。
        val safeSession = sessionToken?.trim()?.takeIf { SESSION_TOKEN_RE.matches(it) }.orEmpty()
        if (!isValidUid(safeUid)) {
            return BatchSubmitResult(false, null, emptyList(), "酷安 UID 必须是 4~12 位数字")
        }
        if (safeNickname.isEmpty()) {
            return BatchSubmitResult(false, null, emptyList(), "酷安 UID 未核实，拿不到署名")
        }
        // 带令牌时 nonce 可以不在形状内：服务端同一处判据是
        // `if (!PROOF_NONCE_RE.test(nonce) && sessionToken === '')` —— 令牌这条路不比对签名，
        // 也就没有 nonce 可比。空串仍然要过形状（那是签名路的判据）。
        if (safeSession.isEmpty() && !PROOF_NONCE_RE.matches(safeNonce)) {
            return BatchSubmitResult(false, null, emptyList(), "口令随机段格式不对（应为 6 位小写字母数字）")
        }
        // 本地四闸与形状检查只留一份实现：界面上的 canSubmit 只是提前拦，
        // 真正发请求前再用同一条规则核对一次（文件可能在这期间被删/被改）。
        // 这一步要 stat N 个文件，放在 IO 上做。
        val body = buildBatchBody(modules, safeNickname, safeUid, safeNonce, safeSession)
        val url = BASE_URL.trimEnd('/') + "/submit"
        val builder = Request.Builder().url(url).post(body)
        val token = turnstileToken?.trim().orEmpty()
        if (token.isNotEmpty()) builder.header(TURNSTILE_HEADER, token)
        // 必须在 IO 上跑：调用方是 viewModelScope（主线程），
        // `call.execute()` 在主线程会直接 NetworkOnMainThreadException。
        return withContext(Dispatchers.IO) {
            ContributionBatch.localRejection(modules, limits)?.let { reason ->
                return@withContext BatchSubmitResult(false, null, emptyList(), reason)
            }
            val call = runCatching { client().newCall(builder.build()) }.getOrNull()
                ?: return@withContext BatchSubmitResult(false, null, emptyList(), "无法发起上传请求")
            try {
                withTimeout(BATCH_SUBMIT_TIMEOUT_MS) {
                    execute(call, BATCH_SUBMIT_TIMEOUT_MS)
                }.use { response -> readBatchSubmitResult(response, limits) }
            } catch (e: TimeoutCancellationException) {
                // 内层超时不是「调用方取消」：这里把它收成一句可显示的失败，
                // 让界面能复位 submitting，而不是把整次提交挂住。
                // 文案由界面按 locale 取（[CLIENT_TIMEOUT_CODE]），这里不写死中文。
                BatchSubmitResult(
                    false,
                    null,
                    emptyList(),
                    message = "",
                    code = CLIENT_TIMEOUT_CODE,
                )
            } catch (e: CancellationException) {
                // 调用方取消（界面关了）照常往外传。
                throw e
            } catch (e: Throwable) {
                // OkHttp 的 call timeout 通常先于协程超时触发，抛的是
                // InterruptedIOException("timeout")——别把它报成「网络不可用」。
                if (e is InterruptedIOException) {
                    BatchSubmitResult(
                        false,
                        null,
                        emptyList(),
                        message = "",
                        code = CLIENT_TIMEOUT_CODE,
                    )
                } else {
                    BatchSubmitResult(false, null, emptyList(), describeUpload(e))
                }
            }
        }
    }

    /**
     * 批量 multipart。公开成 internal 只为让单测能直接盯住「manifest 是第一个 part」
     * 与 zip 的顺序——这两件事写错，服务端会回 `manifest_late` / `batch_incomplete`，
     * 而界面上只会看到一次没有原因的长上传失败。
     */
    internal fun buildBatchBody(
        modules: List<BatchZip>,
        uploader: String,
        uid: String,
        nonce: String,
        sessionToken: String = "",
    ): MultipartBody = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("manifest", ContributionBatch.buildManifestJson(modules))
        .addFormDataPart("uploader", uploader)
        .addFormDataPart("coolapkUid", uid)
        .addFormDataPart("pledge", PLEDGE_VERSION)
        .addFormDataPart("nonce", nonce)
        // 令牌恒发一个 part（空串也要发）：服务端读的是 `fields.session`，
        // 「字段不在」与「字段是空串」在它那里是同一件事，但少发一个 part 会让
        // multipart 的形状随登录状态变化 —— 契约上共享字段恒在顶层更省事。
        .addFormDataPart("session", sessionToken)
        .apply {
            for (module in modules) {
                addFormDataPart(
                    "zip",
                    module.file.name.ifBlank { "${module.meta.id}-${module.meta.versionCode}.zip" },
                    module.file.asRequestBody(ZIP_MEDIA_TYPE),
                )
            }
        }
        .build()

    private fun readBatchSubmitResult(response: Response, limits: BatchLimits?): BatchSubmitResult {
        val text = runCatching { response.body.string() }.getOrNull().orEmpty()
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (!response.isSuccessful) {
            val serverError = json?.optString("error")?.takeIf { it.isNotBlank() }
                ?.take(MAX_ERROR_REASON_CHARS)
            val code = json?.optString("code")?.trim()?.takeIf { it.isNotEmpty() }
            return BatchSubmitResult(
                false,
                null,
                emptyList(),
                ContributionBatch.errorCopy(response.code, code, serverError, limits),
                code,
            )
        }
        if (json == null) {
            return BatchSubmitResult(false, null, emptyList(), "上传失败（服务端响应不是合法 JSON）")
        }
        if (!json.optBoolean("ok", false)) {
            val serverError = json.optString("error").takeIf { it.isNotBlank() }
                ?.take(MAX_ERROR_REASON_CHARS)
            val code = json.optString("code").trim().takeIf { it.isNotEmpty() }
            return BatchSubmitResult(
                false,
                null,
                emptyList(),
                ContributionBatch.errorCopy(response.code, code, serverError, limits),
                code,
            )
        }
        val submitted = mutableListOf<SubmittedModule>()
        val arr = json.optJSONArray("modules")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                submitted += SubmittedModule(
                    id = row.optString("id"),
                    versionCode = row.optLong("versionCode"),
                    sha256 = row.optString("sha256"),
                    assetName = row.optString("assetName"),
                )
            }
        }
        return BatchSubmitResult(
            ok = true,
            prUrl = json.optString("pr").takeIf { it.isNotBlank() },
            modules = submitted,
            message = "",
            session = readSessionGrant(json.optJSONObject("session")),
        )
    }

    /**
     * 应答里的 `session` 三态：没有键 / 是 null ⇒ `null`（没签）；
     * 有键且形状合法 ⇒ [SessionGrant]；有键但形状不对 ⇒ 也回 `null`。
     *
     * 「形状不对就当没签」是故意的：脏数据不能顶掉本地那张好令牌。
     * 服务端那一侧 `expiresAt` 是毫秒时间戳；不是正数就当没签。
     */
    private fun readSessionGrant(json: JSONObject?): SessionGrant? {
        if (json == null) return null
        val token = json.optString("token").trim()
        val expiresAt = json.optLong("expiresAt", 0L)
        if (!SESSION_TOKEN_RE.matches(token) || expiresAt <= 0L) return null
        return SessionGrant(token = token, expiresAt = expiresAt)
    }

    private fun describeUpload(error: Throwable): String = when (error) {
        // OkHttp 的 `call.timeout` 到点抛的是 InterruptedIOException("timeout")，
        // 不是 SocketTimeoutException；先认父类，别把超时误报成「网络不可用」。
        is InterruptedIOException ->
            "上传超时；口令没有消耗，可以用同一串重试。若提示口令已用过，App 会换一串。"
        is IOException -> "网络不可用，上传未完成；口令没有消耗。"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "上传失败"
    }

    /**
     * 问服务端"这个酷安 UID 是谁"。
     *
     * 任何失败都返回一个**不放行**的结果（[CoolapkUidLookup.Unavailable] 等），不抛异常：
     * 调用点在输入框的防抖回调里，抛出去就是一次崩溃或一次没人处理的静默失败。
     *
     * 形状不合法的 UID 不打网络请求（[isValidUid] 先挡），直接 [CoolapkUidLookup.NotFound] 之外
     * 的结果由调用方按"没输入"处理——这里返回 [CoolapkUidLookup.Unavailable] 只为给一个确定取值，
     * 界面上不会走到（未通过形状校验时界面显示的是"还没填"）。
     */
    suspend fun lookupCoolapkUser(uid: String): CoolapkUidLookup {
        val safeUid = uid.trim()
        if (!isValidUid(safeUid)) return CoolapkUidLookup.Unavailable
        val url = userUrl(safeUid)
        return withContext(Dispatchers.IO) {
            val call = runCatching { client().newCall(Request.Builder().url(url).get().build()) }
                .getOrNull() ?: return@withContext CoolapkUidLookup.Unavailable
            try {
                withTimeout(QUERY_TIMEOUT_MS) { execute(call, QUERY_TIMEOUT_MS) }.use { response -> readUidLookup(response) }
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) CoolapkUidLookup.Unavailable else throw e
            } catch (e: Throwable) {
                CoolapkUidLookup.Unavailable
            }
        }
    }

    /**
     * 提交一个模块 zip。
     *
     * 任何失败都返回 `ok=false` 且带人类可读的 [SubmitResult.message]，不抛异常。
     *
     * [zip] 会被流式上传（不整体读进内存）；[meta] 为 null 时退化为"读不出模块信息"，
     * 直接失败，不让一条残缺条目进仓库。
     *
     * 身份两个字段一起发：`uploader` 是酷安反查出来的昵称（索引里公开展示），
     * `coolapkUid` 是投稿人自己填的 UID（审核与追责用）。**服务端把 `coolapkUid`
     * 定为必收字段**，缺失或不是 4~12 位数字一律 400 —— 这一侧先挡一道，
     * 免得用户白等一次上传。
     * [nonce] 是所有权口令的随机部分。[displayedZipSha256] 是对话框展示（并绑进口令）的那份
     * 整包哈希：这里对磁盘上的文件**重新实算**一遍并与它对比——只为本地把话说清楚，
     * **这不是新增的校验闸**，服务端拿到字节后自己会算（⑤-c 拒声明值与实算值不一致的条目，
     * 所有权前缀也用实算值），对不上一律拒绝；本地对比只是让用户少上传一个注定 403 的包、
     * 并看到一句和「你复制的口令」对得上的原因。
     */
    suspend fun submitModule(
        zip: File,
        meta: ModulePropInfo?,
        source: String,
        uid: String,
        nickname: String,
        nonce: String,
        displayedZipSha256: String? = null,
    ): SubmitResult {
        val resolved = meta ?: return failure("读不出模块信息（module.prop 缺失或 id/name 为空）")
        val safeSource = source.trim()
        val safeUid = uid.trim()
        val safeNickname = nickname.trim()
        if (safeSource.isEmpty()) return failure("请填写出处链接或来源说明")
        if (!isValidUid(safeUid)) return failure("酷安 UID 必须是 4~12 位数字")
        if (safeNickname.isEmpty()) return failure("酷安 UID 未核实，拿不到署名")

        // isFile 必须在哈希之前查：对不存在/是目录的路径做哈希只会得到一句
        // 「读取模块文件失败」，把「文件没了」误报成「读不动」，用户不知道去哪找原因。
        if (!zip.isFile) return failure("模块文件不存在或不是普通文件")

        val sha256 = ModuleProp.zipSha256(zip)
            ?: return failure("读取模块文件失败，无法计算校验值")

        if (!displayedZipSha256.isNullOrEmpty() && displayedZipSha256 != sha256) {
            return failure(
                "模块文件在打开对话框之后被改动了——你复制的口令绑的是改动前那份字节，" +
                    "现在这份对不上，已停止上传。请重新打包、重新打开对话框再提交。"
            )
        }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("id", resolved.id)
            .addFormDataPart("name", resolved.name)
            .addFormDataPart("version", resolved.version)
            .addFormDataPart("versionCode", resolved.versionCode.toString())
            .addFormDataPart("author", resolved.author)
            .addFormDataPart("description", resolved.description)
            .addFormDataPart("source", safeSource)
            .addFormDataPart("uploader", safeNickname)
            .addFormDataPart("coolapkUid", safeUid)
            .addFormDataPart("sha256", sha256)
            .addFormDataPart("pledge", PLEDGE_VERSION)
            .addFormDataPart("nonce", nonce)
            .addFormDataPart(
                "zip",
                zip.name.ifBlank { "${resolved.id}-${resolved.versionCode}.zip" },
                zip.asRequestBody(ZIP_MEDIA_TYPE),
            )
            .build()

        val url = BASE_URL.trimEnd('/') + "/submit"
        return withContext(Dispatchers.IO) {
            val call = runCatching { client().newCall(Request.Builder().url(url).post(body).build()) }
                .getOrNull() ?: return@withContext failure("无法发起投稿请求")
            try {
                withTimeout(SUBMIT_TIMEOUT_MS) { execute(call, SUBMIT_TIMEOUT_MS) }.use { response -> readSubmitResult(response) }
            } catch (e: CancellationException) {
                // 调用方取消（界面关了）不是失败，照常往外传。
                throw e
            } catch (e: Throwable) {
                failure(describe(e))
            }
        }
    }

    /** 客户端可以被换掉（单测里就是），生产路径用应用里那一份。 */
    private fun client(): OkHttpClient =
        runCatching { ksuApp.okhttpClient }.getOrElse { OkHttpClient() }

    /**
     * 问服务端「这张长期令牌还在不在」，并拿回它绑的那份身份。
     *
     * 服务端承诺**恒 HTTP 200**（`{ok:true, uid, nickname, avatar, level, expiresAt}` 或
     * `{ok:false, error}`）：一个「查自己的令牌还在不在」的请求回 401 只会让界面弹
     * 「登录已过期」，而真正的语义是「这张失效了，去重新验证一次」。
     *
     * 返回值是 [SessionCheck] 三态，**不抛异常**：调用点在界面初始化里，抛出去就是一次崩溃。
     * 「服务端说失效」（[SessionCheck.Invalid]）与「这一趟没问出来」
     * （[SessionCheck.Unavailable]）必须分开 —— 前者要抹掉本地那份、请用户重新验证，
     * 后者什么都不许改。旧实现把两者压成同一个 null，就是这一处 P0。
     */
    suspend fun checkSession(token: String): SessionCheck {
        val safeToken = token.trim()
        // 形状不对的一串不发出去：它永远不可能有效，问一次只会把服务端变成一个
        // 「猜中即证实」的探测器。本地这串已经坏了，按「明确失效」处理。
        if (!SESSION_TOKEN_RE.matches(safeToken)) return SessionCheck.Invalid
        val body = JSONObject().put("token", safeToken).toString().toRequestBody(JSON_MEDIA_TYPE)
        val url = BASE_URL.trimEnd('/') + "/session/check"
        return withContext(Dispatchers.IO) {
            val call = runCatching {
                client().newCall(Request.Builder().url(url).post(body).build())
            }.getOrNull() ?: return@withContext SessionCheck.Unavailable
            try {
                withTimeout(QUERY_TIMEOUT_MS) { execute(call, QUERY_TIMEOUT_MS) }.use { response ->
                    readSessionCheck(response, safeToken)
                }
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) SessionCheck.Unavailable else throw e
            } catch (e: Throwable) {
                SessionCheck.Unavailable
            }
        }
    }

    /**
     * 吊销服务端那一张令牌（「退出验证」用）。
     *
     * 服务端是**幂等**的：形状不对、压根没有这张、已经吊销过 —— 全都回 `{ok:true}`，
     * 所以这个端点不会变成一个「猜中即证实」的探测器。因此这里也不区分成败：
     * 网络不通时本地那份照样抹掉（用户的意图是「这台设备不再记住我」，服务端那张
     * 九十天后自己过期，留着它不会让本地重新变成已验证）。
     */
    suspend fun revokeSession(token: String) {
        val safeToken = token.trim()
        if (!SESSION_TOKEN_RE.matches(safeToken)) return
        val body = JSONObject().put("token", safeToken).toString().toRequestBody(JSON_MEDIA_TYPE)
        val url = BASE_URL.trimEnd('/') + "/session/revoke"
        withContext(Dispatchers.IO) {
            val call = runCatching {
                client().newCall(Request.Builder().url(url).post(body).build())
            }.getOrNull() ?: return@withContext
            runCatching {
                withTimeout(QUERY_TIMEOUT_MS) { execute(call, QUERY_TIMEOUT_MS) }.use { it.body.string() }
            }
            Unit
        }
    }

    /**
     * `/session/check` 的应答解析：三种出口，一种都不许省。
     *
     * - `{ok:false}` ⇒ [SessionCheck.Invalid]：服务端**明确**说这张失效了，界面该抹掉本地
     *   那份、请用户重新验证。这是唯一允许抹本地令牌的应答。
     * - 非 2xx / 不是 JSON / `ok:true` 但字段缺失 ⇒ [SessionCheck.Unavailable]：服务端承诺
     *   恒 200，走到这里说明部署版本不对或中间有劫持；字段缺失则是脏应答。两者都只能算
     *   「这一趟没问出来」，绝不能借它抹掉用户九十天的便利。
     * - 昵称为空 ⇒ 也按 [SessionCheck.Unavailable]：没有昵称就没有可署名的东西，
     *   这种令牌顶掉「改签名」那一步之后，提交只会回一句「拿不到署名」，
     *   用户完全看不出为什么（`canSubmit` 那条路只看 `uidVerified`）。
     */
    private fun readSessionCheck(response: Response, token: String): SessionCheck {
        if (!response.isSuccessful) return SessionCheck.Unavailable
        val text = runCatching { response.body.string() }.getOrNull()
            ?: return SessionCheck.Unavailable
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return SessionCheck.Unavailable
        if (!json.optBoolean("ok", false)) return SessionCheck.Invalid
        val uid = json.optString("uid").trim()
        val expiresAt = json.optLong("expiresAt", 0L)
        if (uid.isEmpty() || expiresAt <= 0L) return SessionCheck.Unavailable
        val nickname = json.optString("nickname").trim()
        if (nickname.isEmpty()) return SessionCheck.Unavailable
        val session = UploadSession(
            token = token,
            uid = uid,
            nickname = nickname,
            avatar = json.optString("avatar").trim(),
            expiresAt = expiresAt,
        )
        return if (session.wellFormed) SessionCheck.Valid(session) else SessionCheck.Unavailable
    }

    /**
     * 阻塞式执行；不能是 `suspend`，否则 [withTimeout] 打断不掉已经开始读响应的请求。
     *
     * 两层超时：OkHttp 的 [okhttp3.Call.timeout] 负责掐断 socket（连接上了但服务端不回话，
     * 只有协程超时的话，那个协程会被挂住直到 socket 自己超时，可能远超预算的那 20 秒）；
     * [withTimeout] 负责把 OkHttp 之外的等待（DNS、排队）也算进来。
     * 超时数值由调用方传入：查询与投稿的最坏后端耗时不是一个量级（见两个 *_TIMEOUT_MS 常量）。
     */
    private fun execute(call: okhttp3.Call, timeoutMs: Long): Response {
        runCatching { call.timeout().timeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
        return call.execute()
    }

    /**
     * `/check` 应答的完整解析：status + `update_eligible` + `reason`。
     *
     * 取代了原先「只读 status」的那一支：只读 status 会让归属闸永远是空的（三态恒 UNKNOWN），
     * 而 `/check` 一次就回了三格，分两趟读等于让调用方有机会只更新其中一格。
     */
    private fun readAnswer(response: Response): CheckAnswer {
        if (!response.isSuccessful) return CheckAnswer.UNKNOWN
        val body = runCatching { response.body.string() }.getOrNull() ?: return CheckAnswer.UNKNOWN
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return CheckAnswer.UNKNOWN
        val name = json.optString("status").trim().lowercase(Locale.ROOT)
        return CheckAnswer(
            status = STATUS_BY_NAME[name] ?: CheckStatus.UNKNOWN,
            eligibility = CheckAnswer.parseEligibility(json),
            reason = json.optString("reason").trim(),
        )
    }

    /**
     * `/user` 的应答解析。
     *
     * 四态与前端 `api.js` 的 `lookupCoolapkUser` 一一对应，但多一条**服务端契约**上的收口：
     * 非 2xx 一律按"没核实成"（[CoolapkUidLookup.Unavailable]）处理，而不是"没有核实能力"。
     * 服务端承诺任何情况都回 200，所以走到这里说明部署版本不对或中间有劫持 —— 那种时候
     * 只能当"这次没查成"，绝不能升格成 verified（那是"昵称与 UID 对应"的唯一凭据）。
     *
     * `verified` 还要求昵称非空：没有昵称就没有可署名的东西，退回 [CoolapkUidLookup.Unavailable]。
     */
    private fun readUidLookup(response: Response): CoolapkUidLookup {
        if (!response.isSuccessful) return CoolapkUidLookup.Unavailable
        val body = runCatching { response.body.string() }.getOrNull()
            ?: return CoolapkUidLookup.Unavailable
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return CoolapkUidLookup.Unavailable
        return when (json.optString("status").trim().lowercase(Locale.ROOT)) {
            "verified" -> json.optString("nickname").trim()
                .takeIf { it.isNotEmpty() }
                ?.let { nickname ->
                    // 头像只在 http(s) 时才收（与 Worker 侧 `classifyCoolapkUser` 同一条）：
                    // 把 `javascript:` 之类塞进图片加载器没有任何好处。
                    val avatar = json.optString("avatar").trim()
                    CoolapkUidLookup.Verified(
                        nickname = nickname,
                        avatar = avatar.takeIf {
                            it.startsWith("http://", true) || it.startsWith("https://", true)
                        }.orEmpty(),
                    )
                }
                ?: CoolapkUidLookup.Unavailable

            "not_found" -> CoolapkUidLookup.NotFound
            "self_reported" -> CoolapkUidLookup.SelfReported
            else -> CoolapkUidLookup.Unavailable
        }
    }

    /**
     * 投稿结果的解析。
     *
     * 非 2xx 也**必须**把 body 里的 `error` 带出来：服务端所有拒绝都写在那儿，而且是 4xx 回的
     * （`rejectOwnership` → `{ok:false, error}` + 403、缺 pledge/nonce 形状不对 → 400）。
     * 只回一句"服务端返回 HTTP 403"的话，用户看到的是没有原因的失败，而
     * [com.sukisu.ultra.ui.component.ModuleContributionRules.proofWasAlreadyUsed]
     * 那条"只有口令已用过才重掷 nonce"的判定也永远拿不到依据——它会退化成"从不重掷"。
     * HTTP 码一起留在消息里：body 不是 JSON、或压根没有 error 时，那句话就是用户仅有的信息。
     * error 取前 [MAX_ERROR_REASON_CHARS] 个字：这一句会被原样渲染成一行错误提示，
     * 而中间的 Cloudflare 出错时回的是整页 HTML / 上游栈回显，不设上限就会撑出一屏网页。
     *
     * `code` 与 `error` 一起读：非 2xx（和 200+ok:false）的拒绝体里可能带机器可读失败码
     * （Worker 的 used-nonce 分支回 `code: 'proof_used'`）。它是 nonce 轮换判据的**首选**，
     * 中文文案只是兜底 —— 网页端 app.js 已改成码优先，App 不能再只靠 grep 文案。
     */
    private fun readSubmitResult(response: Response): SubmitResult {
        if (!response.isSuccessful) {
            val withCode = "（服务端返回 HTTP ${response.code}）"
            val json = runCatching { response.body.string() }.getOrNull()
                ?.let { text -> runCatching { JSONObject(text) }.getOrNull() }
            val reason = json?.optString("error")
                ?.takeIf { it.isNotBlank() }
                ?.take(MAX_ERROR_REASON_CHARS)
            val machineCode = json?.optString("code")?.trim()?.takeIf { it.isNotEmpty() }
            return SubmitResult(false, null, reason?.let { "$it$withCode" } ?: "投稿失败$withCode", machineCode)
        }
        val body = runCatching { response.body.string() }.getOrNull()
            ?: return SubmitResult(false, null, "投稿失败（读不到服务端响应）")
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return SubmitResult(false, null, "投稿失败（服务端响应不是合法 JSON）")
        if (!json.optBoolean("ok", false)) {
            val reason = json.optString("error").takeIf { it.isNotBlank() }
                ?: "服务端拒绝了这次投稿"
            val machineCode = json.optString("code").trim().takeIf { it.isNotEmpty() }
            return SubmitResult(false, null, reason, machineCode)
        }
        return SubmitResult(true, json.optString("pr").takeIf { it.isNotBlank() }, "投稿已提交")
    }

    /** 把失败原因说成人话，而不是把一个 IOException 的类名甩给用户。 */
    private fun describe(error: Throwable): String = when (error) {
        is kotlinx.coroutines.TimeoutCancellationException ->
            "投稿超时（${SUBMIT_TIMEOUT_MS / 1000} 秒），请稍后再试"
        is SocketTimeoutException ->
            "投稿超时（${SUBMIT_TIMEOUT_MS / 1000} 秒），请稍后再试"
        is IOException -> "网络不可用，投稿未完成"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "投稿失败"
    }

    private fun failure(message: String): SubmitResult = SubmitResult(false, null, message)

    /**
     * 构造 URL 时的占位主机名。
     *
     * [okhttp3.HttpUrl.Builder] 只对 query 参数做转义，而 [BASE_URL] 是占位常量、
     * 连域名都可能是假的，所以借一个固定占位域名把查询串拼好，再换回真实 base。
     * 这样 `&`、`=`、`%` 这些查询串里的结构字符在 id 里一律被转义掉——
     * 否则 `id=x&versionCode=999` 这种 id 会多注入一个 versionCode 参数，
     * 把一个未收录（NEW）的模块查成已收录（RECORDED）。
     */
    private const val HOST = "placeholder.invalid"

    /**
     * `/check` 的完整 URL。
     *
     * 参数走 [okhttp3.HttpUrl.Builder.addQueryParameter]，由它负责转义：
     * `&`、`=`、`%`、`#` 在 id 里一律变成 `%26` 这类转义序列，拼不出第二个参数。
     * 公开出来是为了让单测能直接盯住拼出来的串——转义写错是静默的，
     * 看代码很难发现，但一个 `id=x&versionCode=999999` 就能把 NEW 查成 RECORDED。
     */
    fun checkUrl(id: String, versionCode: Int, uid: String = ""): String {
        val builder = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(HOST)
            .addPathSegment("check")
            .addQueryParameter("id", id)
            .addQueryParameter("versionCode", versionCode.toString())
        // uid 与 id 走**同一个**转义器：它是用户手输/从签名里读来的自由文本，
        // 直接拼进查询串就能再注入一个参数，把「不是你的」查成「是你的」。
        // 空 uid 不下发这个键（服务端把空串当没给，但别让请求看起来像"带了"）。
        val who = uid.trim()
        if (who.isNotEmpty()) builder.addQueryParameter("uid", who)
        return builder.build()
            .toString()
            .replaceFirst("https://$HOST", BASE_URL.trimEnd('/'))
    }

    /**
     * id 白名单校验：**在**转义之前先挡一道。
     *
     * 光转义不够：转义只保证"参数不会被拆开"，但把 `a&versionCode=1` 这种 id 原样发给服务端，
     * 服务端拿它去比对索引、去建 `pending/<id>/` 路径，仍然是拿一个不该存在的 id 在做事。
     * 形状不合规的 id 一律按"查不出来"（[CheckStatus.UNKNOWN]）处理——
     * UNKNOWN 的语义就是"没问出来"，静默，绝不会被当成 NEW 去弹分享提示。
     */
    fun isValidId(id: String): Boolean =
        id.length in 1..MAX_ID_LENGTH && ID_PATTERN.matches(id)

    /**
     * 合法酷安 UID 的形状：4~12 位纯数字。
     *
     * 与 Worker 的 `COOLAPK_UID_RE`、网页端 `api.js` 的 `UID_RE` 是同一条约定 ——
     * 三处必须一致，否则会出现"App 认为合法、服务端 400 拒掉"的死角。
     * 形状不对时**不打网络请求**：那只会白耗一次配额，还必然得到一个查不到的结果。
     */
    fun isValidUid(uid: String): Boolean = UID_RE.matches(uid.trim())

    /**
     * `/user` 的完整 URL。
     *
     * 与 [checkUrl] 同一套写法（[okhttp3.HttpUrl.Builder] 负责转义）。UID 已经过
     * [isValidUid] 的纯数字白名单，这里再转一次只是为了让"拼 URL"这件事只有一个出口。
     */
    fun userUrl(uid: String): String =
        okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(HOST)
            .addPathSegment("user")
            .addQueryParameter("uid", uid)
            .build()
            .toString()
            .replaceFirst("https://$HOST", BASE_URL.trimEnd('/'))

    /**
     * 保留给调用方自检：地址配好没有。
     *
     * 判据只能是"还带着没替换的标记"，不能是"域名长什么样"——地址先是部署在
     * `*.workers.dev`、后来换成 `*.pages.dev`，两次都是真实地址；把域名形状当占位标志的话，
     * 真实地址会被判成占位，而 REPLACE-ME 这种假地址倒会被判成配好了。
     */
    fun isPlaceholder(): Boolean =
        BASE_URL.isBlank() ||
            BASE_URL.contains("REPLACE-ME") ||
            BASE_URL.toHttpUrlOrNull()?.host.isNullOrBlank()
}
