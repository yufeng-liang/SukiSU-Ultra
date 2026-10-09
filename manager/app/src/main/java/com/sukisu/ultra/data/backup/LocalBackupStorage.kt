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

    override val id: String = "local"

    /**
     * 文件名先压成单层：这个字符串会被拼进以 root 身份执行的 shell 命令，
     * 既不能让 `..` 逃出 [rootDir]，也不能带引号。
     */
    private fun target(relativePath: String) = "$rootDir/${ArchiveNaming.safeFileName(relativePath)}"

    override suspend fun test(): Result<Unit> = runCatching {
        staging.cleanupStale()
        check(rootFiles.mkdirs(rootDir)) { "backup directory is not writable: $rootDir" }
    }

    override suspend fun put(relativePath: String, size: Long, open: () -> InputStream): Result<Unit> =
        runCatching {
            staging.cleanupStale()
            check(rootFiles.mkdirs(rootDir)) { "backup directory is not writable: $rootDir" }
            val staged = staging.file(relativePath)
            try {
                open().use { input -> staged.outputStream().use { staging.copyCancellable(input, it) } }
                check(rootFiles.copyTo(staged, target(relativePath))) { "cannot write ${target(relativePath)}" }
            } finally {
                staged.delete()
            }
        }

    override suspend fun get(relativePath: String): Result<InputStream> = runCatching {
        staging.cleanupStale()
        val staged = staging.newFile("stage-", ".tmp")
        check(rootFiles.copyFrom(target(relativePath), staged)) { "cannot read ${target(relativePath)}" }
        // 关流即删：只读路径（列表 → 恢复/导出/读 meta）不该在 cache 里叠出整份归档副本。
        staging.openAndDelete(staged)
    }

    override suspend fun delete(relativePath: String): Result<Unit> = runCatching {
        check(rootFiles.delete(target(relativePath))) { "cannot delete ${target(relativePath)}" }
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
}
