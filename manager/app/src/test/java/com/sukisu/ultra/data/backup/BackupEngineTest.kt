package com.sukisu.ultra.data.backup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant

private class FakeStorage(
    override val id: String,
    private val failWrites: Boolean = false,
    private val failIndexRead: Boolean = false,
    private val failDeletes: Boolean = false,
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
        if (failDeletes) return Result.failure(IllegalStateException("$id is read-only"))
        files.remove(relativePath)
        return Result.success(Unit)
    }

    /** 与真实后端一致：文件不存在等于空文本，读不到才是失败。 */
    override suspend fun readText(relativePath: String): Result<String> {
        if (failIndexRead && relativePath == ArchiveNaming.INDEX_FILE) {
            return Result.failure(BackupReasonException(BackupReason.ReadFailed(relativePath, "$id is down")))
        }
        return Result.success(files[relativePath]?.decodeToString() ?: "")
    }

    override suspend fun writeText(relativePath: String, text: String) =
        Result.success(Unit).also { files[relativePath] = text.toByteArray() }
}

private class FakeSource(
    override val kind: BackupKind,
    private val artifacts: List<BackupArtifact>,
    private val failures: List<BackupFailure> = emptyList(),
) : BackupSource {
    /** 记录调用顺序：恢复前拍回滚点这件事只能靠顺序证明。 */
    val calls = mutableListOf<String>()

    /** 最近一次 [export] 收到的勾选集合：用来证明它被原样透传给了源。 */
    var lastSelection: Set<String>? = null

    override suspend fun export(selected: Set<String>?): Result<ExportOutcome> {
        lastSelection = selected
        return Result.success(ExportOutcome(artifacts, failures))
    }

    override suspend fun exportOne(entryId: String): Result<BackupArtifact?> {
        calls += "exportOne"
        return Result.success(artifacts.firstOrNull { it.entryId == entryId })
    }

    override suspend fun restore(entry: BackupEntry, metaJson: String?, content: InputStream): Result<RestoreOutcome> {
        calls += "restore"
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
                    BackupEntry(BackupKind.BOOT, "b", "boot_abcdef123456_x.img", null, 1, "s", "2026-10-08T00:00:00Z"),
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
    fun `restore snapshots a rollback point before restoring`() {
        runBlocking {
            val storage = FakeStorage("local")
            val current = "current".toByteArray()
            storage.files["module_a_1_20261008_120000.zip"] = current
            val source = moduleSource(artifact("a", "sha-a"))

            engine(listOf(storage), source)
                .restore(storage, entry(sha256 = current.sha256Hex(), size = current.size.toLong()))
                .getOrThrow()

            assertEquals(listOf("exportOne", "restore"), source.calls)
            val rollback = BackupManifest.parseEntries(storage.files.getValue(ArchiveNaming.INDEX_FILE).decodeToString()).single()
            assertTrue(rollback.fileName.startsWith(RollbackPolicy.PREFIX))
            assertTrue(storage.files.containsKey(rollback.fileName))
        }
    }

    @Test
    fun `the rollback point keeps the sidecar meta so disabled state survives`() {
        runBlocking {
            val storage = FakeStorage("local")
            val current = "current".toByteArray()
            storage.files["module_a_1_20261008_120000.zip"] = current

            engine(listOf(storage), moduleSource(artifact("a", "sha-a")))
                .restore(storage, entry(sha256 = current.sha256Hex(), size = current.size.toLong()))
                .getOrThrow()

            val rollback = BackupManifest.parseEntries(storage.files.getValue(ArchiveNaming.INDEX_FILE).decodeToString()).single()
            assertEquals(
                "pre_restore_20261008_120000_module_a_1_20261008_120000.zip.meta.json",
                rollback.metaFileName,
            )
            assertTrue(storage.files.containsKey(rollback.metaFileName!!))
        }
    }

    @Test
    fun `restoring from the cloud keeps the rollback point local`() {
        runBlocking {
            val cloud = FakeStorage("webdav")
            val local = FakeStorage("local")
            val current = "cloud-current".toByteArray()
            cloud.files["module_a_1_20261008_120000.zip"] = current

            engine(listOf(cloud), moduleSource(artifact("a", "sha-a")))
                .restore(cloud, entry(sha256 = current.sha256Hex(), size = current.size.toLong()), rollbackStorage = local)
                .getOrThrow()

            assertFalse(
                "the rollback point must not be uploaded to a third-party cloud",
                cloud.files.containsKey(ArchiveNaming.INDEX_FILE),
            )
            val rollback = BackupManifest.parseEntries(local.files.getValue(ArchiveNaming.INDEX_FILE).decodeToString()).single()
            assertTrue(rollback.fileName.startsWith(RollbackPolicy.PREFIX))
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
    fun `importFrom still accepts content that only exists as a rollback point`() {
        runBlocking {
            val storage = FakeStorage("local")
            val body = "foreign".toByteArray()
            val sha = body.inputStream().use { it.sha256Hex() }
            // 回滚点会被按"同一项留 1 份"裁掉，所以"内容只以回滚点存在"时，导入的这一份才是
            // 用户自己能指着的那份——和 backup 里那条"回滚点不参与去重"的规矩一致。
            storage.files[ArchiveNaming.INDEX_FILE] = BackupManifest.renderEntries(
                listOf(
                    BackupEntry(
                        kind = BackupKind.MODULE,
                        entryId = "imported",
                        fileName = "${RollbackPolicy.PREFIX}module_imported_1_20261008_120000.zip",
                        metaFileName = null,
                        sizeBytes = body.size.toLong(),
                        sha256 = sha,
                        createdAt = "2026-10-08T12:00:00Z",
                    ),
                ),
            ).encodeToByteArray()

            engine(listOf(storage), moduleSource()).importFrom(
                storage = storage,
                kind = BackupKind.MODULE,
                fileName = "module_imported_1_20261008_130000.zip",
                metaFileName = null,
                size = body.size.toLong(),
                open = { body.inputStream() },
                metaJson = null,
            ).getOrThrow()

            assertTrue(storage.files.containsKey("module_imported_1_20261008_130000.zip"))
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
    fun `importFrom strips a rollback prefix so the entry is not treated as a restore point`() {
        runBlocking {
            val storage = FakeStorage("local")
            val body = "foreign".toByteArray()

            engine(listOf(storage), moduleSource()).importFrom(
                storage = storage,
                kind = BackupKind.MODULE,
                fileName = "${RollbackPolicy.PREFIX}module_x_1_20261008_130000.zip",
                metaFileName = null,
                size = body.size.toLong(),
                open = { body.inputStream() },
                metaJson = null,
            ).getOrThrow()

            val entry = BackupManifest
                .parseEntries(storage.files.getValue(ArchiveNaming.INDEX_FILE).decodeToString())
                .single()
            assertEquals("module_x_1_20261008_130000.zip", entry.fileName)
            // 留着前缀就等于把用户导入的文件交给回滚点的保留策略去裁（每项只留 1 份）。
            assertFalse(RollbackPolicy.isRollback(entry))
        }
    }
}
