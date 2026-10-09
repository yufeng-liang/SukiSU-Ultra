package com.sukisu.ultra.data.backup

import org.json.JSONObject
import java.io.InputStream
import java.time.Instant

fun interface BootRestorer {
    /** 调 `ksud boot-restore -f`：ksud 会自己按当前 boot 的 sha1 在备份目录里找匹配文件。 */
    suspend fun restoreStockImage(): Result<Unit>
}

/**
 * boot 原厂镜像的备份源。
 *
 * 不重新抓取 boot 分区——ksud 在 patch 时已经把原厂镜像写进 /data/adb/ksu/ksu_backup_<sha1>，
 * 这里只是把已有备份列出来、导出、需要时放回去。
 *
 * 校验必须自己做，而且要做足三层：
 * 1. ksud 只按当前 patched boot 的 cpio 里记录的 sha1 拼文件名、检查文件存在，读到什么就刷什么，
 *    不做任何内容校验；找不到匹配文件时它只打印一行 Warning，转而 `rebuild_without_ksu` 并**正常退出 0**——
 *    也就是"报成功但什么都没恢复"。
 * 2. 只比对边车 meta 里的 sha1 是自证的：meta 与索引都躺在可写的 /sdcard 上。
 * 3. 所以除了 meta 与 entryId 一致，还要重算内容的 sha1 把它和身份绑死，并要求这个身份
 *    真的在本机现有原厂镜像里存在。
 */
class BootBackupSource(
    private val rootFiles: RootFiles,
    private val restorer: BootRestorer,
    private val clock: () -> Instant,
    private val staging: StagingArea,
    private val backupDir: String = "/data/adb/ksu",
) : BackupSource {

    override val kind: BackupKind = BackupKind.BOOT

    private fun stockImageFiles(): List<RootFileEntry> =
        rootFiles.list(backupDir).filter { it.path.substringAfterLast('/').startsWith("ksu_backup_") }

    private fun sha1Of(entry: RootFileEntry): String = entry.path.substringAfterLast('/').removePrefix("ksu_backup_")

    override suspend fun export(): Result<ExportOutcome> = runCatching {
        staging.cleanupStale()
        val timestamp = ArchiveNaming.timestamp(clock())
        val artifacts = mutableListOf<BackupArtifact>()
        val failures = mutableListOf<BackupFailure>()
        stockImageFiles().forEach { file ->
            val sha1 = sha1Of(file)
            val staged = staging.newFile("boot-export-", ".img")
            if (!rootFiles.copyFrom(file.path, staged)) {
                staged.delete()
                failures += BackupFailure("export", file.path, "source", "cannot stage ${file.path}")
                return@forEach
            }
            val sha256 = staged.inputStream().use { it.sha256Hex() }
            val fileName = ArchiveNaming.bootArchiveName(sha1, timestamp)
            artifacts += BackupArtifact(
                kind = kind,
                entryId = sha1,
                fileName = fileName,
                metaFileName = ArchiveNaming.metaFileNameFor(fileName),
                sizeBytes = staged.length(),
                sha256 = sha256,
                metaJson = renderMeta(sha1, staged.length(), sha256),
                openContent = { staged.inputStream() },
                cleanup = { staged.delete() },
            )
        }
        ExportOutcome(artifacts, failures)
    }

    /**
     * boot 的"全部"通常就 1–2 张原厂镜像（ksud 只在 patch 时留当前这一张），
     * 所以直接复用 [export] 再挑一项。没被选中的那几张必须在这里清掉——
     * 它们是为 [export] 的其它 artifact 落的副本，而 artifact 一旦丢弃就没人再调用它的 cleanup。
     */
    override suspend fun exportOne(entryId: String): Result<BackupArtifact?> = runCatching {
        val artifacts = export().getOrThrow().artifacts
        val selected = artifacts.firstOrNull { it.entryId == entryId }
        artifacts.filter { it !== selected }.forEach { it.cleanup?.invoke() }
        selected
    }

    /**
     * 导入的 boot 归档要自己把身份算出来：ksud 只认 `ksu_backup_<原厂镜像 sha1>`，
     * 而 sha1 只存在于内容里，文件名里的 12 位前缀不够用。
     */
    override suspend fun metaForImport(open: () -> InputStream, size: Long): ImportMeta? = runCatching {
        val sha256 = open().use { it.sha256Hex() }
        val sha1 = open().use { it.sha1Hex() }
        ImportMeta(sha1, renderMeta(sha1, size, sha256))
    }.getOrNull()

    override suspend fun restore(
        entry: BackupEntry,
        metaJson: String?,
        content: InputStream,
    ): Result<RestoreOutcome> = runCatching {
        val meta = metaJson?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return@runCatching RestoreOutcome(
                false,
                "boot archive ${entry.fileName} has no sidecar meta; refusing to flash",
            )
        val sha1 = meta.optString("sha1")
        val expectedSha256 = meta.optString("sha256")
        if (sha1.isBlank() || sha1 != entry.entryId || !SHA1_HEX.matches(entry.entryId)) {
            return@runCatching RestoreOutcome(
                false,
                "boot archive belongs to a different stock image (sha1 $sha1 != ${entry.entryId}); refusing to flash",
            )
        }
        if (expectedSha256.isBlank()) {
            return@runCatching RestoreOutcome(false, "boot archive has no checksum; refusing to flash")
        }
        // ksud 找不到匹配文件时会静默 rebuild 并退出 0，所以这个前置条件必须由我们拒绝。
        if (entry.entryId !in stockImageFiles().map { sha1Of(it) }.toSet()) {
            return@runCatching RestoreOutcome(
                false,
                "no stock image with sha1 ${entry.entryId} on this device; refusing to flash",
            )
        }

        staging.cleanupStale()
        val staged = staging.newFile("boot-restore-", ".img")
        try {
            content.use { input -> staged.outputStream().use { input.copyTo(it) } }
            val actualSha256 = staged.inputStream().use { it.sha256Hex() }
            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                return@runCatching RestoreOutcome(
                    false,
                    "boot archive is corrupted (sha256 mismatch); refusing to flash",
                )
            }
            // 把内容与身份绑死：伪造 meta + index 无法让一份任意镜像通过。
            val actualSha1 = staged.inputStream().use { it.sha1Hex() }
            if (actualSha1 != entry.entryId) {
                return@runCatching RestoreOutcome(
                    false,
                    "boot archive content does not match its recorded identity; refusing to flash",
                )
            }
            val target = "$backupDir/ksu_backup_${entry.entryId}"
            if (!rootFiles.copyTo(staged, target)) {
                return@runCatching RestoreOutcome(false, "cannot write $target")
            }
            restorer.restoreStockImage().getOrElse { error ->
                // 刷失败就把刚放回去的文件清掉，别在 ksud 的备份目录里留一份没用的镜像。
                rootFiles.delete(target)
                return@runCatching RestoreOutcome(false, error.message ?: error.javaClass.simpleName)
            }
            RestoreOutcome(true, entry.entryId)
        } finally {
            staged.delete()
        }
    }

    private fun renderMeta(sha1: String, size: Long, sha256: String): String =
        JSONObject().apply {
            put("schema", ArchiveNaming.BOOT_SCHEMA)
            put("version", ArchiveNaming.MANIFEST_VERSION)
            put("entryId", sha1)
            put("sha1", sha1)
            put("sizeBytes", size)
            put("sha256", sha256)
            put("createdAt", clock().toString())
        }.toString(2)

    private companion object {
        /** entryId 会拼进以 root 身份执行的 `cp`/`rm` 目标路径，所以只接受规整的 sha1。 */
        val SHA1_HEX = Regex("^[0-9a-f]{40}$")
    }
}
