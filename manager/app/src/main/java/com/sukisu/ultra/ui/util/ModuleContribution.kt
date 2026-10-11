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
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Locale

/** nonce 的字符集：6 位小写字母数字，与 Worker 的 `PROOF_NONCE_RE`（`/^[a-z0-9]{6}$/`）同一形状。 */
private val NONCE_ALPHABET = ('a'..'z') + ('0'..'9')

/** 共用一个实例：`SecureRandom` 自带种子与锁，每次取字符都新建一个纯属浪费（且没有更安全）。 */
private val NONCE_RANDOM = SecureRandom()

/**
 * 承诺口令的前缀：`sha256(模块 id)` 的前 8 位小写十六进制。
 *
 * 与 worker/src/proof.ts、public/scripts/proof.js 是同一套算法的第三份实现——
 * 三处必须同值，服务端拿它比对用户写进酷安签名的口令。
 */
fun proofPrefixForId(id: String): String =
    id.toByteArray().sha256Hex().take(8)

/** 一次性随机段：6 位小写字母数字。只存这一段，口令前缀现算，杜绝与模块 id 脱钩。 */
fun makeNonce(): String =
    (1..6).map { NONCE_ALPHABET[NONCE_RANDOM.nextInt(NONCE_ALPHABET.size)] }.joinToString("")

/** 承诺文本版本。必须与 Worker 的 `PLEDGE_VERSIONS` 完全一致。 */
private const val PLEDGE_VERSION = "pledge-v1"

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
 * 投稿结果。
 *
 * [ok] 为 false 时 [message] 一定非空且是人类可读的中文原因，供界面直接显示；
 * [prUrl] 只在 [ok] 为 true 时有值（服务端成功但没回 PR 链接时也可能为 null）。
 */
data class SubmitResult(val ok: Boolean, val prUrl: String?, val message: String)

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
    /** 酷安返回了这个 UID 的昵称。唯一允许提交的状态。 */
    data class Verified(val nickname: String) : CoolapkUidLookup

    /** 酷安明确说没有这个用户。 */
    object NotFound : CoolapkUidLookup

    /** 后端没有核实能力（核实开关被关掉）。 */
    object SelfReported : CoolapkUidLookup

    /** 这次没核实成（酷安不可达 / 风控 / 超时）。 */
    object Unavailable : CoolapkUidLookup
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

    /** 8 秒。投稿是安装成功之后的额外一步，等太久会让用户以为卡住了。 */
    private const val TIMEOUT_MS = 8_000L

    /** 只认这几种取值，其余（含大小写以外的花活）一律 [CheckStatus.UNKNOWN]。 */
    private val STATUS_BY_NAME = mapOf(
        "recorded" to CheckStatus.RECORDED,
        "new" to CheckStatus.NEW,
        "version_update" to CheckStatus.VERSION_UPDATE,
        "unknown" to CheckStatus.UNKNOWN,
    )

    private val ZIP_MEDIA_TYPE = "application/zip".toMediaType()

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
    suspend fun checkRecorded(id: String, versionCode: Int): CheckStatus {
        if (!isValidId(id)) return CheckStatus.UNKNOWN
        val url = checkUrl(id, versionCode)
        return withContext(Dispatchers.IO) {
            val call = runCatching { client().newCall(Request.Builder().url(url).get().build()) }
                .getOrNull() ?: return@withContext CheckStatus.UNKNOWN
            // 只吞自己那次超时；调用方发起的取消（界面关了、协程取消了）必须照常往外传，
            // 否则已经没人等结果了，这里还会继续跑完整个请求。
            try {
                withTimeout(TIMEOUT_MS) { execute(call) }.use { response -> readStatus(response) }
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) CheckStatus.UNKNOWN else throw e
            } catch (e: Throwable) {
                CheckStatus.UNKNOWN
            }
        }
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
                withTimeout(TIMEOUT_MS) { execute(call) }.use { response -> readUidLookup(response) }
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
     * 直接失败，不让一条残缺条目进人工审核队列。
     *
     * 身份两个字段一起发：`uploader` 是酷安反查出来的昵称（索引里公开展示），
     * `coolapkUid` 是投稿人自己填的 UID（审核与追责用）。**服务端把 `coolapkUid`
     * 定为必收字段**，缺失或不是 4~12 位数字一律 400 —— 这一侧先挡一道，
     * 免得用户白等一次上传。
     */
    suspend fun submitModule(
        zip: File,
        meta: ModulePropInfo?,
        source: String,
        uid: String,
        nickname: String,
        nonce: String,
    ): SubmitResult {
        val resolved = meta ?: return failure("读不出模块信息（module.prop 缺失或 id/name 为空）")
        val safeSource = source.trim()
        val safeUid = uid.trim()
        val safeNickname = nickname.trim()
        if (safeSource.isEmpty()) return failure("请填写出处链接或来源说明")
        if (!isValidUid(safeUid)) return failure("酷安 UID 必须是 4~12 位数字")
        if (safeNickname.isEmpty()) return failure("酷安 UID 未核实，拿不到署名")

        val sha256 = ModuleProp.zipSha256(zip)
            ?: return failure("读取模块文件失败，无法计算校验值")
        if (!zip.isFile) return failure("模块文件不存在或不是普通文件")

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
                withTimeout(TIMEOUT_MS) { execute(call) }.use { response -> readSubmitResult(response) }
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
     * 阻塞式执行；不能是 `suspend`，否则 [withTimeout] 打断不掉已经开始读响应的请求。
     *
     * 两层超时：OkHttp 的 [okhttp3.Call.timeout] 负责掐断 socket（连接上了但服务端不回话，
     * 只有协程超时的话，那个协程会被挂住直到 socket 自己超时，可能远超 8 秒）；
     * [withTimeout] 负责把 OkHttp 之外的等待（DNS、排队）也算进来。
     */
    private fun execute(call: okhttp3.Call): Response {
        runCatching { call.timeout().timeout(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
        return call.execute()
    }

    private fun readStatus(response: Response): CheckStatus {
        if (!response.isSuccessful) return CheckStatus.UNKNOWN
        val body = runCatching { response.body.string() }.getOrNull() ?: return CheckStatus.UNKNOWN
        val name = runCatching { JSONObject(body).optString("status") }
            .getOrNull()
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?: return CheckStatus.UNKNOWN
        return STATUS_BY_NAME[name] ?: CheckStatus.UNKNOWN
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
                ?.let { CoolapkUidLookup.Verified(it) }
                ?: CoolapkUidLookup.Unavailable

            "not_found" -> CoolapkUidLookup.NotFound
            "self_reported" -> CoolapkUidLookup.SelfReported
            else -> CoolapkUidLookup.Unavailable
        }
    }

    private fun readSubmitResult(response: Response): SubmitResult {
        if (!response.isSuccessful) {
            return SubmitResult(false, null, "投稿失败（服务端返回 HTTP ${response.code}）")
        }
        val body = runCatching { response.body.string() }.getOrNull()
            ?: return SubmitResult(false, null, "投稿失败（读不到服务端响应）")
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return SubmitResult(false, null, "投稿失败（服务端响应不是合法 JSON）")
        if (!json.optBoolean("ok", false)) {
            val reason = json.optString("error").takeIf { it.isNotBlank() }
                ?: "服务端拒绝了这次投稿"
            return SubmitResult(false, null, reason)
        }
        return SubmitResult(true, json.optString("pr").takeIf { it.isNotBlank() }, "投稿已提交")
    }

    /** 把失败原因说成人话，而不是把一个 IOException 的类名甩给用户。 */
    private fun describe(error: Throwable): String = when (error) {
        is kotlinx.coroutines.TimeoutCancellationException -> "投稿超时（8 秒），请稍后再试"
        is SocketTimeoutException -> "投稿超时（8 秒），请稍后再试"
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
    fun checkUrl(id: String, versionCode: Int): String =
        okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(HOST)
            .addPathSegment("check")
            .addQueryParameter("id", id)
            .addQueryParameter("versionCode", versionCode.toString())
            .build()
            .toString()
            .replaceFirst("https://$HOST", BASE_URL.trimEnd('/'))

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
