package com.sukisu.ultra.data.backup

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * 跑在脱离页面的协程里，与用户手动备份、手动删除都可能重叠。锁按后端 id 取，
 * 且是进程级的（[indexLocks]），因为每个 [com.sukisu.ultra.data.backup.BackupRepository]
 * 都会各自装配一套后端实例。
 */
class BackupEngine(
    private val sources: Map<BackupKind, BackupSource>,
    private val storages: List<BackupStorage>,
    private val retention: Int = 5,
    private val partitionRetention: Int = 2,
    private val clock: () -> Instant = { Instant.now() },
) {

    /**
     * [selected] 透传给源：模块用它支持用户勾选，null = 全部；boot 忽略。
     *
     * [onProgress] 按时间节流上报"这次目标已经推出去多少字节"（见 [CountingInputStream]）。
     * 总字节数取源产出的全部归档之和：被去重跳过的那些不会推出去，所以进度条最后可能差一点点，
     * 调用方在目标结束时按"完成"处理。
     *
     * [onPacking] 是打包阶段的进度（见 [PackingProgress]）：字节进度要到打包完才存在，而打包
     * 本身往往是整个备份里最慢的一段，不报这一段界面就只能在 0 上干等。收尾那条由这里补，
     * 不指望源记得——一个归档都没产出时源没有下一条可报。
     */
    suspend fun backup(
        kind: BackupKind,
        selected: Set<String>? = null,
        onProgress: (TransferProgress) -> Unit = {},
        onPacking: (PackingProgress) -> Unit = {},
    ): BackupRunResult {
        val source = sources[kind]
            ?: return BackupRunResult(
                kind,
                failures = listOf(BackupFailure("", ENGINE_STORAGE, BackupReason.NoSource(kind))),
            )
        val outcome = try {
            source.export(selected, onPacking).getOrElse { error ->
                return BackupRunResult(kind, failures = listOf(BackupFailure("", SOURCE_STORAGE, reasonOf(error))))
            }
        } finally {
            // 打包结束（含失败）：界面据此切回按字节的进度，否则"正在打包"会一直挂在那儿。
            onPacking(PackingProgress(index = 0, total = 0, finished = true))
        }
        val written = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        // 同一批内容要写到每一个后端，所以总字节是"这一批 × 后端数"：只按一批算的话，第二个后端
        // 一开始写，进度条就已经贴在 100% 上了（sentBefore 本来就是跨后端累计的）。
        val totalBytes = outcome.artifacts.sumOf { it.sizeBytes } * storages.size
        // 已经推完的那几个归档的字节数：进度是"这次目标"的累计，不是单个文件的，也不分后端。
        var sentBefore = 0L
        // 源侧的失败（某个模块没打包成功）同样要报出来，否则用户看到"written N"以为全都备上了。
        // 它不属于任何一个后端，所以留在整体里、不进任何一份按后端的明细。
        val sourceFailures = outcome.failures.toMutableList()
        val perStorage = mutableListOf<StorageOutcome>()

        try {
            storages.forEachIndexed { storageIndex, storage ->
                // 按后端各记一份：整体计数在两侧都写成功时会翻倍，而结果表要按来源报数。
                val storageWritten = mutableListOf<String>()
                val storageSkipped = mutableListOf<String>()
                val storageFailures = mutableListOf<BackupFailure>()
                withIndexLock(storage.id) {
                    // 索引读不出来就不回写：把"读失败"当成"空索引"会把已有 manifest 整体清掉。
                    val index = readIndex(storage).getOrElse { error ->
                        storageFailures += BackupFailure(ArchiveNaming.INDEX_FILE, storage.id, reasonOf(error))
                        return@withIndexLock
                    }
                    val pending = mutableListOf<BackupEntry>()
                    val usedNames = index.mapTo(mutableSetOf()) { it.fileName }

                    outcome.artifacts.forEachIndexed { artifactIndex, artifact ->
                        if (DuplicatePolicy.findDuplicate(index + pending, kind, artifact.sha256) != null) {
                            storageSkipped += artifact.fileName
                            return@forEachIndexed
                        }
                        val fileName = ArchiveNaming.uniqueName(artifact.fileName, usedNames)
                        usedNames += fileName
                        val bytesBefore = sentBefore
                        val report: (Long) -> Unit = { inFile ->
                            onProgress(
                                TransferProgress(
                                    fileName = fileName,
                                    sentBytes = bytesBefore + inFile,
                                    totalBytes = totalBytes,
                                    // 第几个归档：11 个模块的备份只报字节数，看不出还剩几个文件。
                                    fileIndex = artifactIndex + 1,
                                    fileCount = outcome.artifacts.size,
                                    // 字节数是跨后端累计的、不回头，所以"现在在写哪一处"必须单独报。
                                    storage = storage.id,
                                    storageIndex = storageIndex + 1,
                                    storageCount = storages.size,
                                ),
                            )
                        }
                        // 后端自己会报（WebDAV）时不要在源流上再包一层：它先把源流落到 staging、
                        // 再上传 staging 文件，包在源流上数到的是那段本地拷贝，而真上传时后端又
                        // 会按 socket 写出的字节数报一次——两条流混在一起，进度会先冲到底再冻住。
                        val put = storage.put(
                            relativePath = fileName,
                            size = artifact.sizeBytes,
                            open = if (storage.reportsTransferProgress) {
                                artifact.openContent
                            } else {
                                { CountingInputStream(delegate = artifact.openContent(), onProgress = report) }
                            },
                            onProgress = report,
                        )
                        val putFailure = put.exceptionOrNull()
                        if (putFailure != null) {
                            storageFailures += BackupFailure(fileName, storage.id, reasonOf(putFailure))
                            return@forEachIndexed
                        }
                        // 字节确实出去了，进度基准就往前走；失败的那次不动，与老行为一致。
                        sentBefore += artifact.sizeBytes
                        val metaName = artifact.metaFileName?.let { ArchiveNaming.metaFileNameFor(fileName) }
                        if (metaName != null && artifact.metaJson != null) {
                            val metaFailure = storage.writeText(metaName, artifact.metaJson).exceptionOrNull()
                            if (metaFailure != null) {
                                // 归档和边车 meta 是一份备份的两半：meta（模块名/版本、启动镜像的 sha1）
                                // 写不进去，这条就整体算失败，绝不能登记进索引——登记了等于给用户一条
                                // "已备份"却必然恢复失败的记录。归档已经写进去了，这里尽力删掉，删不掉
                                // 也照实报出来；索引里不留半条，下次备份会用同名文件把它覆盖掉。
                                storageFailures += BackupFailure(metaName, storage.id, reasonOf(metaFailure))
                                storage.delete(fileName).onFailure { cleanup ->
                                    storageFailures += BackupFailure(fileName, storage.id, reasonOf(cleanup))
                                }
                                return@forEachIndexed
                            }
                        }
                        storageWritten += fileName
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

                    val updated = index + pending
                    val expired = RetentionPolicy.expired(
                        existing = updated,
                        kind = kind,
                        keep = RetentionPolicy.keepFor(kind, retention, partitionRetention),
                        // 刚写进去的这些不许被同一次调用删掉：设备时钟被调过时，"最近"会算到旧
                        // 备份头上，那样这次备份就是删掉自己。
                        keepEntries = pending.mapTo(mutableSetOf()) { it.fileName },
                    )
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
                            storageFailures += BackupFailure(entry.fileName, storage.id, reasonOf(error))
                        }
                    }
                    val remaining = updated - deleted
                    storage.writeText(ArchiveNaming.INDEX_FILE, BackupManifest.renderEntries(remaining))
                        .onFailure { storageFailures += BackupFailure(ArchiveNaming.INDEX_FILE, storage.id, reasonOf(it)) }
                }
                written += storageWritten
                skipped += storageSkipped
                perStorage += StorageOutcome(storage.id, storageWritten, storageSkipped, storageFailures)
            }
        } finally {
            // 打包落下的临时文件在所有后端都写完之前不能删，所以清理放在这里。
            outcome.artifacts.forEach { it.cleanup?.invoke() }
        }

        return BackupRunResult(
            kind = kind,
            written = written,
            skipped = skipped,
            failures = sourceFailures + perStorage.flatMap { it.failures },
            storages = perStorage,
        )
    }

    suspend fun list(storage: BackupStorage, kind: BackupKind): Result<List<BackupEntry>> =
        readIndex(storage).map { entries -> entries.filter { it.kind == kind }.sortedByDescending { it.createdAt } }

    /**
     * 删掉 [entries] 这几条备份。
     *
     * 索引和盘上的文件必须始终对得上，否则列表里会出现"点开恢复必失败、再删也删不掉"的死条目。
     * 所以顺序是**先提交不含目标项的索引，再删文件**：索引写不进去时什么都还没动，用户看到原
     * 列表，重试即可。文件删到一半失败（某项被占用、后端只读）时，把已经删掉的那些从索引里
     * 摘掉、没删掉的留在索引里——已经不在盘上的条目留在列表里只会变成一个永远失败的死条目。
     */
    suspend fun delete(storage: BackupStorage, entries: List<BackupEntry>): Result<Unit> = runCatchingCancellable {
        if (entries.isEmpty()) return@runCatchingCancellable
        withIndexLock(storage.id) {
            val index = readIndex(storage).getOrThrow()
            val targets = index.filter { candidate -> entries.any { it.fileName == candidate.fileName } }
            if (targets.isEmpty()) return@withIndexLock
            storage.writeText(ArchiveNaming.INDEX_FILE, BackupManifest.renderEntries(index - targets.toSet()))
                .getOrThrow()

            // 真的从盘上消失了的那些。归档删掉了、边车 meta 没删掉也算：索引指向归档，
            // 留着一个没有归档的条目比留下一个没人引用的 meta 文件糟得多。
            val deleted = mutableListOf<BackupEntry>()
            var failure: Throwable? = null
            targets.forEach { entry ->
                var archiveGone = false
                try {
                    storage.delete(entry.fileName).getOrThrow()
                    archiveGone = true
                    entry.metaFileName?.let { storage.delete(it).getOrThrow() }
                    deleted += entry
                } catch (error: Throwable) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    if (archiveGone) deleted += entry
                    if (failure == null) failure = error
                }
            }
            // 抓成 val：failure 是被上面的 lambda 改过的 var，智能转换在它身上不成立。
            val primary = failure
            if (primary != null) {
                // 把索引拉回"盘上实际剩下来的东西"：这次删成功的摘掉，失败的留着让用户重试。
                // 回滚本身失败也不改变主因，只挂成 suppressed。
                try {
                    storage.writeText(
                        ArchiveNaming.INDEX_FILE,
                        BackupManifest.renderEntries(index - deleted.toSet()),
                    ).getOrThrow()
                } catch (rollback: Throwable) {
                    if (rollback is kotlinx.coroutines.CancellationException) throw rollback
                    primary.addSuppressed(rollback)
                }
                throw primary
            }
        }
    }

    /** 恢复 [entry]。 */
    suspend fun restore(
        storage: BackupStorage,
        entry: BackupEntry,
    ): Result<RestoreOutcome> = runCatchingCancellable {
        // 没有对应源就当场报"这类内容没有来源"，而不是让 Map.getValue 抛一句 NoSuchElementException：
        // 那句话既不像原因，也没法被 BackupText 翻译成用户看得懂的话。
        val source = sources[entry.kind]
            ?: throw BackupReasonException(BackupReason.NoSource(entry.kind))
        // 边车元数据读不到有两种，必须分开：文件本来就没有（后端约定返回空文本）＝这份备份没存
        // 状态，按"没有"处理；文件在却读失败（网络断、权限没了）＝不能装作没有，否则模块会以
        // 启用状态装回去、和备份时不一样，而界面上还显示"已恢复"。
        val metaJson = entry.metaFileName
            ?.let { name -> storage.readText(name).getOrThrow() }
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
     * 导出到调用方给的流。sink 必须无论成败都被关闭，否则 SAF 选定的文档会留半截内容。
     *
     * 和 [restore] 一样过 [verify]：导出去的东西会被分享到别的设备/别的工具手上，索引里记了
     * sha256 就必须对得上——送出被截断的归档，对方要到安装或刷机时才炸，那时已经查不出源在哪。
     */
    suspend fun exportTo(storage: BackupStorage, entry: BackupEntry, sink: OutputStream): Result<Unit> =
        runCatchingCancellable {
            sink.use { out -> copyTo(storage, entry, out).getOrThrow() }
        }

    /**
     * 把 [entry] 的内容（校验通过后）写进 [sink]，**不关** sink。
     *
     * 分享多选是打包成一个 zip，流要跨多个条目复用，只有 [exportTo] 这种"一次性 sink"才该被关闭。
     * 校验与 [exportTo] 完全一样，别绕过这里直接 `storage.get().copyTo(...)`——那条路没有校验。
     */
    suspend fun copyTo(storage: BackupStorage, entry: BackupEntry, sink: OutputStream): Result<Unit> =
        runCatchingCancellable {
            storage.get(entry.fileName).getOrThrow().use { content ->
                verify(content, entry).copyTo(sink)
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
    ): Result<Unit> = runCatchingCancellable {
        val requested = ArchiveNaming.safeFileName(fileName)
            .ifBlank { ArchiveNaming.IMPORTED_FALLBACK_NAME }
        if (size > BackupLimits.MAX_IMPORT_BYTES) {
            throw BackupReasonException(BackupReason.ImportTooLarge(requested, BackupLimits.MAX_IMPORT_BYTES))
        }
        val adopted = sources[kind]?.metaForImport(requested, open, size)
        val resolvedMeta = metaJson ?: adopted?.metaJson
        // 先算校验和，再进索引锁：同一个文件被导两次只该收一份，而"是不是同一份内容"只能靠它
        // 判断（文件名可以改、大小可以一样）。同时再次限制实际流长度，不信任 SAF 声明的 size。
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var actualSize = 0L
        open().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (actualSize > BackupLimits.MAX_IMPORT_BYTES - count) {
                    throw BackupReasonException(BackupReason.ImportTooLarge(requested, BackupLimits.MAX_IMPORT_BYTES))
                }
                actualSize += count
                digest.update(buffer, 0, count)
            }
        }
        if (actualSize != size) throw BackupReasonException(BackupReason.FileUnreadable)
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        withIndexLock(storage.id) {
            val index = readIndex(storage).getOrThrow()
            DuplicatePolicy.findDuplicate(index, kind, sha256)?.let { existing ->
                throw BackupReasonException(BackupReason.DuplicateContent(existing.fileName))
            }
            val taken = index.filterNot { it.fileName == requested }.mapTo(mutableSetOf()) { it.fileName }
            val finalName = ArchiveNaming.uniqueName(requested, taken)
            storage.put(finalName, size, open = open).getOrThrow()

            val metaName = if (resolvedMeta != null) ArchiveNaming.metaFileNameFor(finalName) else metaFileName
            try {
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
                    sha256 = sha256,
                    createdAt = clock().toString(),
                )
                storage.writeText(
                    ArchiveNaming.INDEX_FILE,
                    BackupManifest.renderEntries(index.filterNot { it.fileName == finalName } + entry),
                ).getOrElse { error ->
                    throw BackupReasonException(BackupReason.ImportIndexWriteFailed(externalTextOrNull(error)))
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                // 回滚是尽力而为：清不掉只作为附加信息挂在主因下面。让清理的异常顶掉主因，
                // 用户看到的就是"删除失败"，而真正的原因（索引写不进去）反而没提。
                // 只清真的写过的那两个：meta 名是调用方给的，没写就不该去删同名文件。
                if (metaName != null && resolvedMeta != null) cleanupQuietly(storage, metaName, error)
                cleanupQuietly(storage, finalName, error)
                throw error
            }
        }
    }

    /**
     * 读索引。文件不存在（后端约定返回空文本）等于空索引；**读不到或解析不了**则返回失败，
     * 调用方必须据此拒绝回写——[readIndex] 的结果会被整体写回去，把失败当空表就是清空 manifest。
     */
    private suspend fun readIndex(storage: BackupStorage): Result<List<BackupEntry>> {
        val text = storage.readText(ArchiveNaming.INDEX_FILE)
            .getOrElse { error -> return Result.failure(error) }
        if (text.isBlank()) return Result.success(emptyList())
        // 解析本身是纯同步的，仍走 runCatchingCancellable：mapCatching/runCatching 会把取消也收成
        // "索引读失败"，而取消必须原样上抛（理由见 runCatchingCancellable）。
        return runCatchingCancellable { BackupManifest.parseEntriesOrThrow(text) }
    }

    private suspend fun <T> withIndexLock(storageId: String, block: suspend () -> T): T =
        indexLocks.computeIfAbsent(storageId) { Mutex() }.withLock { block() }

    /** 尽力删掉一个半成品文件：删不掉不改变 [primary]，只把它记进 suppressed。 */
    private suspend fun cleanupQuietly(storage: BackupStorage, path: String, primary: Throwable) {
        try {
            storage.delete(path).getOrThrow()
        } catch (cleanup: Throwable) {
            if (cleanup is kotlinx.coroutines.CancellationException) throw cleanup
            primary.addSuppressed(cleanup)
        }
    }

    private companion object {
        /** 进程级：同一个后端 id 的索引读-改-写串行化，跨 BackupRepository 实例生效。 */
        val indexLocks = ConcurrentHashMap<String, Mutex>()

        /** 源侧失败的 storage 标记，UI 不把它当成"备份位置"来显示。 */
        const val SOURCE_STORAGE = "source"

        /** 引擎自身的失败（比如没有这个 kind 的源）。 */
        const val ENGINE_STORAGE = "engine"
    }
}
