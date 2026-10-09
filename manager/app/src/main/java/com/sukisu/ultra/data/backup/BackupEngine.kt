package com.sukisu.ultra.data.backup

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 在 [BackupSource] 与 [BackupStorage] 之间编排备份与恢复。
 *
 * 一次备份把源导出一次，然后对每个后端独立写入：内容与索引里已有条目相同则跳过，
 * 每个失败都记录在案——单个后端故障不能掩盖健康后端的结果。
 *
 * index.json 的读-改-写是"读全量 → 改 → 整体回写"，所以必须串行化：模块安装后的自动备份
 * 跑在脱离页面的协程里，与用户手动备份、与恢复前的回滚点写入都可能重叠。锁按后端 id 取，
 * 且是进程级的（[indexLocks]），因为每个 [com.sukisu.ultra.data.backup.BackupRepository]
 * 都会各自装配一套后端实例。
 */
class BackupEngine(
    private val sources: Map<BackupKind, BackupSource>,
    private val storages: List<BackupStorage>,
    private val retention: Int = 5,
    private val bootRetention: Int = 2,
    private val clock: () -> Instant = { Instant.now() },
) {

    /**
     * [selected] 透传给源：模块用它支持用户勾选，null = 全部；boot 忽略。
     *
     * [onProgress] 按时间节流上报"这次目标已经推出去多少字节"（见 [CountingInputStream]）。
     * 总字节数取源产出的全部归档之和：被去重跳过的那些不会推出去，所以进度条最后可能差一点点，
     * 调用方在目标结束时按"完成"处理。
     */
    suspend fun backup(
        kind: BackupKind,
        selected: Set<String>? = null,
        onProgress: (TransferProgress) -> Unit = {},
    ): BackupRunResult {
        val source = sources[kind]
            ?: return BackupRunResult(
                kind,
                failures = listOf(BackupFailure("", ENGINE_STORAGE, BackupReason.NoSource(kind))),
            )
        val outcome = source.export(selected).getOrElse { error ->
            return BackupRunResult(kind, failures = listOf(BackupFailure("", SOURCE_STORAGE, reasonOf(error))))
        }
        val written = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val totalBytes = outcome.artifacts.sumOf { it.sizeBytes }
        // 已经推完的那几个归档的字节数：进度是"这次目标"的累计，不是单个文件的。
        var sentBefore = 0L
        // 源侧的失败（某个模块没打包成功）同样要报出来，否则用户看到"written N"以为全都备上了。
        val failures = outcome.failures.toMutableList()

        try {
            storages.forEach { storage ->
                withIndexLock(storage.id) {
                    // 索引读不出来就不回写：把"读失败"当成"空索引"会把已有 manifest 整体清掉。
                    val index = readIndex(storage).getOrElse { error ->
                        failures += BackupFailure(ArchiveNaming.INDEX_FILE, storage.id, reasonOf(error))
                        return@withIndexLock
                    }
                    val pending = mutableListOf<BackupEntry>()
                    val usedNames = index.mapTo(mutableSetOf()) { it.fileName }

                    outcome.artifacts.forEach { artifact ->
                        // 回滚点不参与去重：内容只以回滚点形式存在时，正常备份仍应立成业务条目，
                        // 否则那份回滚点被保留策略淘汰后，内容就再没有索引指向它了。
                        val business = (index + pending).filterNot { RollbackPolicy.isRollback(it) }
                        if (DuplicatePolicy.findDuplicate(business, kind, artifact.sha256) != null) {
                            skipped += artifact.fileName
                            return@forEach
                        }
                        val fileName = ArchiveNaming.uniqueName(artifact.fileName, usedNames)
                        usedNames += fileName
                        val bytesBefore = sentBefore
                        val report: (Long) -> Unit = { inFile ->
                            onProgress(TransferProgress(fileName, bytesBefore + inFile, totalBytes))
                        }
                        // 后端自己会报（WebDAV）时不要在源流上再包一层：它先把源流落到 staging、
                        // 再上传 staging 文件，包在源流上数到的是那段本地拷贝，而真上传时后端又
                        // 会按 socket 写出的字节数报一次——两条流混在一起，进度会先冲到底再冻住。
                        storage.put(
                            relativePath = fileName,
                            size = artifact.sizeBytes,
                            open = if (storage.reportsTransferProgress) {
                                artifact.openContent
                            } else {
                                { CountingInputStream(delegate = artifact.openContent(), onProgress = report) }
                            },
                            onProgress = report,
                        )
                            .onFailure { failures += BackupFailure(fileName, storage.id, reasonOf(it)) }
                            .onSuccess {
                                written += fileName
                                sentBefore += artifact.sizeBytes
                                val metaName = artifact.metaFileName?.let { ArchiveNaming.metaFileNameFor(fileName) }
                                if (metaName != null && artifact.metaJson != null) {
                                    storage.writeText(metaName, artifact.metaJson)
                                        .onFailure { failures += BackupFailure(metaName, storage.id, reasonOf(it)) }
                                }
                                pending += BackupEntry(
                                    kind = kind,
                                    entryId = artifact.entryId,
                                    fileName = fileName,
                                    metaFileName = metaName,
                                    sizeBytes = artifact.sizeBytes,
                                    sha256 = artifact.sha256,
                                    createdAt = clock().toString(),
                                )
                            }
                    }

                    val updated = index + pending
                    val expired = RetentionPolicy.expired(updated, kind, RetentionPolicy.keepFor(kind, retention, bootRetention))
                    // 删不掉的条目留在索引里：从索引里摘掉却把文件留在盘上，那份归档就再也没人
                    // 指向它了，下一次备份也不会再试着删——用户看不到、也清不掉的孤儿。
                    val deleted = mutableSetOf<BackupEntry>()
                    expired.forEach { entry ->
                        val archive = storage.delete(entry.fileName)
                        val meta = entry.metaFileName?.let { storage.delete(it) } ?: Result.success(Unit)
                        if (archive.isSuccess && meta.isSuccess) {
                            deleted += entry
                        } else {
                            val error = archive.exceptionOrNull() ?: meta.exceptionOrNull()!!
                            failures += BackupFailure(entry.fileName, storage.id, reasonOf(error))
                        }
                    }
                    val remaining = updated - deleted
                    storage.writeText(ArchiveNaming.INDEX_FILE, BackupManifest.renderEntries(remaining))
                        .onFailure { failures += BackupFailure(ArchiveNaming.INDEX_FILE, storage.id, reasonOf(it)) }
                }
            }
        } finally {
            // 打包落下的临时文件在所有后端都写完之前不能删，所以清理放在这里。
            outcome.artifacts.forEach { it.cleanup?.invoke() }
        }

        return BackupRunResult(kind, written, skipped, failures)
    }

    suspend fun list(storage: BackupStorage, kind: BackupKind): Result<List<BackupEntry>> =
        readIndex(storage).map { entries -> entries.filter { it.kind == kind }.sortedByDescending { it.createdAt } }

    /**
     * 删掉 [entries] 这几条备份。
     *
     * 顺序是先删文件再改索引：删不掉的文件必须留在索引里——从索引里摘掉却把文件留在盘上，
     * 那份归档就再也没人指向它了，用户既看不到也清不掉。索引写不回去时把删掉的名字重新挂上，
     * 否则"文件没了但索引还写着"会让列表点开就是空的。
     */
    suspend fun delete(storage: BackupStorage, entries: List<BackupEntry>): Result<Unit> = runCatching {
        if (entries.isEmpty()) return@runCatching
        withIndexLock(storage.id) {
            val index = readIndex(storage).getOrThrow()
            val targets = index.filter { candidate -> entries.any { it.fileName == candidate.fileName } }
            val removed = mutableListOf<BackupEntry>()
            targets.forEach { entry ->
                val archive = storage.delete(entry.fileName)
                val meta = entry.metaFileName?.let { storage.delete(it) } ?: Result.success(Unit)
                if (archive.isSuccess && meta.isSuccess) {
                    removed += entry
                } else {
                    throw (archive.exceptionOrNull() ?: meta.exceptionOrNull())!!
                }
            }
            storage.writeText(ArchiveNaming.INDEX_FILE, BackupManifest.renderEntries(index - removed.toSet()))
                .getOrThrow()
        }
    }

    /**
     * 恢复 [entry]。
     *
     * [rollbackStorage] 是回滚点落点，默认与被恢复的项同一个后端；云端恢复必须显式传本地后端——
     * 否则每次从云端恢复都会先把当前那一项（boot 时是 32–96MB 的镜像）上传到第三方网盘，
     * 而设计要的是"回滚点留在本地、不上云"。
     */
    suspend fun restore(
        storage: BackupStorage,
        entry: BackupEntry,
        rollbackStorage: BackupStorage = storage,
    ): Result<RestoreOutcome> = runCatching {
        val source = sources.getValue(entry.kind)
        // 恢复回滚点本身就是"回到恢复前的状态"，再给它拍一个回滚点毫无意义，
        // 而且会把正在读取的那一份当成"旧的同类回滚点"删掉，导致这次恢复必然失败。
        if (!RollbackPolicy.isRollback(entry)) {
            snapshotForRollback(rollbackStorage, source, entry)
        }
        val metaJson = entry.metaFileName
            ?.let { name -> storage.readText(name).getOrNull() }
            ?.takeIf { it.isNotBlank() }
        storage.get(entry.fileName).getOrThrow().use { raw ->
            source.restore(entry, metaJson, verify(raw, entry)).getOrThrow()
        }
    }

    /** 索引里记了 sha256 就必须对得上：被截断的归档照常恢复等于把坏内容装回设备。 */
    private fun verify(raw: InputStream, entry: BackupEntry): InputStream =
        if (entry.sha256.isBlank()) raw
        else VerifyingInputStream(raw, entry.sha256, entry.fileName)

    /**
     * 恢复前把"当前那一项"导出成回滚点。
     *
     * 拿不到回滚点只是少了安全网，不该让用户连恢复都做不了——所以这里全程不抛异常。
     * 回滚点不计入业务归档额度，且按"同一项"只留最近 1 份（见 [RollbackPolicy.expiredRollbacks]）。
     */
    private suspend fun snapshotForRollback(rollbackStorage: BackupStorage, source: BackupSource, entry: BackupEntry) {
        val artifact = source.exportOne(entry.entryId).getOrNull() ?: return
        try {
            withIndexLock(rollbackStorage.id) {
                val index = readIndex(rollbackStorage).getOrNull() ?: return@withIndexLock
                val usedNames = index.mapTo(mutableSetOf()) { it.fileName }
                val name = ArchiveNaming.uniqueName(
                    RollbackPolicy.rollbackNameFor(entry, ArchiveNaming.timestamp(clock())),
                    usedNames,
                )
                if (rollbackStorage.put(name, artifact.sizeBytes, open = artifact.openContent).isFailure) return@withIndexLock

                // 边车 meta 要一起留：少了它，用回滚点救回来的模块会丢掉禁用状态。
                val metaName = artifact.metaFileName?.let { ArchiveNaming.metaFileNameFor(name) }
                if (metaName != null && artifact.metaJson != null) {
                    rollbackStorage.writeText(metaName, artifact.metaJson)
                }

                val updated = index + BackupEntry(
                    kind = entry.kind,
                    entryId = entry.entryId,
                    fileName = name,
                    metaFileName = metaName,
                    sizeBytes = artifact.sizeBytes,
                    sha256 = artifact.sha256,
                    createdAt = clock().toString(),
                )
                val expired = RollbackPolicy.expiredRollbacks(updated)
                expired.forEach { old ->
                    rollbackStorage.delete(old.fileName)
                    old.metaFileName?.let { rollbackStorage.delete(it) }
                }
                rollbackStorage.writeText(ArchiveNaming.INDEX_FILE, BackupManifest.renderEntries(updated - expired.toSet()))
            }
        } finally {
            artifact.cleanup?.invoke()
        }
    }

    /** 导出到调用方给的流。sink 必须无论成败都被关闭，否则 SAF 选定的文档会留半截内容。 */
    suspend fun exportTo(storage: BackupStorage, entry: BackupEntry, sink: OutputStream): Result<Unit> = runCatching {
        sink.use { out ->
            storage.get(entry.fileName).getOrThrow().use { content -> content.copyTo(out) }
        }
    }

    /** 把外部来源（SAF 选中的文件）收编进 [storage] 并登记到索引，之后就能像本地备份一样恢复。 */
    suspend fun importFrom(
        storage: BackupStorage,
        kind: BackupKind,
        fileName: String,
        metaFileName: String?,
        size: Long,
        open: () -> InputStream,
        metaJson: String?,
    ): Result<Unit> = runCatching {
        // 导入的文件不能顶着回滚点的前缀：`RollbackPolicy` 是按文件名前缀认回滚点的，
        // 一个恰好叫 pre_restore_*.zip 的用户文件会被当成"同一项的第 2 份回滚点"裁掉，
        // 也会在列表里被标成"恢复点"。
        val requested = ArchiveNaming.safeFileName(fileName)
            .removePrefix(RollbackPolicy.PREFIX)
            .ifBlank { ArchiveNaming.IMPORTED_FALLBACK_NAME }
        val adopted = sources[kind]?.metaForImport(open, size)
        val resolvedMeta = metaJson ?: adopted?.metaJson
        withIndexLock(storage.id) {
            val index = readIndex(storage).getOrThrow()
            val taken = index.filterNot { it.fileName == requested }.mapTo(mutableSetOf()) { it.fileName }
            val finalName = ArchiveNaming.uniqueName(requested, taken)
            storage.put(finalName, size, open = open).getOrThrow()

            val metaName = if (resolvedMeta != null) ArchiveNaming.metaFileNameFor(finalName) else metaFileName
            if (metaName != null && resolvedMeta != null) storage.writeText(metaName, resolvedMeta).getOrThrow()

            val entryId = adopted?.entryId
                ?: metaJson?.let { runCatching { BackupManifest.parseModuleMeta(it).entryId }.getOrNull() }
                    ?.takeIf { it.isNotBlank() }
                ?: finalName
            val entry = BackupEntry(
                kind = kind,
                entryId = entryId,
                fileName = finalName,
                metaFileName = metaName,
                sizeBytes = size,
                // 算不出校验和就不要登记：留个空 sha 等于让恢复全程不做内容校验。
                sha256 = open().use { it.sha256Hex() },
                createdAt = clock().toString(),
            )
            storage.writeText(
                ArchiveNaming.INDEX_FILE,
                BackupManifest.renderEntries(index.filterNot { it.fileName == finalName } + entry),
            ).getOrThrow()
        }
    }

    /**
     * 读索引。文件不存在（后端约定返回空文本）等于空索引；**读不到或解析不了**则返回失败，
     * 调用方必须据此拒绝回写——[readIndex] 的结果会被整体写回去，把失败当空表就是清空 manifest。
     */
    private suspend fun readIndex(storage: BackupStorage): Result<List<BackupEntry>> =
        storage.readText(ArchiveNaming.INDEX_FILE).mapCatching { text ->
            if (text.isBlank()) emptyList() else BackupManifest.parseEntriesOrThrow(text)
        }

    private suspend fun <T> withIndexLock(storageId: String, block: suspend () -> T): T =
        indexLocks.computeIfAbsent(storageId) { Mutex() }.withLock { block() }

    private companion object {
        /** 进程级：同一个后端 id 的索引读-改-写串行化，跨 BackupRepository 实例生效。 */
        val indexLocks = ConcurrentHashMap<String, Mutex>()

        /** 源侧失败的 storage 标记，UI 不把它当成"备份位置"来显示。 */
        const val SOURCE_STORAGE = "source"

        /** 引擎自身的失败（比如没有这个 kind 的源）。 */
        const val ENGINE_STORAGE = "engine"
    }
}
