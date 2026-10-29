package com.sukisu.ultra.ui.screen.upload

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.sukisu.ultra.data.backup.StagingArea
import com.sukisu.ultra.ksuApp
import com.sukisu.ultra.ui.util.ModuleProp
import com.sukisu.ultra.ui.util.ModulePropInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 把用户从 SAF 挑中的 zip 落到 staging，并读出表单要用的全部信息。
 *
 * 与 [com.sukisu.ultra.ui.screen.module.ModuleContributionEntry] 共用同一个
 * `cacheDir/contribution-staging` 目录（旧入口留下的实现照旧可用），
 * 但这里不做「把已安装模块重新打包」那件事：新流程上传的是**用户自己选的原始 zip**，
 * 打包出来的字节与作者发布的包不同，口令绑的 sha 也会跟着不同。
 *
 * 而且那条路现在已经关了：任何**没有安装器**的包都在这里就被拒（[Result.NoInstaller]），
 * 从已安装目录打出来的包必然落在这一类里。
 */
object ModuleUploadStaging {

    /** staging 结果：成功时带着已经算好 sha 的条目，失败时只说明失败在哪一步。 */
    sealed interface Result {
        data class Ok(val zip: UploadZip) : Result

        /** 读不出 module.prop（不是 zip / 没有根目录 module.prop / id 或 name 为空）。 */
        data object UnreadableModule : Result

        /**
         * 包里没有安装器（见 [ModuleProp.hasInstallerEntry]）。
         *
         * 最典型的来源是「用已安装的模块现打一份」：ksud 装完会删掉 `customize.sh`、
         * 也从不解压 `META-INF`，所以那棵目录树里必然没有安装器。这种包发给别人，
         * ksud 找不到脚本会静默按默认方式解压，脚本本该做的选架构、设权限、环境检查
         * 全部不发生 —— 所以这条路上直接拒收，不让它进表单。
         */
        data object NoInstaller : Result

        /** 复制、读盘或算哈希失败（uri 失效、空间不足等）。 */
        data object Failed : Result

        /** 超过服务端自报的单枚上限；[sizeBytes] 是读到的实际/声明大小。 */
        data class TooBig(val sizeBytes: Long, val maxBytes: Long) : Result
    }

    private fun staging(): StagingArea = StagingArea(File(ksuApp.cacheDir, "contribution-staging"))

    /**
     * 复制并解析一个 uri。
     *
     * 整个动作在 IO 上跑：zip 最大 8MB，在主线程读一遍就是一次掉帧；sha256 还要再读一遍。
     * 复制出来的文件不删——它正是后面 `submitBatch` 要上传的那一份，删了提交就没东西可传。
     *
     * [maxBytes] 是服务端自报的单枚上限。先读 SAF 声明的 SIZE，再在拷贝循环里边读边量：
     * 声明的 SIZE 可能缺失或不准，只信声明就会把一个 2GB 的包整个写进 cache 再拒。
     */
    suspend fun stage(context: Context, uri: Uri, maxBytes: Long?): Result = withContext(Dispatchers.IO) {
        copyIntoStaging(
            declared = declaredSize(context, uri),
            maxBytes = maxBytes,
            open = { context.contentResolver.openInputStream(uri) },
        )
    }

    /**
     * 从本地文件 stage：用于「用安装时留存的那份原始包」。
     *
     * 与 uri 那条路径共用同一段落盘与解析逻辑，区别只在数据源——留存包本来就在
     * app 私有目录里，不需要 SAF，也不需要再问 ContentResolver 要大小。
     */
    suspend fun stageFile(file: File, maxBytes: Long?): Result = withContext(Dispatchers.IO) {
        copyIntoStaging(
            declared = file.length().takeIf { it > 0L },
            maxBytes = maxBytes,
            open = { file.inputStream() },
        )
    }

    /**
     * 复制进 staging 并解析。
     *
     * [declared] 是数据源自报的大小（可空）；[open] 负责开流，返回 null 视为 uri 失效。
     * 复制出来的文件不删——它正是后面 `submitBatch` 要上传的那一份。
     */
    private suspend fun copyIntoStaging(
        declared: Long?,
        maxBytes: Long?,
        open: () -> java.io.InputStream?,
    ): Result {
        val limit = maxBytes?.takeIf { it > 0 }
        if (limit != null && declared != null && declared > limit) {
            return Result.TooBig(declared, limit)
        }

        var copied = 0L
        var tooBig = false
        var target: File? = null
        val staged = try {
            val area = staging()
            area.cleanupStale()
            val file = area.newFile("upload-", ".zip")
            target = file
            val input = open()
            if (input == null) {
                // uri 失效：空副本也别留。
                file.delete()
                return Result.Failed
            }
            input.use { source ->
                file.outputStream().use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        // 离开页面时 viewModelScope 被取消：这里停手并把半截副本删掉，
                        // 别把一份没人认领的文件留在 cache 里。
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read <= 0) break
                        copied += read
                        if (limit != null && copied > limit) {
                            tooBig = true
                            break
                        }
                        sink.write(buffer, 0, read)
                    }
                }
            }
            file
        } catch (e: CancellationException) {
            target?.delete()
            throw e
        } catch (e: Throwable) {
            target?.delete()
            null
        } ?: return Result.Failed

        if (tooBig) {
            staged.delete()
            return Result.TooBig(copied, limit ?: copied)
        }

        val meta = ModuleProp.parseModuleProp(staged)
        if (meta == null) {
            staged.delete()
            return Result.UnreadableModule
        }
        // 没有安装器的包一律不收：索引里存的就是别人要装的那一份，缺脚本等于给下载者
        // 一个「装得上但装不对」的包。判据与理由见 [ModuleProp.hasInstallerEntry]。
        if (!ModuleProp.hasInstallerEntry(staged)) {
            staged.delete()
            return Result.NoInstaller
        }
        val sha = ModuleProp.zipSha256(staged)
        if (sha.isNullOrBlank()) {
            staged.delete()
            return Result.Failed
        }
        return Result.Ok(
            UploadZip(
                path = staged.absolutePath,
                fileName = "${safeId(meta.id)}-${meta.versionCode}.zip",
                id = meta.id,
                name = meta.name,
                version = meta.version,
                versionCode = meta.versionCode,
                author = meta.author,
                description = meta.description,
                sha256 = sha,
                sizeBytes = staged.length(),
            )
        )
    }

    /** SAF 声明的字节数；拿不到就返回 null（交给拷贝循环兜底）。 */
    private fun declaredSize(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
    }.getOrNull()

    /** 文件名里出现 `/` 会让重命名静默失败，压平成单层。 */
    private fun safeId(id: String): String =
        id.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "module" }

    /** 给表单用的只读信息投影（复用同一份 [ModulePropInfo] 字段）。 */
    fun metaOf(zip: UploadZip): ModulePropInfo = ModulePropInfo(
        id = zip.id,
        name = zip.name,
        version = zip.version,
        versionCode = zip.versionCode,
        author = zip.author,
        description = zip.description,
    )
}
