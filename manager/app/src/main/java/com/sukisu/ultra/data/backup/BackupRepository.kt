package com.sukisu.ultra.data.backup

import android.content.Context
import android.net.Uri
import com.sukisu.ultra.data.repository.ModuleRepositoryImpl
import com.sukisu.ultra.data.repository.SettingsRepository
import com.sukisu.ultra.data.repository.SettingsRepositoryImpl
import com.sukisu.ultra.ksuApp
import com.sukisu.ultra.ui.util.getFileName
import com.sukisu.ultra.ui.util.restoreBoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.time.Instant
import java.util.concurrent.TimeUnit

object BackupDefaults {
    const val LOCAL_DIR = "/sdcard/Download/SukiSU-Backup"
    const val RETENTION = 5

    /** boot 单张原厂镜像 32–96MB，留 5 份就是几百 MB，默认只留 2 份。 */
    const val BOOT_RETENTION = 2

}

/**
 * UI 层的唯一入口。把内核（引擎 + 源 + 本地/云端后端）组装起来。
 *
 * 本地与云端各持一个 [BackupEngine]，共用同一份 [engineSources]：
 * 云端故障（断网、凭据过期）不能拖慢本地备份与恢复。
 *
 * 所有临时文件都收进 [workStaging] 或后端各自的 staging：以前打包/恢复/导入的文件直接落在
 * cacheDir 根下，不在任何 [StagingArea] 管辖内，[StagingArea.cleanupStale] 永远碰不到它们，
 * boot 每导出一次就在那儿留一份 32–96MB 的镜像。
 */
class BackupRepository(private val context: Context = ksuApp) {

    private val settings: SettingsRepository = SettingsRepositoryImpl()

    private companion object {
        /** 分享文件的落点：FileProvider 只暴露了 cache 目录，所以它必须在 cache 里。 */
        const val SHARE_DIR = "backup-share"
    }

    /** 打包、恢复、导入共用的临时区。 */
    private val workStaging = StagingArea(File(context.cacheDir, "backup-staging/work"))

    private val localStorage by lazy {
        LocalBackupStorage(
            rootDir = BackupDefaults.LOCAL_DIR,
            staging = StagingArea(File(context.cacheDir, "backup-staging/local")),
            rootFiles = ShellRootFiles(),
        )
    }

    /** 本地与云端两个引擎共用同一份源，模块打包/ksud 调用都不便宜。 */
    private val engineSources: Map<BackupKind, BackupSource> = mapOf(
        BackupKind.MODULE to ModuleBackupSource(
            lister = { ModuleRepositoryImpl().getModules().getOrThrow() },
            archiver = ZipModuleArchiver(workStaging),
            restorer = KsuModuleRestorer(),
            clock = { Instant.now() },
            deviceName = { android.os.Build.MODEL ?: "unknown" },
            staging = workStaging,
        ),
        BackupKind.BOOT to BootBackupSource(
            rootFiles = ShellRootFiles(),
            // ksud 只按当前 patched boot 里的 sha1 找文件、不校验内容，校验由 BootBackupSource 自己做
            restorer = {
                runCatching {
                    val result = restoreBoot({}, {})
                    if (result.code != 0) {
                        // ksud 的 stderr 才是真正的原因；它可能是空的，那就让 UI 只说"恢复失败"。
                        throw BackupReasonException(
                            BackupReason.BootFlashFailed(result.err.takeIf { it.isNotBlank() }),
                        )
                    }
                }
            },
            clock = { Instant.now() },
            staging = workStaging,
        ),
    )

    private val engine by lazy {
        BackupEngine(
            sources = engineSources,
            storages = listOf(localStorage),
            retention = settings.backupRetention,
            bootRetention = settings.backupBootRetention,
        )
    }

    /** 云端是否已配置：开关打开且地址非空。 */
    fun cloudConfigured(): Boolean = settings.backupCloudEnabled && settings.webDavUrl.isNotBlank()

    /** 每次调用按当前设置重新装配一个后端（OkHttp 客户端 + staging 目录）。 */
    private fun cloudStorage(
        url: String = settings.webDavUrl,
        user: String = settings.webDavUser,
        pass: String = settings.webDavPassword,
    ): WebDavBackupStorage = WebDavBackupStorage(
        client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build(),
        baseUrl = url,
        username = user,
        password = pass,
        staging = StagingArea(File(context.cacheDir, "backup-staging/webdav")),
    )

    private fun cloudEngine() = BackupEngine(
        sources = engineSources,
        storages = listOf(cloudStorage()),
        retention = settings.backupRetention,
        bootRetention = settings.backupBootRetention,
    )

    private fun storageFor(origin: BackupOrigin): BackupStorage =
        if (origin == BackupOrigin.CLOUD) cloudStorage() else localStorage

    private fun engineFor(origin: BackupOrigin): BackupEngine =
        if (origin == BackupOrigin.CLOUD) cloudEngine() else engine

    /** 用输入框里当前的值测连接，而不是已保存的旧配置——否则用户改完地址不点保存就测了旧地址。 */
    suspend fun testCloud(url: String, user: String, pass: String): Result<Unit> =
        withContext(Dispatchers.IO) { cloudStorage(url.trim(), user.trim(), pass).test() }

    /**
     * [selected] 只对模块有意义：勾了哪几个就只打包哪几个，null = 全部。
     *
     * 自动备份（[autoBackupAfterInstall]）不走这里，它始终打包全部模块——那是一次无人值守的
     * 快照，"刚装完东西"这一刻要的是完整状态，而不是用户上次手工勾的那几个。
     */
    suspend fun backup(
        origin: BackupOrigin,
        kind: BackupKind,
        selected: Set<String>? = null,
        onProgress: (TransferProgress) -> Unit = {},
    ): BackupRunResult = withContext(Dispatchers.IO) {
        if (origin == BackupOrigin.CLOUD && !cloudConfigured()) {
            return@withContext BackupRunResult(
                kind,
                failures = listOf(
                    BackupFailure(
                        path = "",
                        storage = WebDavBackupStorage.ID,
                        reason = BackupReason.CloudNotConfigured,
                    ),
                ),
            )
        }
        engineFor(origin).backup(kind, selected, onProgress)
    }

    suspend fun list(origin: BackupOrigin, kind: BackupKind): Result<List<BackupEntry>> =
        withContext(Dispatchers.IO) { engineFor(origin).list(storageFor(origin), kind) }

    /** 恢复 [entry]：从它所属的后端读归档，交给对应的源装回设备。 */
    suspend fun restore(origin: BackupOrigin, entry: BackupEntry): Result<RestoreOutcome> =
        withContext(Dispatchers.IO) {
            engineFor(origin).restore(storageFor(origin), entry)
        }

    /** 删掉这几条备份：归档、边车 meta 和索引一起清。 */
    suspend fun delete(origin: BackupOrigin, entries: List<BackupEntry>): Result<Unit> =
        withContext(Dispatchers.IO) { engineFor(origin).delete(storageFor(origin), entries) }

    /**
     * 把选中的条目收成一个可以分享出去的文件。
     *
     * 只选一条就给原样的归档：它还能被"从文件导入"吃回去，导出的东西才是可用的。多条打成一个
     * zip——分享面板里 11 个附件用户没法处理。文件落在专门的 `share/` 目录里并且**不删**：
     * 接收方的应用是分享面板关掉之后才去读它的；上一次分享的文件在这一次开始时清掉。
     */
    suspend fun packForShare(origin: BackupOrigin, entries: List<BackupEntry>): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(entries.isNotEmpty()) { "nothing selected" }
                val dir = File(context.cacheDir, SHARE_DIR)
                dir.listFiles().orEmpty().forEach { it.delete() }
                dir.mkdirs()
                val engine = engineFor(origin)
                val storage = storageFor(origin)
                if (entries.size == 1) {
                    val entry = entries.single()
                    val target = File(dir, entry.fileName.substringAfterLast('/'))
                    target.outputStream().use { sink -> engine.exportTo(storage, entry, sink).getOrThrow() }
                    return@runCatching target
                }
                val target = File(dir, shareZipName(entries))
                ZipOutputStream(target.outputStream().buffered()).use { zip ->
                    entries.forEach { entry ->
                        zip.putNextEntry(ZipEntry(entry.fileName.substringAfterLast('/')))
                        storage.get(entry.fileName).getOrThrow().use { content -> content.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                target
            }
        }

    /** zip 名字带上这一批是什么，用户存下来之后还认得出。 */
    private fun shareZipName(entries: List<BackupEntry>): String {
        val kind = if (entries.all { it.kind == BackupKind.BOOT }) "boot" else "modules"
        return "SukiSU-Backup-$kind-${ArchiveNaming.timestamp(Instant.now())}.zip"
    }

    /**
     * 模块安装成功后调用。未开启自动备份时返回 null。
     *
     * 写本地还是写云端由设置里的两个开关决定，与页面上那对「备份位置」勾选无关——那边管
     * 手动备份这一次，这边管无人值守的那一次。两处都没勾时什么都不做，也返回 null，
     * 界面会说明原因（`backup_auto_dest_none`）。
     *
     * 结果**落盘**：调用方（刷入流程）跑完就散，用户看不到任何东西，这条记录是事后唯一
     * 能回答"那次备上了没有"的地方。
     */
    suspend fun autoBackupAfterInstall(): AutoBackupRecord? = withContext(Dispatchers.IO) {
        if (!AutoBackupPolicy.shouldRun(settings.backupAutoAfterInstall, BackupKind.MODULE)) {
            return@withContext null
        }
        val targets = AutoBackupPolicy.targets(
            local = settings.backupAutoLocal,
            cloud = settings.backupAutoCloud,
            cloudConfigured = cloudConfigured(),
        )
        if (!targets.any) return@withContext null
        val local = if (targets.local) engine.backup(BackupKind.MODULE) else null
        // 云端失败也要报出来，否则自动上传一直失败而用户什么都看不到。
        val cloud = if (targets.cloud) cloudEngine().backup(BackupKind.MODULE) else null
        val record = AutoBackupRecord(
            atEpochMs = System.currentTimeMillis(),
            outcome = AutoBackupPolicy.classify(local, cloud),
            writtenCount = (local?.written?.size ?: 0) + (cloud?.written?.size ?: 0),
            failures = local?.failures.orEmpty() + cloud?.failures.orEmpty(),
            // 内容没变的那几个也记下来：装同一个版本的模块时写入数是 0，不写清"没有新东西可存"，
            // 界面上那句"成功 · 写入 0 项"看着像什么都没干。
            skippedCount = (local?.skipped?.size ?: 0) + (cloud?.skipped?.size ?: 0),
        )
        settings.backupAutoLastRecord = AutoBackupRecordJson.render(record)
        record
    }

    /** 上一次自动备份的结果。没跑过、或记录坏了都返回 null。 */
    fun lastAutoBackup(): AutoBackupRecord? = AutoBackupRecordJson.parse(settings.backupAutoLastRecord)

    /** 读某条备份的边车元数据，用于列表展示（名称/版本/禁用状态）。 */
    suspend fun readMeta(origin: BackupOrigin, entry: BackupEntry): ModuleBackupMeta? = withContext(Dispatchers.IO) {
        val metaName = entry.metaFileName ?: return@withContext null
        storageFor(origin).readText(metaName).getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { BackupManifest.parseModuleMeta(it) }.getOrNull() }
    }

    /**
     * SAF 选中的归档文件收编进本地备份列表，之后即可恢复。
     *
     * 返回收编后**实际**记进去的类型：归档名里带着身份（`boot_` 前缀是原厂镜像），而界面上的
     * "备份内容"勾选在另一个分页上，隔着这么远按勾选猜类型，会把 boot 归档登记成模块——
     * 之后点"恢复"就会拿它去装模块。名字认不出来时才退回调用方给的那个。
     */
    suspend fun importFromSaf(uri: Uri, kind: BackupKind): Result<BackupKind> = withContext(Dispatchers.IO) {
        // document provider 的 lastPathSegment 是内部段（primary:Download/x.zip），不是文件名。
        val name = uri.getFileName(context)
            ?: return@withContext Result.failure(BackupReasonException(BackupReason.FileNameUnresolved))
        val actualKind = ArchiveNaming.kindOf(name) ?: kind
        workStaging.cleanupStale()
        val staged = workStaging.newFile("import-", ".bin")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { input.copyTo(it) }
            } ?: return@withContext Result.failure(BackupReasonException(BackupReason.FileUnreadable))
            engine.importFrom(
                storage = localStorage,
                kind = actualKind,
                fileName = name,
                metaFileName = null,
                size = staged.length(),
                open = { staged.inputStream() },
                metaJson = null,
            ).map { actualKind }
        } finally {
            staged.delete()
        }
    }
}
