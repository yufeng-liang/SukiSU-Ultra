package com.sukisu.ultra.data.backup

import java.io.InputStream

/**
 * 本地后端，落点是 root 可写的共享目录（默认 /sdcard/Download/SukiSU-Backup）。
 *
 * 读写一律"先落 staging 临时文件 → root cp 到目标 → 删临时文件"，
 * 与 ui/screen/settings/tools/ToolsUtils.kt 现有形状一致，避免 SuFileOutputStream 走 shell 管道的吞吐问题。
 */
class LocalBackupStorage(
    private val rootDir: String,
    private val staging: StagingArea,
    private val rootFiles: RootFiles,
) : BackupStorage {

    override val id: String = ID

    /**
     * 文件名先压成单层：这个字符串会被拼进以 root 身份执行的 shell 命令，
     * 既不能让 `..` 逃出 [rootDir]，也不能带引号。
     */
    private fun target(relativePath: String) = "$rootDir/${ArchiveNaming.safeFileName(relativePath)}"

    override suspend fun test(): Result<Unit> = runCatchingCancellable {
        staging.cleanupStale()
        ensureRoot()
    }

    // 不报进度：拷到目标那一步是 `cp`，没有回调可用，字节数由引擎包在源流上数
    // （见 BackupStorage.reportsTransferProgress）。
    override suspend fun put(
        relativePath: String,
        size: Long,
        onProgress: (Long) -> Unit,
        open: () -> InputStream,
    ): Result<Unit> =
        runCatchingCancellable {
            staging.cleanupStale()
            ensureRoot()
            val staged = staging.file(relativePath)
            try {
                open().use { input -> staged.outputStream().use { staging.copyCancellable(input, it) } }
                val target = target(relativePath)
                // 先写到同目录的临时名再改名。`cp` 会先把目标内容截断，写到一半断掉（进程被杀、
                // 断电、空间写满）留下的是半个文件——而索引正是靠这个文件活着的：半截索引读不出来，
                // 引擎读不出来又拒绝回写，用户看到的就是"全部备份都没了"。改名是原子的，读者要么
                // 看到旧内容、要么看到新内容。
                val incoming = "$target$TEMP_SUFFIX"
                if (!rootFiles.copyTo(staged, incoming)) {
                    rootFiles.delete(incoming)
                    throw BackupReasonException(BackupReason.WriteFailed(target, null))
                }
                if (!rootFiles.rename(incoming, target)) {
                    rootFiles.delete(incoming)
                    throw BackupReasonException(BackupReason.WriteFailed(target, null))
                }
            } finally {
                staged.delete()
            }
        }

    override suspend fun get(relativePath: String): Result<InputStream> = runCatchingCancellable {
        staging.cleanupStale()
        val staged = staging.newFile("stage-", ".tmp")
        val target = target(relativePath)
        if (!rootFiles.copyFrom(target, staged)) {
            staged.delete()
            throw BackupReasonException(BackupReason.ReadFailed(target, null))
        }
        // 关流即删：只读路径（列表 → 恢复/导出/读 meta）不该在 cache 里叠出整份归档副本。
        staging.openAndDelete(staged)
    }

    override suspend fun delete(relativePath: String): Result<Unit> = runCatchingCancellable {
        val target = target(relativePath)
        if (!rootFiles.delete(target)) throw BackupReasonException(BackupReason.DeleteFailed(target, null))
    }

    /**
     * 索引/边车不存在时返回空文本，让引擎把"没有"与"读不到"分开。
     *
     * 这里必须按"先尝试读，读不到再确认是不是真的不存在"的顺序：反过来先问 exists() 的话，
     * 一次 exists() 的假阴性就会被引擎当成"空索引"，随后整体回写把已有 manifest 清掉。
     */
    override suspend fun readText(relativePath: String): Result<String> = runCatchingCancellable {
        val path = target(relativePath)
        // 读取有上限（见 [BackupLimits]）：索引会被"读全量 → 改 → 整体回写"，被换成一个大文件时
        // 必须当场报"读不出来"让引擎拒绝回写，而不是先把几十 MB 读进内存再 OOM——OOM 连拒绝都做不到。
        get(relativePath)
            .mapCatching { stream -> stream.use { it.readBoundedText(BackupLimits.MAX_TEXT_BYTES, path) } }
            .getOrElse { error ->
                if (rootFiles.exists(path)) throw error
                ""
            }
    }

    override suspend fun writeText(relativePath: String, text: String): Result<Unit> {
        val bytes = text.toByteArray()
        return put(relativePath, bytes.size.toLong()) { bytes.inputStream() }
    }

    /** 目录建不出来就没法读也没法写，先在这里把原因说清楚。 */
    private fun ensureRoot() {
        if (!rootFiles.mkdirs(rootDir)) throw BackupReasonException(BackupReason.DirectoryNotWritable(rootDir, null))
    }

    companion object {
        /** 后端 id。UI 靠它把失败区分成"本地"和"云端"，所以别在多处写字面量。 */
        const val ID = "local"

        /** 写入过程中用的后缀，见 [put]：改名成功后就不会有这个名字的文件留下。 */
        private const val TEMP_SUFFIX = ".tmp"
    }
}
