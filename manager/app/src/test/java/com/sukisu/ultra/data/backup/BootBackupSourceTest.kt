package com.sukisu.ultra.data.backup

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

private class FakeBootRootFiles(private val entries: Map<String, ByteArray>) : RootFiles {
    val restored = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    override fun mkdirs(path: String) = true
    override fun copyTo(from: File, toPath: String): Boolean {
        restored += toPath; return true
    }
    override fun copyFrom(path: String, to: File): Boolean {
        val bytes = entries[path] ?: return false
        to.writeBytes(bytes); return true
    }
    override fun list(dir: String) = entries.keys.map { RootFileEntry(it, entries.getValue(it).size.toLong(), 0L) }
    override fun delete(path: String): Boolean {
        deleted += path; return true
    }
    override fun exists(path: String) = entries.containsKey(path)
}

class BootBackupSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val sha1 = "9f2c8a1b4e6d00112233445566778899aabbccdd"
    private val now = Instant.parse("2026-10-08T12:00:00Z")

    private fun source(root: FakeBootRootFiles, restorer: BootRestorer = NoopBootRestorer) =
        BootBackupSource(root, restorer, { now }, StagingArea(temp.newFolder()))

    /** ksud 只按 cpio 里的 sha1 找文件、不校验内容，所以边车 meta 是恢复时唯一的依据。 */
    private fun meta(sha1: String, body: ByteArray) = JSONObject().apply {
        put("sha1", sha1)
        put("sha256", body.sha256Hex())
        put("sizeBytes", body.size.toLong())
    }.toString()

    private fun bootEntry() = BackupEntry(
        kind = BackupKind.BOOT,
        entryId = sha1,
        fileName = "boot_9f2c8a1b4e6d_20261008_120000.img",
        metaFileName = null,
        sizeBytes = 9L,
        sha256 = "img-bytes".toByteArray().sha256Hex(),
        createdAt = "2026-10-08T00:00:00Z",
    )

    /**
     * 恢复必须把身份与内容绑死，所以测试里的 entryId 不能随手编——真机上 ksud 留下的文件名
     * 就是原厂镜像内容算出来的 sha1。这里从内容反推身份，与 [BootBackupSource.metaForImport] 一致。
     */
    private fun identityOf(body: ByteArray) = body.inputStream().use { it.sha1Hex() }

    /** 真机上一份可恢复的 boot 备份必然伴随 ksud 留下的 `ksu_backup_<sha1>`。 */
    private fun deviceWithStockImage(body: ByteArray) =
        FakeBootRootFiles(mapOf("/data/adb/ksu/ksu_backup_${identityOf(body)}" to body))

    @Test
    fun `export lists existing stock image backups`() {
        runBlocking {
            val root = FakeBootRootFiles(mapOf("/data/adb/ksu/ksu_backup_$sha1" to "img-bytes".toByteArray()))

            val artifacts = source(root).export().getOrThrow().artifacts

            assertEquals("boot_9f2c8a1b4e6d_20261008_120000.img", artifacts.single().fileName)
            assertEquals(sha1, artifacts.single().entryId)
            assertEquals("img-bytes".toByteArray().sha256Hex(), artifacts.single().sha256)
        }
    }

    @Test
    fun `files that are not stock image backups are ignored`() {
        runBlocking {
            val root = FakeBootRootFiles(
                mapOf(
                    "/data/adb/ksu/ksu_backup_$sha1" to "img".toByteArray(),
                    "/data/adb/ksu/sukisu.log" to "log".toByteArray(),
                )
            )
            assertEquals(1, source(root).export().getOrThrow().artifacts.size)
        }
    }

    @Test
    fun `export is empty when no stock image backup exists`() {
        runBlocking {
            assertTrue(source(FakeBootRootFiles(emptyMap())).export().getOrThrow().artifacts.isEmpty())
        }
    }

    @Test
    fun `export cleans up the staged image after the artifact is consumed`() {
        runBlocking {
            val root = FakeBootRootFiles(mapOf("/data/adb/ksu/ksu_backup_$sha1" to "img-bytes".toByteArray()))
            val artifact = source(root).export().getOrThrow().artifacts.single()
            assertTrue(artifact.openContent().use { it.readBytes() }.isNotEmpty())

            artifact.cleanup?.invoke()

            assertTrue(
                "a 96MB boot image must not stay in cache after the backup",
                temp.root.walkTopDown().none { it.isFile && it.name.startsWith("boot-export-") },
            )
        }
    }

    @Test
    fun `restore puts the image back and asks ksud to restore`() {
        runBlocking {
            val body = "img-bytes".toByteArray()
            val id = identityOf(body)
            val root = deviceWithStockImage(body)
            var restoredCalls = 0
            val bootSource = source(root) { restoredCalls++; Result.success(Unit) }

            val outcome = bootSource
                .restore(bootEntry().copy(entryId = id), meta(id, body), body.inputStream())
                .getOrThrow()

            assertTrue(outcome.reason?.toString(), outcome.success)
            assertEquals("/data/adb/ksu/ksu_backup_$id", root.restored.single())
            assertEquals(1, restoredCalls)
        }
    }

    @Test
    fun `restore refuses an archive with no sidecar meta`() {
        runBlocking {
            val root = FakeBootRootFiles(emptyMap())
            val body = "img-bytes".toByteArray()

            val outcome = source(root).restore(bootEntry(), null, body.inputStream()).getOrThrow()

            assertFalse(outcome.success)
            assertTrue(root.restored.isEmpty())
        }
    }

    @Test
    fun `restore refuses an archive that belongs to another stock image`() {
        runBlocking {
            val root = FakeBootRootFiles(emptyMap())
            val body = "img-bytes".toByteArray()
            val other = "00000000000000112233445566778899aabbccdd"

            val outcome = source(root).restore(bootEntry(), meta(other, body), body.inputStream()).getOrThrow()

            assertFalse(outcome.success)
            assertTrue(outcome.reason is BackupReason.BootForeignStockImage)
            assertTrue(root.restored.isEmpty())
        }
    }

    @Test
    fun `restore refuses when this device has no stock image with that sha1`() {
        runBlocking {
            val body = "img-bytes".toByteArray()
            val id = identityOf(body)

            // 真机上没有这张原厂镜像时，ksud 只打印一行 Warning，转而 rebuild_without_ksu 并退出 0，
            // 也就是"报成功但什么都没恢复"。所以这个前置条件必须由我们拒绝。
            val outcome = source(FakeBootRootFiles(emptyMap()))
                .restore(bootEntry().copy(entryId = id), meta(id, body), body.inputStream())
                .getOrThrow()

            assertFalse(outcome.success)
            assertTrue(outcome.reason is BackupReason.BootStockImageMissing)
        }
    }

    @Test
    fun `restore refuses content that does not hash to its recorded identity`() {
        runBlocking {
            val body = "img-bytes".toByteArray()
            // meta 与 entryId 自洽、sha256 也与内容一致，只有内容算出的 sha1 对不上身份——
            // 这正是"伪造 meta + index 让任意镜像过关"的形状，必须被拒。
            val forged = "9f2c8a1b4e6d00112233445566778899aabbccdd"
            val root = FakeBootRootFiles(mapOf("/data/adb/ksu/ksu_backup_$forged" to "other".toByteArray()))

            val outcome = source(root)
                .restore(bootEntry().copy(entryId = forged), meta(forged, body), body.inputStream())
                .getOrThrow()

            assertFalse(outcome.success)
            assertTrue(outcome.reason is BackupReason.BootIdentityMismatch)
            assertTrue(root.restored.isEmpty())
        }
    }

    @Test
    fun `restore refuses an archive whose content does not match the sidecar checksum`() {
        runBlocking {
            val body = "img-bytes".toByteArray()
            val id = identityOf(body)
            val root = deviceWithStockImage(body)
            val wrongMeta = JSONObject().apply {
                put("sha1", id)
                put("sha256", "deadbeef")
                put("sizeBytes", body.size.toLong())
            }.toString()

            val outcome = source(root)
                .restore(bootEntry().copy(entryId = id), wrongMeta, body.inputStream())
                .getOrThrow()

            assertFalse(outcome.success)
            assertTrue(outcome.reason is BackupReason.Corrupted)
            assertTrue(root.restored.isEmpty())
        }
    }

    @Test
    fun `restore reports the ksud error and cleans up the image it placed`() {
        runBlocking {
            val body = "img-bytes".toByteArray()
            val id = identityOf(body)
            val root = deviceWithStockImage(body)
            val bootSource = source(root) { Result.failure(IllegalStateException("no matching backup")) }

            val outcome = bootSource
                .restore(bootEntry().copy(entryId = id), meta(id, body), body.inputStream())
                .getOrThrow()

            assertFalse(outcome.success)
            // ksud 的 stderr 就是失败原因，原样带出来当第三方细节。
            val reason = outcome.reason
            assertTrue(reason is BackupReason.BootFlashFailed)
            assertEquals("no matching backup", (reason as BackupReason.BootFlashFailed).external)
            assertEquals(listOf("/data/adb/ksu/ksu_backup_$id"), root.deleted)
        }
    }

    @Test
    fun `metaForImport derives the identity from the archive content`() {
        runBlocking {
            val body = "stock-image".toByteArray()

            val imported = source(FakeBootRootFiles(emptyMap()))
                .metaForImport({ body.inputStream() }, body.size.toLong())

            assertNotNull(imported)
            assertEquals(body.inputStream().use { it.sha1Hex() }, imported!!.entryId)
            assertEquals(body.sha256Hex(), JSONObject(imported.metaJson).getString("sha256"))
        }
    }

    private companion object {
        val NoopBootRestorer = object : BootRestorer {
            override suspend fun restoreStockImage() = Result.success(Unit)
        }
    }
}
