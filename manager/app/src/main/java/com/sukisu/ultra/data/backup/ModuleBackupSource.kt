package com.sukisu.ultra.data.backup

import com.sukisu.ultra.data.model.Module
import com.sukisu.ultra.ui.util.ModuleProp
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

    override suspend fun export(
        selected: Set<String>?,
        onPacking: (PackingProgress) -> Unit,
    ): Result<ExportOutcome> = runCatchingCancellable {
        // 勾选的模块在打包期间可能被卸载：过滤后为空就是"没得备份"，交给上层按空结果处理，
        // 而不是把它当成错误——用户可能刚把勾上的模块删掉。
        val modules = lister.list()
        exportAll(if (selected == null) modules else modules.filter { it.id in selected }, onPacking)
    }

    private suspend fun exportAll(
        modules: List<Module>,
        onPacking: (PackingProgress) -> Unit,
    ): ExportOutcome {
        val timestamp = ArchiveNaming.timestamp(clock())
        val device = deviceName()
        val createdAt = clock().toString()
        val artifacts = mutableListOf<BackupArtifact>()
        val failures = mutableListOf<BackupFailure>()
        modules.forEachIndexed { index, module ->
            // 报在这个模块打包**之前**：点完就要看到东西在动，等这一个打完才报等于又停了一次，
            // 而第一个模块往往就是最费时的那个。
            onPacking(PackingProgress(index + 1, modules.size))
            // 已经挂了 remove 标记的模块下次开机就没了。给它打包等于备份一个正在退场的状态：
            // 用户重启后看着它消失，之后从这份备份恢复它又回来了。跳过并说清原因，
            // 而不是静默少一条（静默少一条会被读成"备份漏了"）。
            if (module.remove) {
                failures += BackupFailure(
                    path = module.id,
                    storage = SOURCE_STORAGE,
                    reason = BackupReason.ModulePendingRemoval(module.id),
                )
                return@forEachIndexed
            }
            val archived = archiver.archive(module).getOrElse { error ->
                // 打包失败要报出来：静默跳过会让用户看到"written N"却不知道有模块没备上。
                failures += BackupFailure(
                    path = module.id,
                    storage = SOURCE_STORAGE,
                    reason = BackupReason.ArchiveFailed(module.id, externalTextOrNull(error)),
                )
                return@forEachIndexed
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
    ): Result<RestoreOutcome> = runCatchingCancellable {
        // entryId 直接来自索引（共享存储或远端，外部可写），恢复禁用状态时会被拼进以 root 执行的
        // ksud 命令。形状不对就地拒绝：装上了也不会去执行那条命令，与其说"恢复了"再说"禁用态没恢复"，
        // 不如一开始就告诉用户这份备份的 id 不可用。
        if (!ArchiveNaming.isValidModuleId(entry.entryId)) {
            return@runCatchingCancellable RestoreOutcome(false, BackupReason.ModuleIdUnusable(entry.entryId))
        }
        staging.cleanupStale()
        val staged = staging.newFile("restore-", ".zip")
        try {
            content.use { input -> staged.outputStream().use { input.copyTo(it) } }
            // 装之前先认包：条目里的 entryId 来自索引（共享存储 / 远端 / 导入的归档，都可写），
            // 而 ksud 只认包里的 module.prop.id。两者不一致时，「恢复 A」会实际装上 B，
            // 紧接着 setDisabled 还会照着 A 的 id 去执行——所以这里必须先把关。
            val actualId = ModuleProp.parseModuleProp(staged)?.id
            if (actualId != entry.entryId) {
                return@runCatchingCancellable RestoreOutcome(
                    false,
                    BackupReason.ModuleZipUnusable(entry.entryId),
                )
            }
            restorer.install(staged).getOrElse { error ->
                return@runCatchingCancellable RestoreOutcome(
                    false,
                    BackupReason.ModuleInstallFailed(entry.entryId, externalTextOrNull(error)),
                )
            }
            val meta = metaJson?.let { runCatching { BackupManifest.parseModuleMeta(it) }.getOrNull() }
            if (meta?.disabled == true) {
                restorer.setDisabled(entry.entryId, true).getOrElse { error ->
                    return@runCatchingCancellable RestoreOutcome(
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
