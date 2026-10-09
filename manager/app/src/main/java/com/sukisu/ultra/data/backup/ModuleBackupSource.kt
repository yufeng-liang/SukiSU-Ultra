package com.sukisu.ultra.data.backup

import com.sukisu.ultra.data.model.Module
import java.io.File
import java.io.InputStream
import java.time.Instant

fun interface ModuleLister {
    suspend fun list(): List<Module>
}

data class ArchivedModule(val file: File, val sizeBytes: Long, val sha256: String)

/** 把一个已安装模块打成一个标准模块 zip（module.prop 在 zip 根，可直接被 ksud module install 消费）。 */
fun interface ModuleArchiver {
    suspend fun archive(module: Module): Result<ArchivedModule>
}

interface ModuleRestorer {
    suspend fun install(zip: File): Result<Unit>
    suspend fun setDisabled(id: String, disabled: Boolean): Result<Unit>
}

class ModuleBackupSource(
    private val lister: ModuleLister,
    private val archiver: ModuleArchiver,
    private val restorer: ModuleRestorer,
    private val clock: () -> Instant,
    private val deviceName: () -> String,
    private val staging: StagingArea,
) : BackupSource {

    override val kind: BackupKind = BackupKind.MODULE

    override suspend fun export(selected: Set<String>?): Result<ExportOutcome> = runCatching {
        // 勾选的模块在打包期间可能被卸载：过滤后为空就是"没得备份"，交给上层按空结果处理，
        // 而不是把它当成错误——用户可能刚把勾上的模块删掉。
        val modules = lister.list()
        exportAll(if (selected == null) modules else modules.filter { it.id in selected })
    }

    override suspend fun exportOne(entryId: String): Result<BackupArtifact?> = runCatching {
        lister.list().firstOrNull { it.id == entryId }?.let { exportAll(listOf(it)).artifacts.firstOrNull() }
    }

    private suspend fun exportAll(modules: List<Module>): ExportOutcome {
        val timestamp = ArchiveNaming.timestamp(clock())
        val device = deviceName()
        val createdAt = clock().toString()
        val artifacts = mutableListOf<BackupArtifact>()
        val failures = mutableListOf<BackupFailure>()
        modules.forEach { module ->
            val archived = archiver.archive(module).getOrElse { error ->
                // 打包失败要报出来：静默跳过会让用户看到"written N"却不知道有模块没备上。
                failures += BackupFailure(
                    path = module.id,
                    storage = SOURCE_STORAGE,
                    reason = BackupReason.ArchiveFailed(module.id, externalTextOrNull(error)),
                )
                return@forEach
            }
            val fileName = ArchiveNaming.moduleArchiveName(module.id, module.versionCode, timestamp)
            val meta = ModuleBackupMeta(
                entryId = module.id,
                name = module.name,
                versionName = module.version,
                versionCode = module.versionCode,
                author = module.author,
                metamodule = module.metamodule,
                disabled = !module.enabled,
                sourceDevice = device,
                createdAt = createdAt,
            )
            artifacts += BackupArtifact(
                kind = kind,
                entryId = module.id,
                fileName = fileName,
                metaFileName = ArchiveNaming.metaFileNameFor(fileName),
                sizeBytes = archived.sizeBytes,
                sha256 = archived.sha256,
                metaJson = BackupManifest.renderModuleMeta(meta),
                openContent = { archived.file.inputStream() },
                cleanup = { archived.file.delete() },
            )
        }
        return ExportOutcome(artifacts, failures)
    }

    override suspend fun restore(
        entry: BackupEntry,
        metaJson: String?,
        content: InputStream,
    ): Result<RestoreOutcome> = runCatching {
        staging.cleanupStale()
        val staged = staging.newFile("restore-", ".zip")
        try {
            content.use { input -> staged.outputStream().use { input.copyTo(it) } }
            restorer.install(staged).getOrElse { error ->
                return@runCatching RestoreOutcome(
                    false,
                    BackupReason.ModuleInstallFailed(entry.entryId, externalTextOrNull(error)),
                )
            }
            val meta = metaJson?.let { runCatching { BackupManifest.parseModuleMeta(it) }.getOrNull() }
            if (meta?.disabled == true) {
                restorer.setDisabled(entry.entryId, true).getOrElse { error ->
                    return@runCatching RestoreOutcome(
                        false,
                        BackupReason.ModuleDisableFailed(entry.entryId, externalTextOrNull(error)),
                    )
                }
            }
            RestoreOutcome(true)
        } finally {
            staged.delete()
        }
    }

    private companion object {
        /** 源侧失败的 storage 标记：UI 只在本地/云端之间需要区分，这个不翻译成"备份位置"。 */
        const val SOURCE_STORAGE = "source"
    }
}
