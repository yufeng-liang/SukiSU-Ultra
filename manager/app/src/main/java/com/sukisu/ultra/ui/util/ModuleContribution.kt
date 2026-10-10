package com.sukisu.ultra.ui.util

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
import java.util.Locale

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
     * 投稿服务的地址。占位常量：Worker 部署好之前这里没有真实后端。
     *
     * 占位域名解析不了，[checkRecorded] 会走"网络失败"分支返回 [CheckStatus.UNKNOWN]，
     * 也就是静默——换成真地址之前，投稿入口不会打扰任何用户。
     */
    const val BASE_URL: String = "https://sukisu-module-submit.lyfsearch.workers.dev"

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
     * 提交一个模块 zip。
     *
     * 任何失败都返回 `ok=false` 且带人类可读的 [SubmitResult.message]，不抛异常。
     *
     * [zip] 会被流式上传（不整体读进内存）；[meta] 为 null 时退化为"读不出模块信息"，
     * 直接失败，不让一条残缺条目进人工审核队列。
     */
    suspend fun submitModule(
        zip: File,
        meta: ModulePropInfo?,
        source: String,
        uploader: String,
    ): SubmitResult {
        val resolved = meta ?: return failure("读不出模块信息（module.prop 缺失或 id/name 为空）")
        val safeSource = source.trim()
        val safeUploader = uploader.trim()
        if (safeSource.isEmpty()) return failure("请填写出处链接或来源说明")
        if (safeUploader.isEmpty()) return failure("请填写投稿者署名")

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
            .addFormDataPart("uploader", safeUploader)
            .addFormDataPart("sha256", sha256)
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
     * 保留给调用方自检：地址配好没有。
     *
     * 判据只能是"还带着没替换的标记"，不能是"域名长什么样"——服务就部署在
     * `*.workers.dev` 上，把 `!endsWith(".workers.dev")` 当占位标志的话，
     * 真实地址反而会被判成占位、REPLACE-ME 这种假地址倒会被判成配好了。
     */
    fun isPlaceholder(): Boolean =
        BASE_URL.isBlank() ||
            BASE_URL.contains("REPLACE-ME") ||
            BASE_URL.toHttpUrlOrNull()?.host.isNullOrBlank()
}
