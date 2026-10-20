package com.sukisu.ultra.data.backup

import java.io.File
import java.io.InputStream
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * 分区快照：直接读块设备，把 boot / init_boot 原样存下来。
 *
 * 与旧的「原厂镜像」备份的本质区别：旧的那一份**不是我们抓的**——它是 ksud 在打补丁时自己
 * 存到 `/data/adb/ksu/ksu_backup_<sha1>` 的原厂镜像，用于卸载 root；它只在第一次打补丁时
 * 存在，且恢复它等于"恢复成没 root 的系统"。而这里要的是用户自己那个念头：**现在设备是好的，
 * 先把分区存下来，刷内核刷坏了能刷回去**——所以必须能主动抓、能分别刷回。
 *
 * 两条纪律：
 * 1. **只刷它自称的那个分区**。分区名来自边车 meta（外部可写），必须过白名单；
 * 2. **长度必须对得上**。解压后的字节数与 meta 里记录的分区大小不等就拒绝写入——boot 分区
 *    96MB，写进去 8MB 的 init_boot 镜像会把分区头部覆盖掉，设备起不来。
 */
class PartitionSnapshotSource(
    private val io: PartitionIo,
    private val clock: () -> Instant,
    private val deviceName: () -> String,
    private val staging: StagingArea,
    /** 是否 gzip 压缩。默认开：boot 96MB 压完约 20MB，客户端上传云端时这点差距很要紧。 */
    private val compress: () -> Boolean = { true },
) : BackupSource {

    override val kind: BackupKind = BackupKind.PARTITION

    /**
     * 抓取当前槽位的所有分区（boot / init_boot / vendor_boot 中存在的那些）。
     *
     * [selected] 是分区名集合；null = 全部。界面上「分区快照」只有一栏，勾不勾都抓全部，
     * 所以这里保留参数只是为了与其他源保持同一个签名。
     */
    override suspend fun export(
        selected: Set<String>?,
        onPacking: (PackingProgress) -> Unit,
    ): Result<ExportOutcome> = runCatchingCancellable {
        staging.cleanupStale()
        val all = io.snapshots()
        val targets = if (selected == null) all else all.filter { it.name in selected }
        if (targets.isEmpty()) {
            // 一个能拍的分区都没有：不是"失败"，是"这台设备上没这东西"（比如非 A/B 的老设备
            // 没有独立 init_boot）。返回空产物，由上层按"没什么可备份"处理。
            return@runCatchingCancellable ExportOutcome()
        }
        val timestamp = ArchiveNaming.timestamp(clock())
        val createdAt = clock().toString()
        // 压缩开关在**同一次导出内**只读一次：中途改设置不该让同一次备份里一半压缩一半不压缩。
        val gzip = compress()
        val device = deviceName()
        // 内核版本同样只读一次：同一次快照就是同一个内核，每份 meta 共享同一个 uname——
        // 恢复确认前拿它和当前的比对（KMI 变了要警告）。
        val kernelRelease = io.kernelRelease().takeIf { it.isNotBlank() }
        val artifacts = mutableListOf<BackupArtifact>()
        val failures = mutableListOf<BackupFailure>()
        targets.forEachIndexed { index, info ->
            // 报在读分区**之前**：96MB 的 dd 要跑几秒，不报就是在界面上干等。
            onPacking(PackingProgress(index + 1, targets.size))
            val staged = staging.newFile("partition-", if (gzip) ".img.gz" else ".img")
            try {
                io.dump(info, staged, gzip).getOrElse { error ->
                    failures += BackupFailure(
                        path = info.name,
                        storage = SOURCE_STORAGE,
                        reason = BackupReason.PartitionDumpFailed(info.name, externalTextOrNull(error)),
                    )
                    // 没走到 artifact 就没有 cleanup 回调，这里自己收尾——96MB × 两个分区，
                    // 失败几次就能把缓存撑满。
                    staged.delete()
                    return@forEachIndexed
                }
                val fileName = ArchiveNaming.partitionArchiveName(info.name, timestamp, gzip)
                val meta = PartitionSnapshotMeta(
                    entryId = info.name,
                    partition = info.name,
                    sourceDevice = device,
                    partitionSizeBytes = info.sizeBytes,
                    compressed = gzip,
                    createdAt = createdAt,
                    kernelRelease = kernelRelease,
                )
                artifacts += BackupArtifact(
                    kind = kind,
                    // entryId 用分区名：索引里一条分区快照的身份就是"哪个分区"，
                    // 不像模块那样是模块 id。
                    entryId = info.name,
                    fileName = fileName,
                    metaFileName = ArchiveNaming.metaFileNameFor(fileName),
                    sizeBytes = staged.length(),
                    sha256 = staged.inputStream().use { it.sha256Hex() },
                    metaJson = BackupManifest.renderPartitionMeta(meta),
                    openContent = { staged.inputStream() },
                    cleanup = { staged.delete() },
                )
            } catch (error: Throwable) {
                // 取消必须原样穿透：吞掉它会让循环继续以 root 身份读块设备、压 96MB，
                // 用户点了"取消"却还在跑。BackupEngine 里同类 catch 也是这么写的。
                if (error is CancellationException) throw error
                // staging 落盘、算 sha256 这些再失败就真的没法继续了；但仍要记一条并继续
                // 下一个分区——boot 抓失败不该让 init_boot 也不抓。
                failures += BackupFailure(
                    path = info.name,
                    storage = SOURCE_STORAGE,
                    reason = BackupReason.PartitionDumpFailed(info.name, externalTextOrNull(error)),
                )
                staged.delete()
            }
        }
        ExportOutcome(artifacts, failures)
    }

    /**
     * 把归档刷回它自称的那个分区。
     *
     * 顺序是刻意的：**先**验证 meta、**再**校验长度，**最后**才碰块设备。任何一步过不去都在
     * 写入之前返回——分区被半截镜像覆盖是不可逆的。
     */
    override suspend fun restore(
        entry: BackupEntry,
        metaJson: String?,
        content: InputStream,
    ): Result<RestoreOutcome> = runCatchingCancellable {
        val meta = metaJson?.let { BackupManifest.parsePartitionMeta(it) }
        if (meta == null) {
            // 没有 meta 就不知道往哪刷；猜一个分区名等于蒙着眼睛刷机。
            return@runCatchingCancellable RestoreOutcome(false, BackupReason.PartitionNoSidecarMeta)
        }
        if (!ShellPartitionIo.isValidName(meta.partition)) {
            return@runCatchingCancellable RestoreOutcome(false, BackupReason.PartitionNameUnusable(meta.partition))
        }
        // 交叉校验：边车 meta、归档名、索引里的 entryId 三处必须指向同一个分区。
        // 只看 meta 是不够的——meta 和索引都在共享存储/远端，谁都能改；把 boot_a 的边车改成
        // init_boot_a、再配一份等长的 init_boot 镜像，只校验"名字合法 + 本机存在 + 长度相等"
        // 是拦不住的。三处同时改还要彼此自洽，门槛高得多。
        val namedInFile = partitionFromName(entry.fileName)
        if (namedInFile != null && namedInFile != meta.partition) {
            return@runCatchingCancellable RestoreOutcome(false, BackupReason.PartitionNameUnusable(namedInFile))
        }
        if (ShellPartitionIo.isValidName(entry.entryId) && entry.entryId != meta.partition) {
            return@runCatchingCancellable RestoreOutcome(false, BackupReason.PartitionNameUnusable(entry.entryId))
        }
        // 文件名后缀和 meta 里的 compressed 是两份独立信息，互相矛盾时说明其中一份被改过。
        // 提前拒绝，好过让 gunzip 在坏数据上抛一个用户看不懂的异常。
        if (isCompressedName(entry.fileName) != meta.compressed) {
            return@runCatchingCancellable RestoreOutcome(false, BackupReason.PartitionNameUnusable(entry.fileName))
        }
        // 分区当前是否存在、当前大小多少，都以**现在**为准（不是 meta 里的旧值）。
        val present = io.snapshots().firstOrNull { it.name == meta.partition }
        if (present == null) {
            return@runCatchingCancellable RestoreOutcome(false, BackupReason.PartitionMissing(meta.partition))
        }
        if (meta.partitionSizeBytes <= 0L) {
            // 我们自己的 meta 总会写这个字段，读到 0 只可能是被改过或来自别的工具。
            // 按"元数据不全"拒绝，而不是"未知就放行"——放行等于让一个 JSON 字段关掉这道预检。
            return@runCatchingCancellable RestoreOutcome(false, BackupReason.PartitionNoSidecarMeta)
        }
        if (meta.partitionSizeBytes != present.sizeBytes) {
            // 拍快照那刻的分区大小和现在不一样：多半是这份快照来自另一台设备（或分区被重划过）。
            // 长度校验本可以兜住大部分情况，但"来自别的设备"值得单独说清楚——用户以为是同一个
            // 分区，其实分区表已经变了。
            return@runCatchingCancellable RestoreOutcome(
                false,
                BackupReason.PartitionTableChanged(meta.partition, present.sizeBytes, meta.partitionSizeBytes),
            )
        }
        staging.cleanupStale()
        val staged = staging.newFile("flash-", if (meta.compressed) ".img.gz" else ".img")
        try {
            // 先把整个归档落到 staging：流是边下边读的（云端），直接边解压边写分区等于
            // 网络抖一下就留下半截分区。落盘之后长度可查，才有下面这一步。
            // 上限也必须在这里拦一道：storage 那边对远端文件大小没有保证，
            // 一个被替换成 10GB 的归档能把 /data 写满。
            val written = content.use { input ->
                staged.outputStream().use { output ->
                    input.copyBoundedTo(output, BackupLimits.MAX_IMPORT_BYTES, entry.fileName)
                }
            }
            if (written <= 0L) {
                return@runCatchingCancellable RestoreOutcome(
                    false,
                    BackupReason.ReadFailed(staged.name, null),
                )
            }
            // gzip 头损坏/截断时 GZIPInputStream 抛 IOException；映射成备份原因，
            // 否则用户看到的是原始异常文本，BackupText 里那套分区文案一条都用不上。
            val payloadBytes = if (meta.compressed) {
                runCatching { staged.gunzipLength(present.sizeBytes) }.getOrElse { error ->
                    return@runCatchingCancellable RestoreOutcome(
                        false,
                        BackupReason.ReadFailed(staged.name, externalTextOrNull(error)),
                    )
                }
            } else {
                written
            }
            if (payloadBytes != present.sizeBytes) {
                // 长度不等就拒绝：这是"刷错分区"和"刷了残缺镜像"唯一能提前发现的地方。
                return@runCatchingCancellable RestoreOutcome(
                    false,
                    BackupReason.PartitionSizeMismatch(meta.partition, present.sizeBytes, payloadBytes),
                )
            }
            io.flash(present, staged, meta.compressed).getOrElse { error ->
                return@runCatchingCancellable RestoreOutcome(
                    false,
                    BackupReason.PartitionFlashFailed(meta.partition, externalTextOrNull(error)),
                )
            }
            RestoreOutcome(true)
        } finally {
            staged.delete()
        }
    }

    /** 导入外部归档时补一份 meta：分区名从文件名里认（`partition_boot_a_...img`）。 */
    override suspend fun metaForImport(
        fileName: String,
        open: () -> InputStream,
        size: Long,
    ): ImportMeta? {
        val partition = partitionFromName(fileName) ?: return null
        val info = io.snapshots().firstOrNull { it.name == partition } ?: return null
        val meta = PartitionSnapshotMeta(
            entryId = partition,
            partition = partition,
            sourceDevice = deviceName(),
            // 导入的归档体积未知是否与当前分区一致，所以不记旧大小——恢复时会按**现在**的
            // 分区大小重新比对，记错了反而会挡住一次合法恢复。
            partitionSizeBytes = info.sizeBytes,
            compressed = isCompressedName(fileName),
            createdAt = clock().toString(),
            // 导入的归档拍自哪个内核无从知晓：留 null，恢复确认按「KMI 未知」走普通文案。
            kernelRelease = null,
        )
        return ImportMeta(partition, BackupManifest.renderPartitionMeta(meta))
    }

    private companion object {
        const val SOURCE_STORAGE = "source"
    }
}

/** 从归档名里认出分区名；认不出返回 null（导入时宁可拒绝也不猜）。 */
internal fun partitionFromName(archiveName: String): String? {
    val name = archiveName.substringAfterLast('/')
    if (!name.startsWith("partition_")) return null
    val rest = name.removePrefix("partition_")
    // 分区名本身可能带下划线（init_boot_a），时间戳前面那段才是分区名。
    val timestampAt = Regex("_\\d{8}_\\d{6}").find(rest)?.range?.first ?: return null
    val partition = rest.substring(0, timestampAt)
    return partition.takeIf { ShellPartitionIo.isValidName(it) }
}

/** 归档名是否带 `.gz`。 */
internal fun isCompressedName(archiveName: String): Boolean =
    archiveName.substringAfterLast('/').endsWith(".gz")

/**
 * 数一遍 gzip 解压后的字节数，不落第二份临时文件。
 *
 * [limit] 是提前退出的阈值：解压结果一旦超过它就没有继续数的意义（长度已经不对了），
 * 而 gzip 炸弹能让这个循环读上几分钟。超过就抛异常，由调用方映射成备份原因。
 */
private fun File.gunzipLength(limit: Long = Long.MAX_VALUE): Long =
    java.util.zip.GZIPInputStream(inputStream().buffered()).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) throw java.io.IOException("payload exceeds $limit bytes")
        }
        total
    }
