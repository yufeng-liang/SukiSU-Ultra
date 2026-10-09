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
                    check(result.code == 0) {
                        result.err.ifBlank { "ksud boot-restore failed with code ${result.code}" }
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
            bootRetention = BackupDefaults.BOOT_RETENTION,
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
        bootRetention = BackupDefaults.BOOT_RETENTION,
    )

    private fun storageFor(origin: BackupOrigin): BackupStorage =
        if (origin == BackupOrigin.CLOUD) cloudStorage() else localStorage

    private fun engineFor(origin: BackupOrigin): BackupEngine =
        if (origin == BackupOrigin.CLOUD) cloudEngine() else engine

    /** 用输入框里当前的值测连接，而不是已保存的旧配置——否则用户改完地址不点保存就测了旧地址。 */
    suspend fun testCloud(url: String, user: String, pass: String): Result<Unit> =
        withContext(Dispatchers.IO) { cloudStorage(url.trim(), user.trim(), pass).test() }

    suspend fun backup(origin: BackupOrigin, kind: BackupKind): BackupRunResult = withContext(Dispatchers.IO) {
        if (origin == BackupOrigin.CLOUD && !cloudConfigured()) {
            return@withContext BackupRunResult(
                kind,
                failures = listOf(BackupFailure("config", "", "webdav", "cloud backup is not configured")),
            )
        }
        engineFor(origin).backup(kind)
    }

    suspend fun list(origin: BackupOrigin, kind: BackupKind): Result<List<BackupEntry>> =
        withContext(Dispatchers.IO) { engineFor(origin).list(storageFor(origin), kind) }

    /**
     * 恢复。回滚点始终落在本地后端：从云端恢复时把当前那一项写回云端既违背设计
     * （回滚点不上云），boot 时还等于每次恢复先上传 32–96MB。
     */
    suspend fun restore(origin: BackupOrigin, entry: BackupEntry): Result<RestoreOutcome> =
        withContext(Dispatchers.IO) {
            engineFor(origin).restore(storageFor(origin), entry, rollbackStorage = localStorage)
        }

    suspend fun exportToSaf(origin: BackupOrigin, entry: BackupEntry, uri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            val sink = context.contentResolver.openOutputStream(uri, "w")
                ?: return@withContext Result.failure(IllegalStateException("cannot open $uri"))
            engineFor(origin).exportTo(storageFor(origin), entry, sink)
        }

    /** 模块安装成功后调用。未开启自动备份时返回 null。 */
    suspend fun autoBackupAfterInstall(): BackupRunResult? = withContext(Dispatchers.IO) {
        if (!AutoBackupPolicy.shouldRun(settings.backupAutoAfterInstall, BackupKind.MODULE)) return@withContext null
        val local = engine.backup(BackupKind.MODULE)
        if (!cloudConfigured()) return@withContext local
        // 云端失败也要报出来，否则自动上传一直失败而用户什么都看不到。
        val cloud = cloudEngine().backup(BackupKind.MODULE)
        local.copy(
            written = local.written + cloud.written,
            skipped = local.skipped + cloud.skipped,
            failures = local.failures + cloud.failures,
        )
    }

    /** 读某条备份的边车元数据，用于列表展示（名称/版本/禁用状态）。 */
    suspend fun readMeta(origin: BackupOrigin, entry: BackupEntry): ModuleBackupMeta? = withContext(Dispatchers.IO) {
        val metaName = entry.metaFileName ?: return@withContext null
        storageFor(origin).readText(metaName).getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { BackupManifest.parseModuleMeta(it) }.getOrNull() }
    }

    /** SAF 选中的归档文件收编进本地备份列表，之后即可恢复。 */
    suspend fun importFromSaf(uri: Uri, kind: BackupKind): Result<Unit> = withContext(Dispatchers.IO) {
        // document provider 的 lastPathSegment 是内部段（primary:Download/x.zip），不是文件名。
        val name = uri.getFileName(context)
            ?: return@withContext Result.failure(IllegalStateException("cannot resolve file name for $uri"))
        workStaging.cleanupStale()
        val staged = workStaging.newFile("import-", ".bin")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { input.copyTo(it) }
            } ?: return@withContext Result.failure(IllegalStateException("cannot open $uri"))
            engine.importFrom(
                storage = localStorage,
                kind = kind,
                fileName = name,
                metaFileName = null,
                size = staged.length(),
                open = { staged.inputStream() },
                metaJson = null,
            )
        } finally {
            staged.delete()
        }
    }
}
