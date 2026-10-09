package com.sukisu.ultra.data.backup

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WebDAV 后端（Basic Auth）。
 *
 * 列表不依赖 PROPFIND——很多服务端（尤其 Alist 反代的）depth 与属性返回不一致，
 * 备份列表统一读 index.json。这里只需要 PUT / GET / DELETE / MKCOL。
 *
 * OkHttp 默认允许任意 method 字符串；HttpURLConnection 会对 MKCOL 抛 ProtocolException，
 * 所以这个后端只能建在 OkHttp 上（仓库里已有依赖）。
 *
 * 中转文件统一放进 [staging]，PUT/GET 的请求走 [await]，协程取消时能中断 socket。
 */
class WebDavBackupStorage(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    private val staging: StagingArea = StagingArea(
        File(System.getProperty("java.io.tmpdir") ?: ".", "backup-staging"),
    ),
) : BackupStorage {

    override val id: String = ID

    private val auth = Credentials.basic(username, password)

    private fun url(relativePath: String) = WebDavPaths.joinBase(baseUrl, relativePath)

    private fun request(relativePath: String): Request.Builder =
        Request.Builder().url(url(relativePath)).header("Authorization", auth)

    /** 根集合 MKCOL 一次即可同时验证可达、凭据与可写。 */
    override suspend fun test(): Result<Unit> = runCatching {
        ensureCollections("")
    }

    override suspend fun put(relativePath: String, size: Long, open: () -> InputStream): Result<Unit> = runCatching {
        ensureCollections(relativePath)
        staging.cleanupStale()
        val staged = staging.file(relativePath)
        try {
            open().use { input -> staged.outputStream().use { staging.copyCancellable(input, it) } }
            val body = staged.asRequestBody(OCTET_STREAM)
            client.newCall(request(relativePath).put(body).build()).await().use { response ->
                checkSuccess(response, HttpOperation.UPLOAD, relativePath)
            }
        } finally {
            staged.delete()
        }
    }

    override suspend fun get(relativePath: String): Result<InputStream> = runCatching {
        staging.cleanupStale()
        val staged = staging.newFile("stage-", ".tmp")
        try {
            client.newCall(request(relativePath).get().build()).await().use { response ->
                checkSuccess(response, HttpOperation.DOWNLOAD, relativePath)
                response.body.byteStream().use { input -> staged.outputStream().use { staging.copyCancellable(input, it) } }
            }
            // 关流即删：只读路径不该在 cache 里留下整份归档副本。
            staging.openAndDelete(staged)
        } catch (e: Throwable) {
            // 传输中断或被取消时也要把半截副本清掉，不能留给 6 小时后的 cleanupStale。
            staged.delete()
            throw e
        }
    }

    override suspend fun delete(relativePath: String): Result<Unit> = runCatching {
        client.newCall(request(relativePath).delete().build()).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw BackupAuthException(response.code, "DELETE $relativePath -> HTTP ${response.code}")
            }
            if (!response.isSuccessful && response.code != 404) {
                throw BackupReasonException(BackupReason.HttpFailed(HttpOperation.DELETE, relativePath, response.code))
            }
        }
    }

    /** 索引/边车不存在（404）返回空文本，让引擎把"没有"与"读不到"分开。 */
    override suspend fun readText(relativePath: String): Result<String> = runCatching {
        staging.cleanupStale()
        client.newCall(request(relativePath).get().build()).await().use { response ->
            if (response.code == 404) return@runCatching ""
            checkSuccess(response, HttpOperation.DOWNLOAD, relativePath)
            response.body.string()
        }
    }

    override suspend fun writeText(relativePath: String, text: String): Result<Unit> {
        val bytes = text.toByteArray()
        return put(relativePath, bytes.size.toLong()) { bytes.inputStream() }
    }

    /**
     * 401 / 403 单独抛 [BackupAuthException]：绝大多数 WebDAV 服务商要求用应用密码，
     * 直接把 `MKCOL -> HTTP 401` 丢给用户，他不知道该改什么。
     */
    private fun checkSuccess(response: Response, operation: HttpOperation, path: String) {
        if (response.code == 401 || response.code == 403) {
            throw BackupAuthException(response.code, "$operation $path -> HTTP ${response.code}")
        }
        if (!response.isSuccessful) {
            throw BackupReasonException(BackupReason.HttpFailed(operation, path, response.code))
        }
    }

    /** OkHttp 的 execute() 是阻塞的，协程取消时把 Call 一起取消，中断 socket。 */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }
        })
    }

    /**
     * WebDAV 不会自动建父集合：先 MKCOL 根集合，再逐级 MKCOL [relativePath] 的祖先目录。
     * [relativePath] 以 `/` 结尾时整条路径都算目录，否则最后一段是文件名。
     * 已存在（405 / 301）视为成功。
     */
    private fun ensureCollections(relativePath: String) {
        ensureCollection("")
        val trimmed = relativePath.trim()
        val dirs = WebDavPaths.parentDirs(trimmed)
        val ancestors = if (trimmed.endsWith("/")) dirs else dirs.dropLast(1)
        ancestors.forEach { ensureCollection(it) }
    }

    private fun ensureCollection(relativeDir: String) {
        val body = ByteArray(0).toRequestBody(OCTET_STREAM)
        client.newCall(request(relativeDir).method("MKCOL", body).build()).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw BackupAuthException(response.code, "MKCOL $relativeDir -> HTTP ${response.code}")
            }
            if (!response.isSuccessful && response.code != 405 && response.code != 301) {
                throw BackupReasonException(
                    BackupReason.HttpFailed(HttpOperation.CREATE_DIRECTORY, relativeDir.ifBlank { "/" }, response.code),
                )
            }
        }
    }

    companion object {
        /** 后端 id。UI 靠它把失败区分成"本地"和"云端"，所以别在多处写字面量。 */
        const val ID = "webdav"

        private val OCTET_STREAM: MediaType = "application/octet-stream".toMediaType()
    }
}
