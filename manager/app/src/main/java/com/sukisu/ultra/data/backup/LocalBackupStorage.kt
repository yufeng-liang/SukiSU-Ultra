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

    override suspend fun test(): Result<Unit> = runCatching {
        staging.cleanupStale()
        ensureRoot()
    }

    override suspend fun put(relativePath: String, size: Long, open: () -> InputStream): Result<Unit> =
        runCatching {
            staging.cleanupStale()
            ensureRoot()
            val staged = staging.file(relativePath)
            try {
                open().use { input -> staged.outputStream().use { staging.copyCancellable(input, it) } }
                val target = target(relativePath)
                if (!rootFiles.copyTo(staged, target)) {
                    throw BackupReasonException(BackupReason.WriteFailed(target, null))
                }
            } finally {
                staged.delete()
            }
        }

    override suspend fun get(relativePath: String): Result<InputStream> = runCatching {
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

    override suspend fun delete(relativePath: String): Result<Unit> = runCatching {
        val target = target(relativePath)
        if (!rootFiles.delete(target)) throw BackupReasonException(BackupReason.DeleteFailed(target, null))
    }

    /**
     * 索引/边车不存在时返回空文本，让引擎把"没有"与"读不到"分开。
     *
     * 这里必须按"先尝试读，读不到再确认是不是真的不存在"的顺序：反过来先问 exists() 的话，
     * 一次 exists() 的假阴性就会被引擎当成"空索引"，随后整体回写把已有 manifest 清掉。
     */
    override suspend fun readText(relativePath: String): Result<String> = runCatching {
        get(relativePath)
            .mapCatching { it.use { stream -> stream.readBytes().decodeToString() } }
            .getOrElse { error ->
                if (rootFiles.exists(target(relativePath))) throw error
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
    }
}
