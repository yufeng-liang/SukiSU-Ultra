package com.sukisu.ultra.data.backup

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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

    /**
     * 上传进度由请求体自己报，理由见 [BackupStorage.reportsTransferProgress]。
     */
    override val reportsTransferProgress: Boolean = true

    // 必须显式给 UTF-8：单参重载按 ISO-8859-1 编码，非 ASCII 的用户名/密码会被转成乱码，
    // 服务端算出的凭据和用户输入的就不一致，表现成"密码明明对却一直 401"。
    private val auth = Credentials.basic(username, password, Charsets.UTF_8)

    /** 服务端是否支持 MOVE；`null` 表示还没试过，`false` 表示试过且不支持（退回直接覆盖）。 */
    @Volatile
    private var supportsMove: Boolean? = null

    private fun url(relativePath: String) = WebDavPaths.joinBase(baseUrl, relativePath)

    private fun request(relativePath: String): Request.Builder =
        Request.Builder().url(url(relativePath)).header("Authorization", auth)

    /** 根集合 MKCOL 一次即可同时验证可达、凭据与可写。 */
    override suspend fun test(): Result<Unit> = runCatchingCancellable {
        ensureCollections("")
    }

    override suspend fun put(
        relativePath: String,
        size: Long,
        onProgress: (Long) -> Unit,
        open: () -> InputStream,
    ): Result<Unit> = runCatchingCancellable {
        ensureCollections(relativePath)
        staging.cleanupStale()
        val staged = staging.file(relativePath)
        try {
            open().use { input -> staged.outputStream().use { staging.copyCancellable(input, it) } }
            upload(staged, relativePath, onProgress)
        } finally {
            staged.delete()
        }
    }

    /**
     * 先 PUT 到同目录的 `xxx.part`，再 MOVE 到目标名：中途断流只会留下临时对象，目标名要么还是
     * 上一份完整内容、要么已经是这一份。索引记录着**全部**备份，半截索引等于整个功能读不出东西，
     * 靠的就是这个性质。
     *
     * MOVE 是 WebDAV 核心方法，但确实有服务端禁用它（405/501）。碰上就退回直接覆盖：[target]
     * 的旧内容仍会被下一次完整上传替换，"能写进去但不原子"好过"根本写不进去"。探测一次即可，
     * 之后的文件不再走临时对象。
     */
    private suspend fun upload(staged: File, relativePath: String, onProgress: (Long) -> Unit) {
        if (supportsMove == false) {
            putDirect(staged, relativePath, onProgress)
            return
        }
        val temporaryPath = "$relativePath$PART_SUFFIX"
        putDirect(staged, temporaryPath, onProgress)
        if (moveOnto(temporaryPath, relativePath)) {
            supportsMove = true
            return
        }
        supportsMove = false
        // 服务端不认 MOVE，暂存对象没人会读，清掉再覆盖目标名。清理失败只当没清掉（下面照常覆盖），
        // 但取消不能在这里被咽掉——那会让已经取消的上传继续走覆盖那一步。
        runCatchingCancellable { client.newCall(request(temporaryPath).delete().build()).await().use { } }
        putDirect(staged, relativePath, {})
    }

    /** MOVE 是否被接受；405/501 表示服务端不支持，其它失败码照常抛出（写入没成功就得让上层知道）。 */
    private suspend fun moveOnto(temporaryPath: String, targetPath: String): Boolean =
        client.newCall(
            request(temporaryPath)
                .method("MOVE", null)
                .header("Destination", url(targetPath))
                .header("Overwrite", "T")
                .build(),
        ).await().use { response ->
            when (response.code) {
                405, 501 -> false
                else -> {
                    checkSuccess(response, HttpOperation.UPLOAD, targetPath)
                    true
                }
            }
        }

    private suspend fun putDirect(staged: File, relativePath: String, onProgress: (Long) -> Unit) {
        val body = CountingRequestBody(staged, OCTET_STREAM, onProgress)
        client.newCall(request(relativePath).put(body).build()).await().use { response ->
            checkSuccess(response, HttpOperation.UPLOAD, relativePath)
        }
    }

    override suspend fun get(relativePath: String): Result<InputStream> = runCatchingCancellable {
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

    override suspend fun delete(relativePath: String): Result<Unit> = runCatchingCancellable {
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
    override suspend fun readText(relativePath: String): Result<String> = runCatchingCancellable {
        staging.cleanupStale()
        client.newCall(request(relativePath).get().build()).await().use { response ->
            if (response.code == 404) return@runCatchingCancellable ""
            checkSuccess(response, HttpOperation.DOWNLOAD, relativePath)
            // 读取有上限（见 [BackupLimits]）：索引会被"读全量 → 改 → 整体回写"，远端被塞进一个大文件时
            // 必须当场报"读不出来"让引擎拒绝回写；先整体读进内存再 OOM 的话，连拒绝回写都做不到。
            response.body.byteStream().use {
                it.readBoundedText(BackupLimits.MAX_TEXT_BYTES, url(relativePath))
            }
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
     * "集合已经在了"= 405 或指向自己的重定向（见 [isCollectionAlreadyThere]），其余算失败。
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
            if (!response.isSuccessful && !isCollectionAlreadyThere(response, relativeDir)) {
                throw BackupReasonException(
                    BackupReason.HttpFailed(HttpOperation.CREATE_DIRECTORY, relativeDir.ifBlank { "/" }, response.code),
                )
            }
        }
    }

    /**
     * 这个响应能不能读成"集合已经存在"。
     *
     * 405 是 RFC 4918 §9.3.1 给"目标已有资源"的答复，直接算存在。301/302 不能无条件算：老写法
     * 把任何 301 都当已存在，于是"被重定向到登录页或别的站点"这种真失败也被记成成功，紧接着的
     * PUT 全部失败，报出来的原因还完全指错方向。只有 Location 解析出来仍是同一个集合
     * （同 scheme/host/port，路径除尾斜杠外相同）时才认——nginx 给已存在目录的 MKCOL 回 301
     * 补尾斜杠就是这一种，也仅此一种该被当成成功。
     */
    private fun isCollectionAlreadyThere(response: Response, relativeDir: String): Boolean {
        if (response.code == 405) return true
        if (response.code != 301 && response.code != 302) return false
        val self = url(relativeDir).toHttpUrlOrNull() ?: return false
        val target = response.header("Location")?.let { self.resolve(it) } ?: return false
        return target.scheme == self.scheme &&
            target.host == self.host &&
            target.port == self.port &&
            target.encodedPath.trimEnd('/') == self.encodedPath.trimEnd('/')
    }

    companion object {
        /** 后端 id。UI 靠它把失败区分成"本地"和"云端"，所以别在多处写字面量。 */
        const val ID = "webdav"

        /** 直传临时对象的后缀，见 [WebDavBackupStorage.upload]。 */
        private const val PART_SUFFIX = ".part"

        private val OCTET_STREAM: MediaType = "application/octet-stream".toMediaType()
    }
}
