package com.sukisu.ultra.data.backup

import android.content.Context
import android.net.Uri
import com.sukisu.ultra.data.repository.ModuleRepositoryImpl
import com.sukisu.ultra.data.repository.RepoSourceRepositoryImpl
import com.sukisu.ultra.data.repository.SettingsRepository
import com.sukisu.ultra.data.repository.SettingsRepositoryImpl
import com.sukisu.ultra.ksuApp
import com.sukisu.ultra.ui.util.getFileName
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

    /**
     * 分区快照每次两张（boot 约 96MB + init_boot 约 8MB，压缩后一次约 20MB），
     * 按默认的 2 份算约 40MB；上限 10 份（见 [RetentionLimit.MAX_PARTITION]）约 200MB。
     */
    const val PARTITION_RETENTION = 2

    /**
     * 分区快照默认压缩。
     *
     * 放在这里而不是各处各写一个 `true`：UI 的初始状态与设置项的默认值必须同源，否则改了
     * 一处、另一处还是旧默认值，用户看到开关是关的、实际行为却是压的。
     */
    const val PARTITION_COMPRESSED = true
}

/**
 * 一条归档 + 它在哪一侧。
 *
 * 本地和云端各有一份索引，同一条模块在两边的文件名是一样的，所以来源必须跟着条目走——
 * 恢复、删除、打包都按它自己的来源去读，只看文件名会动错那一侧。列表把两侧合起来列之后，
 * 一次选择（乃至"一次备份"这一组）都可能横跨两侧。
 */
data class OriginEntry(val origin: BackupOrigin, val entry: BackupEntry)

/**
 * UI 层的唯一入口。把内核（引擎 + 源 + 本地/云端后端）组装起来。
 *
 * 引擎按**这次要写哪几处**现装配：一次备份把同一批内容打包一遍，再依次写到每一个后端，所以
 * 本机与云端勾在一起时模块只打一次包。两个后端共用同一份 [engineSources]——源本身没有状态，
 * 模块列表、ksud 调用都不便宜。
 *
 * 所有临时文件都收进 [workStaging] 或后端各自的 staging：以前打包/恢复/导入的文件直接落在
 * cacheDir 根下，不在任何 [StagingArea] 管辖内，[StagingArea.cleanupStale] 永远碰不到它们，
 * 分区快照每导出一次就在那儿留一份上百 MB 的镜像。
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

    /** 分区快照的块设备读写。引擎里的源与恢复确认前的 KMI 比对共用同一份（PartitionIo 无状态）。 */
    private val partitionIo: PartitionIo = ShellPartitionIo()

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
        // 分区快照不走 ksud：boot-restore 只认 /data/adb/ksu/ksu_backup_<sha1>（那是卸载 root
        // 用的原厂镜像），而这里要的是"把某个分区当前的样子存下来、以后原样刷回去"，只能自己 dd。
        BackupKind.PARTITION to PartitionSnapshotSource(
            io = partitionIo,
            clock = { Instant.now() },
            deviceName = { android.os.Build.MODEL ?: "unknown" },
            staging = workStaging,
            compress = { settings.backupPartitionCompressed },
        ),
        BackupKind.REPO_CONFIG to RepoConfigBackupSource(
            reader = { RepoSourceRepositoryImpl().loadSources() },
            writer = { RepoSourceRepositoryImpl().replaceSources(it) },
        ),
    )

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

    private fun storageFor(origin: BackupOrigin): BackupStorage =
        if (origin == BackupOrigin.CLOUD) cloudStorage() else localStorage

    /**
     * 装配一个引擎，写入 [origins] 这几处。
     *
     * 传多个来源**不是**为了省一次调用：引擎一次 [BackupEngine.backup] 只导出一次源，所以同一批
     * 模块打包一遍就能写到本机和云端两处。以前是按目标各调一次（kinds × origins 双层循环），
     * 两侧都勾时同一批模块被打了两遍 zip、算了两次 sha256——11 个模块几十 MB，就是几秒钟白烧的
     * CPU 和电量。
     *
     * **每次调用现装配**：保留额度是活设置（用户在设置页随时能改），缓存引擎等于把当时的额度也
     * 缓存了——改完设置本次会话一直按旧数字裁。装配本身很轻：sources 是长命对象，后端也只有云端
     * 那个要现建 OkHttp 客户端。
     */
    private fun engineFor(origins: List<BackupOrigin>): BackupEngine {
        val (moduleKeep, partitionKeep) =
            backupRetentionNow({ settings.backupRetention }, { settings.backupPartitionRetention })
        return BackupEngine(
            sources = engineSources,
            storages = origins.map { storageFor(it) },
            retention = moduleKeep,
            partitionRetention = partitionKeep,
        )
    }

    /** 用输入框里当前的值测连接，而不是已保存的旧配置——否则用户改完地址不点保存就测了旧地址。 */
    suspend fun testCloud(url: String, user: String, pass: String): Result<Unit> =
        withContext(Dispatchers.IO) { cloudStorage(url.trim(), user.trim(), pass).test() }

    /**
     * 一次备份：同一批内容**打包一次**，写到 [origins] 里的每一处，返回每一处各自的结果。
     *
     * [selected] 只对模块有意义：勾了哪几个就只打包哪几个，null = 全部。
     *
     * 自动备份（[autoBackupAfterInstall]）不走这里，它始终打包全部模块——那是一次无人值守的
     * 快照，"刚装完东西"这一刻要的是完整状态，而不是用户上次手工勾的那几个。
     */
    suspend fun backup(
        origins: List<BackupOrigin>,
        kind: BackupKind,
        selected: Set<String>? = null,
        onProgress: (TransferProgress) -> Unit = {},
        onPacking: (PackingProgress) -> Unit = {},
    ): Map<BackupOrigin, BackupRunResult> = withContext(Dispatchers.IO) {
        // 云端没配好就写不了：把它从"要写的后端"里摘掉，但结果里仍要有它那一份失败——用户勾了
        // 云端，就得知道这次没写上去。
        val writable = origins.filter { it != BackupOrigin.CLOUD || cloudConfigured() }
        val unwritable = origins.filterNot { it in writable }
        val result = if (writable.isEmpty()) {
            // 一处都写不了时不能去调引擎：它会照样把模块打包一遍，然后什么都不写。
            BackupRunResult(kind)
        } else {
            engineFor(writable).backup(kind, selected, onProgress, onPacking)
        }
        buildMap {
            writable.forEach { origin -> put(origin, result.forStorage(storageIdOf(origin))) }
            unwritable.forEach { origin ->
                put(
                    origin,
                    BackupRunResult(
                        kind,
                        failures = listOf(
                            BackupFailure(
                                path = "",
                                storage = storageIdOf(origin),
                                reason = BackupReason.CloudNotConfigured,
                            ),
                        ),
                    ),
                )
            }
        }
    }

    private fun storageIdOf(origin: BackupOrigin): String =
        if (origin == BackupOrigin.CLOUD) WebDavBackupStorage.ID else LocalBackupStorage.ID

    suspend fun list(origin: BackupOrigin, kind: BackupKind): Result<List<BackupEntry>> =
        withContext(Dispatchers.IO) { engineFor(listOf(origin)).list(storageFor(origin), kind) }

    /** 恢复 [entry]：从它所属的后端读归档，交给对应的源装回设备。 */
    suspend fun restore(origin: BackupOrigin, entry: BackupEntry): Result<RestoreOutcome> =
        withContext(Dispatchers.IO) {
            engineFor(listOf(origin)).restore(storageFor(origin), entry)
        }

    /** 删掉这几条备份：归档、边车 meta 和索引一起清。 */
    suspend fun delete(origin: BackupOrigin, entries: List<BackupEntry>): Result<Unit> =
        withContext(Dispatchers.IO) { engineFor(listOf(origin)).delete(storageFor(origin), entries) }

    /**
     * 把选中的条目收成一个可以分享出去的文件。
     *
     * 只选一条就给原样的归档：它还能被"从文件导入"吃回去，导出的东西才是可用的。多条打成一个
     * zip——分享面板里 11 个附件用户没法处理。文件落在专门的 `share/` 目录里并且**不删**：
     * 接收方的应用是分享面板关掉之后才去读它的；上一次分享的文件在这一次开始时清掉。
     *
     * 每条自带来源：列表把两侧合起来列之后，一次选择可能横跨本机与云端，打包要按各自的来源读。
     */
    suspend fun packForShare(items: List<OriginEntry>): Result<File> =
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                require(items.isNotEmpty()) { "nothing selected" }
                val dir = File(context.cacheDir, SHARE_DIR)
                dir.listFiles().orEmpty().forEach { it.delete() }
                dir.mkdirs()
                if (items.size == 1) {
                    val item = items.single()
                    val target = File(dir, item.entry.fileName.substringAfterLast('/'))
                    target.outputStream().use { sink ->
                        engineFor(listOf(item.origin)).exportTo(storageFor(item.origin), item.entry, sink).getOrThrow()
                    }
                    return@runCatchingCancellable target
                }
                val target = File(dir, shareZipName(items.map { it.entry }))
                ZipOutputStream(target.outputStream().buffered()).use { zip ->
                    items.forEach { item ->
                        zip.putNextEntry(ZipEntry(item.entry.fileName.substringAfterLast('/')))
                        // 走 engine.copyTo 而不是直接 storage.get().copyTo：这条路径以前完全不过
                        // sha256 校验（exportTo 有、这里没有），分享出去的多选包同样可能带截断内容。
                        engineFor(listOf(item.origin))
                            .copyTo(storageFor(item.origin), item.entry, zip)
                            .getOrThrow()
                        zip.closeEntry()
                    }
                }
                target
            }
        }

    /** zip 名字带上这一批是什么，用户存下来之后还认得出。 */
    private fun shareZipName(entries: List<BackupEntry>): String {
        val kind = when {
            entries.all { it.kind == BackupKind.PARTITION } -> "partition"
            entries.all { it.kind == BackupKind.REPO_CONFIG } -> "repo-config"
            else -> "modules"
        }
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
        val record = runAutoBackup(BackupKind.MODULE, targets)
        settings.backupAutoLastRecord = AutoBackupRecordJson.render(record)
        record
    }

    /**
     * 跑一次自动备份，把结果落成一条记录。
     *
     * 两处目的地写在**同一次**调用里：引擎一次 backup 只导出一次源，所以本机和云端都勾时
     * 模块只打包一遍。分成两次调用的话（以前就是这样）同一批模块会被打两遍。
     */
    private suspend fun runAutoBackup(kind: BackupKind, targets: AutoBackupPolicy.AutoBackupTargets): AutoBackupRecord {
        val origins = buildList {
            if (targets.local) add(BackupOrigin.LOCAL)
            if (targets.cloud) add(BackupOrigin.CLOUD)
        }
        val results = backup(origins, kind)
        val local = results[BackupOrigin.LOCAL]
        val cloud = results[BackupOrigin.CLOUD]
        return AutoBackupRecord(
            atEpochMs = System.currentTimeMillis(),
            outcome = AutoBackupPolicy.classify(local, cloud),
            writtenCount = (local?.written?.size ?: 0) + (cloud?.written?.size ?: 0),
            failures = local?.failures.orEmpty() + cloud?.failures.orEmpty(),
            // 内容没变的那几个也记下来：装同一个版本的模块时写入数是 0，不写清"没有新东西可存"，
            // 界面上那句"成功 · 写入 0 项"看着像什么都没干。
            skippedCount = (local?.skipped?.size ?: 0) + (cloud?.skipped?.size ?: 0),
        )
    }

    /** 上一次自动备份的结果。没跑过、或记录坏了都返回 null。 */
    fun lastAutoBackup(): AutoBackupRecord? = AutoBackupRecordJson.parse(settings.backupAutoLastRecord)

    /**
     * 仓库源发生变化（增删改）后调用。未开启自动备份时返回 null。
     *
     * 与 [autoBackupAfterInstall] 共用同一个自动备份总开关和目的地开关。归档是几百字节的
     * 确定性 JSON：源没变时被去重跳过，多触发一次只在结果里记一行 skipped；保留额度固定
     * 1 份（见 [RetentionPolicy.keepFor]），有变化时新的顶掉旧的。
     */
    suspend fun autoBackupRepoConfig(): AutoBackupRecord? = withContext(Dispatchers.IO) {
        if (!AutoBackupPolicy.shouldRunOnRepoSourceChange(settings.backupAutoAfterInstall)) {
            return@withContext null
        }
        val targets = AutoBackupPolicy.targets(
            local = settings.backupAutoLocal,
            cloud = settings.backupAutoCloud,
            cloudConfigured = cloudConfigured(),
        )
        if (!targets.any) return@withContext null
        val record = runAutoBackup(BackupKind.REPO_CONFIG, targets)
        settings.backupAutoLastRecord = AutoBackupRecordJson.render(record)
        record
    }

    /** 读某条备份的边车元数据，用于列表展示（名称/版本/禁用状态）。 */
    suspend fun readMeta(origin: BackupOrigin, entry: BackupEntry): ModuleBackupMeta? = withContext(Dispatchers.IO) {
        val metaName = entry.metaFileName ?: return@withContext null
        storageFor(origin).readText(metaName).getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { BackupManifest.parseModuleMeta(it) }.getOrNull() }
    }

    /**
     * 读某条分区快照的边车元数据。
     *
     * 恢复确认前要用它拿拍快照时的内核版本（[PartitionSnapshotMeta.kernelRelease]）。
     * 本机那份是普通文件读取，云端那份要经 WebDAV 下载——调用方应先试本机再试云端，
     * 且同一次快照的每份 meta 共享同一个内核版本，读到第一份非空即可停。
     */
    suspend fun readPartitionMeta(origin: BackupOrigin, entry: BackupEntry): PartitionSnapshotMeta? =
        withContext(Dispatchers.IO) {
            val metaName = entry.metaFileName ?: return@withContext null
            storageFor(origin).readText(metaName).getOrNull()
                ?.takeIf { it.isNotBlank() }
                // parsePartitionMeta 内部已把解析失败折成 null（runCatching+getOrNull），
                // 这里不再兜一层：坏 meta 就是「KMI 未知」，恢复确认按普通文案走。
                ?.let { BackupManifest.parsePartitionMeta(it) }
        }

    /** 当前系统的内核版本（`uname -r`）；取不到（root shell 失败）返回 null。 */
    suspend fun currentKernelRelease(): String? = withContext(Dispatchers.IO) {
        partitionIo.kernelRelease().takeIf { it.isNotBlank() }
    }

    /**
     * SAF 选中的归档文件收编进本地备份列表，之后即可恢复。
     *
     * 返回收编后**实际**记进去的类型：归档名里带着身份（`partition_` 前缀是分区快照），而界面上
     * 的"备份内容"勾选在另一个分页上，隔着这么远按勾选猜类型，会把快照归档登记成模块——之后点
     * "恢复"就会拿它去装模块。名字认不出来时才退回调用方给的那个。
     */
    suspend fun importFromSaf(uri: Uri, kind: BackupKind): Result<BackupKind> = withContext(Dispatchers.IO) {
        // 整段都收进 Result：这里的失败（选的文件打不开、超过导入上限、索引写不进去）是要给用户
        // 看的一句话，不是崩溃。取消照旧上抛，别把"用户离开页面"变成一次普通失败。
        runCatchingCancellable {
            // document provider 的 lastPathSegment 是内部段（primary:Download/x.zip），不是文件名。
            val name = uri.getFileName(context)
                ?: throw BackupReasonException(BackupReason.FileNameUnresolved)
            val actualKind = ArchiveNaming.kindOf(name) ?: kind
            workStaging.cleanupStale()
            val staged = workStaging.newFile("import-", ".bin")
            try {
                // provider 声称的大小靠不住，所以按实际读出来的字节封顶：超限当场停下，既不把
                // staging 撑到没有上限，也不让这份归档被登记进列表（登记了就是恢复时才炸）。
                val stagedBytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    staged.outputStream().use { output ->
                        input.copyBoundedTo(output, BackupLimits.MAX_IMPORT_BYTES, name)
                    }
                } ?: throw BackupReasonException(BackupReason.FileUnreadable)
                // 对照实际拷贝字节，拒绝流中途截断/长度变化。
                val declaredSize = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }
                if (declaredSize != null && declaredSize != stagedBytes) {
                    throw BackupReasonException(BackupReason.FileUnreadable)
                }
                // 导入也按当时的设置现装配引擎（见 engineFor）。
                engineFor(listOf(BackupOrigin.LOCAL)).importFrom(
                    storage = localStorage,
                    kind = actualKind,
                    fileName = name,
                    metaFileName = null,
                    size = stagedBytes,
                    open = { staged.inputStream() },
                    metaJson = null,
                ).getOrThrow()
                actualKind
            } finally {
                staged.delete()
            }
        }
    }
}
