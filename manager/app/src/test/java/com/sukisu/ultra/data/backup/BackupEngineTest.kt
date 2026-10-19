package com.sukisu.ultra.data.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant

private class FakeStorage(
    override val id: String,
    private val failWrites: Boolean = false,
    private val failIndexRead: Boolean = false,
    private val failDeletes: Boolean = false,
    private val failMetaWrites: Boolean = false,
    private val failIndexWrites: Boolean = false,
    private val failMetaRead: Boolean = false,
    /** 只让某一个路径删不掉：用来构造"删到一半失败"。 */
    var failDeleteOf: String? = null,
    override val reportsTransferProgress: Boolean = false,
) : BackupStorage {
    val files = mutableMapOf<String, ByteArray>()

    override suspend fun test(): Result<Unit> = Result.success(Unit)

    override suspend fun put(
        relativePath: String,
        size: Long,
        onProgress: (Long) -> Unit,
        open: () -> InputStream,
    ): Result<Unit> {
        if (failWrites) return Result.failure(IllegalStateException("$id is down"))
        val bytes = open().use { it.readBytes() }
        files[relativePath] = bytes
        // 会自己报进度的后端（WebDAV 那一类）：按"真的写出去多少"报，引擎不该再包一层源流。
        if (reportsTransferProgress) onProgress(bytes.size.toLong())
        return Result.success(Unit)
    }

    override suspend fun get(relativePath: String) = files[relativePath]
        ?.let { Result.success(ByteArrayInputStream(it) as InputStream) }
        ?: Result.failure(NoSuchElementException(relativePath))

    override suspend fun delete(relativePath: String): Result<Unit> {
        if (failDeletes || relativePath == failDeleteOf) {
            return Result.failure(IllegalStateException("$id is read-only"))
        }
        files.remove(relativePath)
        return Result.success(Unit)
    }

    /** 与真实后端一致：文件不存在等于空文本，读不到才是失败。 */
    override suspend fun readText(relativePath: String): Result<String> {
        if (failIndexRead && relativePath == ArchiveNaming.INDEX_FILE) {
            return Result.failure(BackupReasonException(BackupReason.ReadFailed(relativePath, "$id is down")))
        }
        if (failMetaRead && relativePath.endsWith(".meta.json")) {
            return Result.failure(BackupReasonException(BackupReason.ReadFailed(relativePath, "$id is down")))
        }
        return Result.success(files[relativePath]?.decodeToString() ?: "")
    }

    override suspend fun writeText(relativePath: String, text: String): Result<Unit> {
        if (failIndexWrites && relativePath == ArchiveNaming.INDEX_FILE) {
            return Result.failure(IllegalStateException("$id cannot write index"))
        }
        if (failMetaWrites && relativePath.endsWith(".meta.json")) {
            // 真实后端（LocalBackupStorage / WebDavBackupStorage）写失败时抛的就是这个类型。
            return Result.failure(
                BackupReasonException(BackupReason.WriteFailed(relativePath, "$id cannot write sidecars")),
            )
        }
        files[relativePath] = text.toByteArray()
        return Result.success(Unit)
    }
}

/** 传输途中被用户取消：put 直接把 CancellationException 抛出去，模拟协程取消。 */
private class CancellingStorage : BackupStorage {
    override val id = "cancelling"

    override suspend fun test(): Result<Unit> = Result.success(Unit)

    override suspend fun put(
        relativePath: String,
        size: Long,
        onProgress: (Long) -> Unit,
        open: () -> InputStream,
    ): Result<Unit> = throw CancellationException("left the screen")

    override suspend fun get(relativePath: String): Result<InputStream> =
        Result.failure(NoSuchElementException(relativePath))

    override suspend fun delete(relativePath: String): Result<Unit> = Result.success(Unit)

    override suspend fun readText(relativePath: String): Result<String> = Result.success("")

    override suspend fun writeText(relativePath: String, text: String): Result<Unit> = Result.success(Unit)
}

private class FakeSource(
    override val kind: BackupKind,
    private val artifacts: List<BackupArtifact>,
    private val failures: List<BackupFailure> = emptyList(),
    /** 打包期间要报的几条（真实模块源按模块逐条报）。 */
    private val packReports: List<PackingProgress> = emptyList(),
    /** 让 export 直接失败：用来证明"打包收尾"那条照样会到。 */
    private val exportFails: Boolean = false,
) : BackupSource {
    /** 最近一次 [export] 收到的勾选集合：用来证明它被原样透传给了源。 */
    var lastSelection: Set<String>? = null

    /** 被打包的次数：一次备份写到几处都只该是 1（同一批内容打包一遍）。 */
    var exportCount = 0

    /** 最近一次 [restore] 收到的边车元数据：用来证明"读不到"没有被当成"没有"。 */
    var lastRestoreMeta: String? = null

    override suspend fun export(
        selected: Set<String>?,
        onPacking: (PackingProgress) -> Unit,
    ): Result<ExportOutcome> {
        exportCount++
        lastSelection = selected
        packReports.forEach(onPacking)
        if (exportFails) return Result.failure(IllegalStateException("packaging blew up"))
        return Result.success(ExportOutcome(artifacts, failures))
    }

    override suspend fun restore(entry: BackupEntry, metaJson: String?, content: InputStream): Result<RestoreOutcome> {
        lastRestoreMeta = metaJson
        // 真实源都会把内容读到底（落盘再交给 ksud），校验也发生在读到末尾时。
        content.use { it.readBytes() }
        return Result.success(RestoreOutcome(true))
    }
}

private fun artifact(
    id: String,
    sha: String,
    body: String = "body-$id",
    cleanup: (() -> Unit)? = null,
) = BackupArtifact(
    kind = BackupKind.MODULE,
    entryId = id,
    fileName = "module_${id}_1_20261008_120000.zip",
    metaFileName = "module_${id}_1_20261008_120000.zip.meta.json",
    sizeBytes = body.length.toLong(),
    sha256 = sha,
    metaJson = "{}",
    openContent = { body.byteInputStream() },
    cleanup = cleanup,
)

class BackupEngineTest {

    private fun engine(
        storages: List<BackupStorage>,
        source: BackupSource,
        retention: Int = 5,
    ) = BackupEngine(
        sources = mapOf(source.kind to source),
        storages = storages,
        retention = retention,
        clock = { Instant.parse("2026-10-08T12:00:00Z") },
    )

    private fun moduleSource(vararg artifacts: BackupArtifact) =
        FakeSource(BackupKind.MODULE, artifacts.toList())

    private fun entry(entryId: String = "a", sha256: String = "s", size: Long = 7L) = BackupEntry(
        kind = BackupKind.MODULE,
        entryId = entryId,
        fileName = "module_${entryId}_1_20261008_120000.zip",
        metaFileName = null,
        sizeBytes = size,
        sha256 = sha256,
        createdAt = "2026-10-08T00:00:00Z",
    )

    @Test
    fun `backup writes archive and meta and updates the index`() {
        runBlocking {
            val storage = FakeStorage("local")
            val result = engine(listOf(storage), moduleSource(artifact("a", "sha-a"))).backup(BackupKind.MODULE)

            assertTrue(result.isSuccess)
            assertEquals(listOf("module_a_1_20261008_120000.zip"), result.written)
            assertTrue(storage.files.containsKey("module_a_1_20261008_120000.zip"))
            assertTrue(storage.files.containsKey("module_a_1_20261008_120000.zip.meta.json"))
            assertEquals(
                listOf("a"),
                BackupManifest.parseEntries(storage.files.getValue("index.json").decodeToString()).map { it.entryId }
            )
        }
    }

    @Test
    fun `delete removes the archive, its meta and the index entry`() {
        runBlocking {
            val storage = FakeStorage("local")
            val engine = engine(listOf(storage), moduleSource(artifact("a", "sha-a"), artifact("b", "sha-b")))
            engine.backup(BackupKind.MODULE)
            val listed = engine.list(storage, BackupKind.MODULE).getOrThrow()
            val doomed = listed.first { it.entryId == "a" }

            engine.delete(storage, listOf(doomed)).getOrThrow()

            assertFalse(storage.files.containsKey(doomed.fileName))
            assertFalse(storage.files.containsKey(doomed.metaFileName!!))
            assertEquals(
                listOf("b"),
                BackupManifest.parseEntries(storage.files.getValue("index.json").decodeToString()).map { it.entryId }
            )
        }
    }

    /** 索引写失败时不会触碰归档；用户仍能从原列表恢复或重试删除。 */
    @Test
    fun `a failed delete index commit leaves the archive and original manifest intact`() {
        runBlocking {
            val storage = FakeStorage("local", failIndexWrites = true)
            val engine = engine(listOf(storage), moduleSource(artifact("a", "sha-a")))
            val stored = entry("a").copy(metaFileName = "module_a_1_20261008_120000.zip.meta.json")
            val before = BackupManifest.renderEntries(listOf(stored))
            storage.files[ArchiveNaming.INDEX_FILE] = before.toByteArray()
            storage.files[stored.fileName] = "payload-a".toByteArray()
            storage.files[stored.metaFileName!!] = "{}".toByteArray()

            assertTrue(engine.delete(storage, listOf(stored)).isFailure)
            assertTrue(storage.files.containsKey(stored.fileName))
            assertTrue(storage.files.containsKey(stored.metaFileName))
            assertEquals(before, storage.files.getValue(ArchiveNaming.INDEX_FILE).decodeToString())
        }
    }

    /** 删不掉的文件必须留在索引里，否则那份归档再也没人指向它，用户既看不到也清不掉。 */
    @Test
    fun `a failed delete leaves both the file and its index entry in place`() {
        runBlocking {
            val storage = FakeStorage("local", failDeletes = true)
            val engine = engine(listOf(storage), moduleSource(artifact("a", "sha-a")))
            engine.backup(BackupKind.MODULE)
            val doomed = engine.list(storage, BackupKind.MODULE).getOrThrow().single()

            assertTrue(engine.delete(storage, listOf(doomed)).isFailure)
            assertEquals(
                listOf("a"),
                BackupManifest.parseEntries(storage.files.getValue("index.json").decodeToString()).map { it.entryId }
            )
        }
    }

    /**
     * 只删掉一部分时索引必须和盘上剩下来的东西对上：已经删掉的不能还留在列表里
     * （点开恢复只会失败、再删也删不掉），没删掉的必须留着让用户重试。
     */
    @Test
    fun `delete keeps the manifest in step with the archives it could actually remove`() {
        runBlocking {
            val storage = FakeStorage("local")
            val engine = engine(listOf(storage), moduleSource(artifact("a", "sha-a"), artifact("b", "sha-b")))
            engine.backup(BackupKind.MODULE)
            val listed = engine.list(storage, BackupKind.MODULE).getOrThrow()
            val a = listed.first { it.entryId == "a" }
            val b = listed.first { it.entryId == "b" }
            storage.failDeleteOf = b.fileName

            assertTrue(engine.delete(storage, listOf(a, b)).isFailure)

            assertFalse(storage.files.containsKey(a.fileName))
            assertTrue(storage.files.containsKey(b.fileName))
            assertEquals(
                listOf("b"),
                BackupManifest.parseEntries(storage.files.getValue(ArchiveNaming.INDEX_FILE).decodeToString())
                    .map { it.entryId },
            )
        }
    }

    /** 声明的尺寸就超限：连读都不该读，更不该在盘上留下任何东西。 */
    @Test
    fun `import refuses an archive whose declared size is over the limit`() {
        runBlocking {
            val storage = FakeStorage("local")
            val engine = engine(listOf(storage), moduleSource())
            val name = "module_big_1_20261008_120000.zip"

            val result = engine.importFrom(
                storage = storage,
                kind = BackupKind.MODULE,
                fileName = name,
                metaFileName = null,
                size = BackupLimits.MAX_IMPORT_BYTES + 1,
                open = { ByteArrayInputStream("payload".toByteArray()) },
                metaJson = null,
            )

            assertEquals(
                BackupReason.ImportTooLarge(name, BackupLimits.MAX_IMPORT_BYTES),
                (result.exceptionOrNull() as BackupReasonException).reason,
            )
            assertTrue(storage.files.isEmpty())
        }
    }

    /** 归档写进去了、索引却提交失败：不能留下"盘上有、列表里没有"的半条。 */
    @Test
    fun `import cleans up what it wrote when the index cannot be committed`() {
        runBlocking {
            val storage = FakeStorage("local", failIndexWrites = true)
            val engine = engine(listOf(storage), moduleSource())
            val body = "payload-b".toByteArray()

            val result = engine.importFrom(
                storage = storage,
                kind = BackupKind.MODULE,
                fileName = "module_b_1_20261008_120000.zip",
                metaFileName = null,
                size = body.size.toLong(),
                open = { ByteArrayInputStream(body) },
                metaJson = "{}",
            )

            assertTrue((result.exceptionOrNull() as? BackupReasonException)?.reason is BackupReason.ImportIndexWriteFailed)
            assertTrue(storage.files.isEmpty())
        }
    }

    /**
     * 边车 meta 读不到（网络断、权限没了）时不能装作"这份备份没存状态"：那会把模块以启用状态
     * 装回去、和备份时不一样，而界面上还写着"已恢复"。
     */
    @Test
    fun `restore fails instead of silently dropping unreadable sidecar meta`() {
        runBlocking {
            val storage = FakeStorage("local", failMetaRead = true)
            val stored = entry("a", sha256 = "").copy(metaFileName = "module_a_1_20261008_120000.zip.meta.json")
            storage.files[ArchiveNaming.INDEX_FILE] = BackupManifest.renderEntries(listOf(stored)).toByteArray()
            storage.files[stored.fileName] = "body-a".toByteArray()

            assertTrue(engine(listOf(storage), FakeSource(BackupKind.MODULE, emptyList())).restore(storage, stored).isFailure)
        }
    }

    /** 边车 meta 本来就不存在（旧备份、导入的归档）按"没有状态"处理，恢复照常进行。 */
    @Test
    fun `restore treats a missing sidecar meta as no recorded state`() {
        runBlocking {
            val storage = FakeStorage("local")
            val source = FakeSource(BackupKind.MODULE, emptyList())
            val stored = entry("a", sha256 = "").copy(metaFileName = "module_a_1_20261008_120000.zip.meta.json")
            storage.files[ArchiveNaming.INDEX_FILE] = BackupManifest.renderEntries(listOf(stored)).toByteArray()
            storage.files[stored.fileName] = "body-a".toByteArray()

            assertTrue(engine(listOf(storage), source).restore(storage, stored).getOrThrow().success)
            assertNull(source.lastRestoreMeta)
        }
    }

    /** 导入拷贝按字节封顶：provider 声称的大小靠不住，流本身超了就必须当场停下。 */
    @Test
    fun `the import copy stops at the limit instead of writing past it`() {
        runBlocking {
            val out = ByteArrayOutputStream()

            val error = runCatching {
                ByteArrayInputStream(ByteArray(100) { 1 }).copyBoundedTo(out, limit = 64, path = "big.zip")
            }.exceptionOrNull()

            assertEquals(
                BackupReason.ImportTooLarge("big.zip", 64),
                (error as BackupReasonException).reason,
            )
            assertTrue(out.size() <= 64)
        }
    }

    @Test
    fun `the import copy passes through content that fits the limit`() {
        runBlocking {
            val out = ByteArrayOutputStream()

            val copied = ByteArrayInputStream(ByteArray(64) { 7 }).copyBoundedTo(out, limit = 64, path = "ok.zip")

            assertEquals(64L, copied)
            assertEquals(64, out.size())
        }
    }

    @Test
    fun `the engine passes the module selection through to the source`() {
        runBlocking {
            val source = FakeSource(BackupKind.MODULE, listOf(artifact("a", "sha-a")))
            engine(listOf(FakeStorage("local")), source).backup(BackupKind.MODULE, setOf("a", "b"))

            assertEquals(setOf("a", "b"), source.lastSelection)
        }
    }

    @Test
    fun `backup reports how many bytes have gone out and how many are left`() {
        runBlocking {
            val a = artifact("a", "sha-a")
            val b = artifact("b", "sha-b")
            val seen = mutableListOf<TransferProgress>()

            engine(listOf(FakeStorage("local")), moduleSource(a, b)).backup(BackupKind.MODULE, onProgress = { seen += it })

            // 每个归档各自从头数，累计值挂在引擎上——所以推给界面的字节数必须单调不减，
            // 否则进度条会往回跳。
            assertTrue(seen.isNotEmpty())
            assertEquals(seen.map { it.sentBytes }.sorted(), seen.map { it.sentBytes })
            // 最后一次上报必须是"全都推完了"，进度条才会走到底（而不是停在 99%）。
            val total = a.sizeBytes + b.sizeBytes
            assertEquals(total, seen.last().sentBytes)
            assertEquals(total, seen.last().totalBytes)
        }
    }

    @Test
    fun `a storage that reports progress itself is not counted twice`() {
        runBlocking {
            val a = artifact("a", "sha-a")
            val b = artifact("b", "sha-b")
            val seen = mutableListOf<TransferProgress>()

            engine(listOf(FakeStorage("webdav", reportsTransferProgress = true)), moduleSource(a, b))
                .backup(BackupKind.MODULE, onProgress = { seen += it })

            // 每个文件只该有一条上报（来自后端自己），累计值正好落在"这个文件写完"的位置。
            // 引擎若在源流上又包一层，每个文件会多出一条，累计值也就不是这两个点了。
            assertEquals(listOf(a.sizeBytes, a.sizeBytes + b.sizeBytes), seen.map { it.sentBytes })
            assertEquals(a.sizeBytes + b.sizeBytes, seen.last().totalBytes)
        }
    }

    @Test
    fun `the last chunk of a big file is reported too`() {
        runBlocking {
            // 大文件会被切成很多块，节流会把最后一块吞掉——不补那一次，进度条就停在 99%。
            val big = artifact("a", "sha-a", body = "x".repeat(200 * 1024))
            val seen = mutableListOf<TransferProgress>()

            engine(listOf(FakeStorage("local")), moduleSource(big))
                .backup(BackupKind.MODULE, onProgress = { seen += it })

            assertEquals(big.sizeBytes, seen.last().sentBytes)
            assertEquals(big.sizeBytes, seen.last().totalBytes)
        }
    }

    @Test
    fun `progress says which archive of how many is going out`() {
        runBlocking {
            // 一次 11 个模块的备份只报字节数，用户看不出还剩几个文件，会把目标数（1/1）当成模块数。
            val a = artifact("a", "sha-a")
            val b = artifact("b", "sha-b")
            val seen = mutableListOf<TransferProgress>()

            engine(listOf(FakeStorage("local")), moduleSource(a, b)).backup(BackupKind.MODULE, onProgress = { seen += it })

            assertEquals(2, seen.last().fileCount)
            // 第一个归档的上报都是 1/2，第二个都是 2/2——不会出现 0 或超过总数。
            assertTrue(seen.filter { it.sentBytes <= a.sizeBytes }.all { it.fileIndex == 1 })
            assertTrue(seen.filter { it.sentBytes > a.sizeBytes }.all { it.fileIndex == 2 })
        }
    }

    /**
     * 打包那一段原来一条上报都没有：界面停在"0 B / 0 B"的进度条上，看着跟卡死一样，
     * 而它常常是整个备份里最慢的一段（11 个模块要先打成 zip）。
     */
    @Test
    fun `packing progress is reported and closed before the first byte goes out`() {
        runBlocking {
            val order = mutableListOf<String>()
            val source = FakeSource(
                BackupKind.MODULE,
                listOf(artifact("a", "sha-a")),
                packReports = listOf(PackingProgress(1, 2), PackingProgress(2, 2)),
            )

            engine(listOf(FakeStorage("local")), source).backup(
                BackupKind.MODULE,
                onProgress = { order += "transfer" },
                onPacking = { order += if (it.finished) "packing-done" else "packing-${it.index}/${it.total}" },
            )

            val firstTransfer = order.indexOf("transfer")
            assertTrue("打包必须在写第一个字节之前报", firstTransfer > 0)
            // 先逐条报打包、再收尾，然后才是字节进度；顺序反了界面会先画字节条再倒回"正在打包"。
            assertEquals(listOf("packing-1/2", "packing-2/2", "packing-done"), order.take(firstTransfer))
            assertTrue(
                "打包收尾之后不该再有打包上报，否则进度条会从字节进度跳回不确定进度",
                order.drop(firstTransfer).none { it.startsWith("packing") },
            )
        }
    }

    /** 源一条都没报（勾选的模块全被卸载了）时，收尾那条也得有，否则界面一直挂在"正在打包"。 */
    @Test
    fun `packing is closed even when the source reports nothing`() {
        runBlocking {
            val seen = mutableListOf<PackingProgress>()

            engine(listOf(FakeStorage("local")), moduleSource(artifact("a", "sha-a")))
                .backup(BackupKind.MODULE, onPacking = { seen += it })

            assertEquals(listOf(PackingProgress(0, 0, finished = true)), seen)
        }
    }

    /** 打包失败同样要收尾：这次备份已经结束了，界面不该还停"正在打包"。 */
    @Test
    fun `packing is closed even when the export fails`() {
        runBlocking {
            val seen = mutableListOf<PackingProgress>()
            val source = FakeSource(
                BackupKind.MODULE,
                emptyList(),
                packReports = listOf(PackingProgress(1, 3)),
                exportFails = true,
            )

            val result = engine(listOf(FakeStorage("local")), source)
                .backup(BackupKind.MODULE, onPacking = { seen += it })

            assertTrue(result.isFailure)
            assertEquals(listOf(PackingProgress(1, 3), PackingProgress(0, 0, finished = true)), seen)
        }
    }

    @Test
    fun `no selection means every entry the source has`() {
        runBlocking {
            val source = FakeSource(BackupKind.MODULE, listOf(artifact("a", "sha-a")))
            engine(listOf(FakeStorage("local")), source).backup(BackupKind.MODULE)

            // null 而不是空集合：空集合是"一个都不要"，两者不能混。
            assertNull(source.lastSelection)
        }
    }

    @Test
    fun `identical content is skipped on the second run`() {
        runBlocking {
            val storage = FakeStorage("local")
            val engine = engine(listOf(storage), moduleSource(artifact("a", "sha-a")))
            engine.backup(BackupKind.MODULE)

            val second = engine.backup(BackupKind.MODULE)

            assertTrue(second.isSuccess)
            assertEquals(listOf("module_a_1_20261008_120000.zip"), second.skipped)
            assertTrue(second.written.isEmpty())
        }
    }

    @Test
    fun `one failing storage does not stop the others`() {
        runBlocking {
            val broken = FakeStorage("webdav", failWrites = true)
            val good = FakeStorage("local")
            val result = engine(listOf(broken, good), moduleSource(artifact("a", "sha-a"))).backup(BackupKind.MODULE)

            assertTrue(result.isPartial)
            assertEquals(listOf("module_a_1_20261008_120000.zip"), result.written)
            assertEquals("webdav", result.failures.single().storage)
        }
    }

    /**
     * 一次备份写到两处只打包一遍。
     *
     * 把 11 个模块打成 zip、算 sha256 是这次备份里最贵的一段（几十 MB），按来源各跑一遍就是白烧
     * 的 CPU 和电量。引擎一次 [BackupEngine.backup] 只导出一次源，这条把它钉住。
     */
    @Test
    fun `two storages share one packing pass`() {
        runBlocking {
            val local = FakeStorage("local")
            val cloud = FakeStorage("webdav")
            val source = moduleSource(artifact("a", "sha-a"))
            val name = "module_a_1_20261008_120000.zip"

            val result = engine(listOf(local, cloud), source).backup(BackupKind.MODULE)

            assertEquals("同一批内容只该被打包一次", 1, source.exportCount)
            // 两处都要有那份归档：省掉的那一遍是打包，不是写入。
            assertTrue(local.files.containsKey(name))
            assertTrue(cloud.files.containsKey(name))
            assertEquals(2, result.storages.size)
        }
    }

    /**
     * 按来源报数必须看各自那一份明细。
     *
     * 整体计数是"写了两份"（同一批内容两个后端各写一次），这正是结果表不能拿它当某一侧成绩的
     * 原因：本地那一格会写着 2 项，而它其实只写了 1 项。
     */
    @Test
    fun `each backend keeps its own tally`() {
        runBlocking {
            val name = "module_a_1_20261008_120000.zip"
            val result = engine(listOf(FakeStorage("local"), FakeStorage("webdav")), moduleSource(artifact("a", "sha-a")))
                .backup(BackupKind.MODULE)

            assertEquals(listOf(name, name), result.written)
            assertEquals(listOf(name), result.forStorage("local").written)
            assertEquals(listOf(name), result.forStorage("webdav").written)
            // 切完的结果里不该再挂着两份明细，否则调用方会拿着半份结果再切一次。
            assertTrue(result.forStorage("local").storages.isEmpty())
        }
    }

    /**
     * 跳过是按后端算的，不是按整次备份算的。
     *
     * 用户后开云端时就是这个样子：本机那份早就有了，云端第一次收。结果表要写"本机 已是最新 ·
     * 云端 已备份 1 项"，而不是两侧都写"已是最新"。
     */
    @Test
    fun `a duplicate is skipped only on the backend that already has it`() {
        runBlocking {
            val a = artifact("a", "sha-a")
            val local = FakeStorage("local")
            val cloud = FakeStorage("webdav")
            val name = "module_a_1_20261008_120000.zip"

            // 第一次只有本机收下；第二次才把云端加上。
            engine(listOf(local), moduleSource(a)).backup(BackupKind.MODULE)
            val second = engine(listOf(local, cloud), moduleSource(a)).backup(BackupKind.MODULE)

            assertEquals(listOf(name), second.forStorage("local").skipped)
            assertTrue(second.forStorage("webdav").skipped.isEmpty())
            assertEquals(listOf(name), second.forStorage("webdav").written)
        }
    }

    /**
     * 源侧的失败不属于任何一个后端，却会让每一份都不完整——所以它出现在每一份里。
     *
     * 只报本地那一行、让云端那一行看着没事，就把"这个模块根本没打出来"藏起来了。
     */
    @Test
    fun `a source level failure shows up in every backend's slice`() {
        runBlocking {
            val source = FakeSource(
                BackupKind.MODULE,
                listOf(artifact("a", "sha-a")),
                failures = listOf(
                    BackupFailure("module_b.zip", "source", BackupReason.WriteFailed("module_b.zip", null)),
                ),
            )
            val result = engine(listOf(FakeStorage("local"), FakeStorage("webdav")), source).backup(BackupKind.MODULE)

            assertTrue(result.forStorage("local").failures.any { it.path == "module_b.zip" })
            assertTrue(result.forStorage("webdav").failures.any { it.path == "module_b.zip" })
        }
    }

    /**
     * 总字节是"这一批 × 后端数"。
     *
     * 只按一批算的话，第二个后端一开始写，进度条就已经贴在 100% 上了（已传字节是跨后端累计的），
     * 用户会以为传完了、然后在原地等。
     */
    @Test
    fun `total bytes span every backend`() {
        runBlocking {
            val a = artifact("a", "sha-a")
            val b = artifact("b", "sha-b")
            val seen = mutableListOf<TransferProgress>()

            engine(listOf(FakeStorage("local"), FakeStorage("webdav")), moduleSource(a, b))
                .backup(BackupKind.MODULE, onProgress = { seen += it })

            val perPass = a.sizeBytes + b.sizeBytes
            assertEquals(perPass * 2, seen.last().totalBytes)
            assertEquals(perPass * 2, seen.last().sentBytes)
        }
    }

    /** 字节数是跨后端累计的、不回头，所以"现在在写哪一处"必须单独报出来。 */
    @Test
    fun `progress says which backend is being written`() {
        runBlocking {
            val seen = mutableListOf<TransferProgress>()

            engine(listOf(FakeStorage("local"), FakeStorage("webdav")), moduleSource(artifact("a", "sha-a")))
                .backup(BackupKind.MODULE, onProgress = { seen += it })

            assertEquals(listOf("local", "webdav"), seen.map { it.storage }.distinct())
            assertEquals(listOf(1, 2), seen.map { it.storageIndex }.distinct())
            assertTrue(seen.all { it.storageCount == 2 })
        }
    }

    @Test
    fun `an unreadable index is reported and never overwritten`() {
        runBlocking {
            val existing = BackupManifest.renderEntries(
                listOf(BackupEntry(BackupKind.MODULE, "old", "module_old_1_x.zip", null, 1, "s1", "2026-10-01T00:00:00Z"))
            )
            val storage = FakeStorage("local", failIndexRead = true)
            storage.files[ArchiveNaming.INDEX_FILE] = existing.toByteArray()

            val result = engine(listOf(storage), moduleSource(artifact("a", "sha-a"))).backup(BackupKind.MODULE)

            assertTrue(result.failures.any { it.reason is BackupReason.ReadFailed })
            assertEquals(
                "a failed index read must not wipe the manifest",
                existing,
                storage.files.getValue(ArchiveNaming.INDEX_FILE).decodeToString(),
            )
        }
    }

    @Test
    fun `an index that cannot be parsed is reported and never overwritten`() {
        runBlocking {
            val garbage = "{ this is not a manifest"
            val storage = FakeStorage("local")
            storage.files[ArchiveNaming.INDEX_FILE] = garbage.toByteArray()

            val result = engine(listOf(storage), moduleSource(artifact("a", "sha-a"))).backup(BackupKind.MODULE)

            assertTrue(result.failures.any { it.reason is BackupReason.IndexUnreadable })
            assertEquals(
                "treating a broken manifest as empty would clear the user's list",
                garbage,
                storage.files.getValue(ArchiveNaming.INDEX_FILE).decodeToString(),
            )
        }
    }

    @Test
    fun `a source level failure is surfaced in the run result`() {
        runBlocking {
            val storage = FakeStorage("local")
            val source = FakeSource(
                BackupKind.MODULE,
                listOf(artifact("b", "sha-b")),
                failures = listOf(BackupFailure(path = "a", storage = "source", reason = BackupReason.ArchiveFailed("a", "boom"))),
            )

            val result = engine(listOf(storage), source).backup(BackupKind.MODULE)

            assertTrue(result.isPartial)
            assertEquals(listOf("module_b_1_20261008_120000.zip"), result.written)
            assertEquals("a", result.failures.single().path)
        }
    }

    @Test
    fun `a colliding archive name gets a disambiguating suffix`() {
        runBlocking {
            val storage = FakeStorage("local")
            storage.files["module_a_1_20261008_120000.zip"] = "already-there".toByteArray()
            storage.files[ArchiveNaming.INDEX_FILE] = BackupManifest.renderEntries(
                listOf(BackupEntry(BackupKind.MODULE, "a", "module_a_1_20261008_120000.zip", null, 12, "old-sha", "2026-10-08T11:00:00Z"))
            ).toByteArray()

            val result = engine(listOf(storage), moduleSource(artifact("a", "new-sha"))).backup(BackupKind.MODULE)

            assertEquals(listOf("module_a_1_20261008_120000_2.zip"), result.written)
            assertEquals("already-there", storage.files.getValue("module_a_1_20261008_120000.zip").decodeToString())
        }
    }

    @Test
    fun `packaging temporaries are cleaned up after the backup`() {
        runBlocking {
            var cleaned = false
            val result = engine(
                listOf(FakeStorage("local")),
                moduleSource(artifact("a", "sha-a", cleanup = { cleaned = true })),
            ).backup(BackupKind.MODULE)

            assertTrue(result.isSuccess)
            assertTrue("a module zip must not stay in cache after the backup", cleaned)
        }
    }

    @Test
    fun `retention deletes the oldest archives beyond the keep count`() {
        runBlocking {
            val storage = FakeStorage("local")
            // 预置两份旧备份，索引里带不同 sha，避免被去重跳过
            storage.files[ArchiveNaming.INDEX_FILE] = BackupManifest.renderEntries(
                listOf(
                    BackupEntry(BackupKind.MODULE, "old1", "module_old1_1_20261001_000000.zip", null, 1, "s1", "2026-10-01T00:00:00Z"),
                    BackupEntry(BackupKind.MODULE, "old2", "module_old2_1_20261002_000000.zip", null, 1, "s2", "2026-10-02T00:00:00Z"),
                )
            ).toByteArray()
            storage.files["module_old1_1_20261001_000000.zip"] = "x".toByteArray()
            storage.files["module_old2_1_20261002_000000.zip"] = "y".toByteArray()

            engine(listOf(storage), moduleSource(artifact("new", "s3")), retention = 2).backup(BackupKind.MODULE)

            assertFalse(storage.files.containsKey("module_old1_1_20261001_000000.zip"))
            assertTrue(storage.files.containsKey("module_old2_1_20261002_000000.zip"))
            assertTrue(storage.files.containsKey("module_new_1_20261008_120000.zip"))
        }
    }

    @Test
    fun `list returns only the requested kind`() {
        runBlocking {
            val storage = FakeStorage("local")
            storage.files[ArchiveNaming.INDEX_FILE] = BackupManifest.renderEntries(
                listOf(
                    BackupEntry(BackupKind.MODULE, "m", "module_m_1_x.zip", null, 1, "s", "2026-10-08T00:00:00Z"),
                    BackupEntry(BackupKind.PARTITION, "b", "partition_boot_a_x.img", null, 1, "s", "2026-10-08T00:00:00Z"),
                )
            ).toByteArray()

            val listed = engine(listOf(storage), moduleSource())
                .list(storage, BackupKind.MODULE).getOrThrow()

            assertEquals(listOf("m"), listed.map { it.entryId })
        }
    }

    @Test
    fun `list on a storage without an index returns empty`() {
        runBlocking {
            val storage = FakeStorage("local")
            assertEquals(
                emptyList<BackupEntry>(),
                engine(listOf(storage), moduleSource()).list(storage, BackupKind.MODULE).getOrThrow(),
            )
        }
    }

    @Test
    fun `exportTo streams the stored archive into the sink`() {
        runBlocking {
            val storage = FakeStorage("local")
            storage.files["module_a_1_20261008_120000.zip"] = "hello".toByteArray()
            val sink = java.io.ByteArrayOutputStream()

            engine(listOf(storage), moduleSource())
                .exportTo(storage, entry(sha256 = ""), sink).getOrThrow()

            assertEquals("hello", sink.toString())
        }
    }

    @Test
    fun `exportTo closes the sink even when the read fails`() {
        runBlocking {
            val storage = FakeStorage("local")
            var closed = false
            val sink = object : java.io.OutputStream() {
                override fun write(b: Int) = Unit
                override fun close() {
                    closed = true
                }
            }

            val result = engine(listOf(storage), moduleSource()).exportTo(storage, entry(sha256 = ""), sink)

            assertTrue(result.isFailure)
            assertTrue("the SAF output stream must not leak", closed)
        }
    }

    @Test
    fun `restore verifies the recorded sha256 and succeeds when it matches`() {
        runBlocking {
            val storage = FakeStorage("local")
            val body = "payload".toByteArray()
            storage.files["module_a_1_20261008_120000.zip"] = body

            val outcome = engine(listOf(storage), moduleSource(artifact("a", "sha-a")))
                .restore(storage, entry(sha256 = body.sha256Hex(), size = body.size.toLong()))
                .getOrThrow()

            assertTrue(outcome.success)
        }
    }

    @Test
    fun `restore refuses an archive whose content does not match the recorded sha256`() {
        runBlocking {
            val storage = FakeStorage("local")
            storage.files["module_a_1_20261008_120000.zip"] = "tampered".toByteArray()

            val result = engine(listOf(storage), moduleSource(artifact("a", "sha-a")))
                .restore(storage, entry(sha256 = "expected-sha"))

            assertTrue(result.isFailure)
            val reason = reasonOf(result.exceptionOrNull()!!)
            assertEquals(BackupReason.Corrupted("module_a_1_20261008_120000.zip", "expected-sha", "tampered".toByteArray().sha256Hex()), reason)
        }
    }

    @Test
    fun `importFrom copies a foreign archive into the storage and the index`() {
        runBlocking {
            val storage = FakeStorage("local")
            val engine = engine(listOf(storage), moduleSource())
            val body = "foreign".toByteArray()

            engine.importFrom(
                storage = storage,
                kind = BackupKind.MODULE,
                fileName = "module_imported_1_20261008_130000.zip",
                metaFileName = "module_imported_1_20261008_130000.zip.meta.json",
                size = body.size.toLong(),
                open = { body.inputStream() },
                metaJson = "{\"entryId\":\"imported\"}",
            ).getOrThrow()

            assertTrue(storage.files.containsKey("module_imported_1_20261008_130000.zip"))
            assertEquals(
                listOf("imported"),
                BackupManifest.parseEntries(storage.files.getValue(ArchiveNaming.INDEX_FILE).decodeToString()).map { it.entryId }
            )
        }
    }

    @Test
    fun `importFrom refuses content that is already stored`() {
        runBlocking {
            val storage = FakeStorage("local")
            val body = "foreign".toByteArray()
            val engine = engine(listOf(storage), moduleSource())

            engine.importFrom(
                storage = storage,
                kind = BackupKind.MODULE,
                fileName = "module_imported_1_20261008_130000.zip",
                metaFileName = null,
                size = body.size.toLong(),
                open = { body.inputStream() },
                metaJson = null,
            ).getOrThrow()

            // 同一个文件再导一次：内容一样，名字换了也一样该被拒——不然列表里多出一行、
            // 云端还要为同一份内容再传一遍。
            val second = engine.importFrom(
                storage = storage,
                kind = BackupKind.MODULE,
                fileName = "module_imported_1_20261008_140000.zip",
                metaFileName = null,
                size = body.size.toLong(),
                open = { body.inputStream() },
                metaJson = null,
            )

            val reason = reasonOf(second.exceptionOrNull() ?: error("第二次导入应当被拒绝"))
            assertTrue(reason is BackupReason.DuplicateContent)
            assertEquals(
                "module_imported_1_20261008_130000.zip",
                (reason as BackupReason.DuplicateContent).existing,
            )
            // 第二份没落盘，索引里也还是那一条。
            assertFalse(storage.files.containsKey("module_imported_1_20261008_140000.zip"))
            assertEquals(
                1,
                BackupManifest.parseEntries(storage.files.getValue(ArchiveNaming.INDEX_FILE).decodeToString()).size,
            )
        }
    }

    @Test
    fun `importFrom flattens a hostile file name`() {
        runBlocking {
            val storage = FakeStorage("local")
            val body = "foreign".toByteArray()

            engine(listOf(storage), moduleSource()).importFrom(
                storage = storage,
                kind = BackupKind.MODULE,
                fileName = "../../etc/passwd",
                metaFileName = null,
                size = body.size.toLong(),
                open = { body.inputStream() },
                metaJson = null,
            ).getOrThrow()

            assertTrue(storage.files.containsKey("passwd"))
        }
    }

    @Test
    fun `a sidecar that cannot be written fails the entry and keeps it out of the index`() {
        runBlocking {
            // 归档写成功、meta 写失败时条目以前照样进索引：之后 restore 拿不到 meta，列表里
            // 那一行点开就报错，保留策略还会把它当一份完整备份数。要么两个都在，要么都不算。
            val storage = FakeStorage("local", failMetaWrites = true)

            val result = engine(listOf(storage), moduleSource(artifact("a", "sha-a"))).backup(BackupKind.MODULE)

            assertTrue(result.isFailure)
            assertTrue(result.written.isEmpty())
            val failure = result.failures.single()
            val reason = failure.reason
            assertEquals("module_a_1_20261008_120000.zip.meta.json", failure.path)
            assertEquals("local", failure.storage)
            assertTrue("expected WriteFailed but got $reason", reason is BackupReason.WriteFailed)
            assertFalse(
                "只剩归档没有 meta 的半份备份不该留在盘上",
                storage.files.containsKey("module_a_1_20261008_120000.zip"),
            )
            assertEquals(
                emptyList<String>(),
                BackupManifest
                    .parseEntries(storage.files.getValue(ArchiveNaming.INDEX_FILE).decodeToString())
                    .map { it.entryId },
            )
        }
    }

    @Test
    fun `a sidecar failure also reports the archive that could not be cleaned up`() {
        runBlocking {
            // 归档删不掉也得说一声：索引里没有它，盘上却还占着地方，只报一句"meta 写失败"
            // 会让用户以为什么都没留下。
            val storage = FakeStorage("local", failDeletes = true, failMetaWrites = true)

            val result = engine(listOf(storage), moduleSource(artifact("a", "sha-a"))).backup(BackupKind.MODULE)

            assertEquals(2, result.failures.size)
            assertTrue(
                "expected the sidecar itself to be reported, got ${result.failures.map { it.path }}",
                result.failures.any { it.path == "module_a_1_20261008_120000.zip.meta.json" },
            )
            assertTrue(
                "expected the orphaned archive to be reported, got ${result.failures.map { it.path }}",
                result.failures.any { it.path == "module_a_1_20261008_120000.zip" },
            )
        }
    }

    @Test
    fun `a cancelled transfer is not folded into a failed result`() {
        // 取消不是失败：吞掉协程的取消信号后，上层（退出页面、切换存储、换配置）再也收不到
        // 取消，用户看到的却是列表里多出一条"备份失败"。
        val subject = engine(listOf(CancellingStorage()), moduleSource(artifact("a", "sha-a")))
        var result: BackupRunResult? = null
        var cancelled = false

        try {
            runBlocking { result = subject.backup(BackupKind.MODULE) }
        } catch (expected: CancellationException) {
            cancelled = true
        }

        assertTrue("cancellation must reach the caller", cancelled)
        assertNull(result)
    }

    @Test
    fun `exportTo verifies the archive when a sha256 is recorded`() {
        runBlocking {
            val storage = FakeStorage("local")
            val body = "payload".toByteArray()
            storage.files["module_a_1_20261008_120000.zip"] = body
            val sink = java.io.ByteArrayOutputStream()

            engine(listOf(storage), moduleSource())
                .exportTo(storage, entry(sha256 = body.sha256Hex(), size = body.size.toLong()), sink)
                .getOrThrow()

            assertEquals("payload", sink.toString())
        }
    }

    @Test
    fun `exportTo refuses an archive whose content does not match the recorded sha256`() {
        runBlocking {
            // 导出（分享、另存）曾经是唯一不过校验的出口：文件在盘上/传输路上坏了照样交给用户。
            val storage = FakeStorage("local")
            storage.files["module_a_1_20261008_120000.zip"] = "tampered".toByteArray()
            val sink = java.io.ByteArrayOutputStream()

            val result = engine(listOf(storage), moduleSource())
                .exportTo(storage, entry(sha256 = "expected-sha"), sink)

            assertTrue(result.isFailure)
            assertEquals(
                BackupReason.Corrupted(
                    "module_a_1_20261008_120000.zip",
                    "expected-sha",
                    "tampered".toByteArray().sha256Hex(),
                ),
                reasonOf(result.exceptionOrNull()!!),
            )
        }
    }

    @Test
    fun `copyTo verifies without closing the sink it was given`() {
        runBlocking {
            // 多选分享时几条归档要写进同一个 zip：谁都不许替调用方关流。
            val storage = FakeStorage("local")
            val body = "payload".toByteArray()
            storage.files["module_a_1_20261008_120000.zip"] = body
            var closed = false
            val sink = object : java.io.OutputStream() {
                private val out = java.io.ByteArrayOutputStream()
                override fun write(b: Int) = out.write(b)
                override fun close() {
                    closed = true
                }
                override fun toString() = out.toString()
            }

            engine(listOf(storage), moduleSource())
                .copyTo(storage, entry(sha256 = body.sha256Hex(), size = body.size.toLong()), sink)
                .getOrThrow()

            assertEquals("payload", sink.toString())
            assertFalse("copyTo 不该关掉调用方的 sink", closed)
        }
    }

}
