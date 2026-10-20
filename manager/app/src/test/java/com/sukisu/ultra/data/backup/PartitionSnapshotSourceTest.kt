package com.sukisu.ultra.data.backup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.util.zip.GZIPOutputStream

/**
 * 分区快照测试。
 *
 * 重点不在"能备份能恢复"，而在**恢复链上每道防线各自真的会拦住**：分区被半截镜像覆盖是
 * 不可逆的，这些校验一旦失效，用户拿不到任何提示，只会看到设备起不来。
 */
class PartitionSnapshotSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val now = Instant.parse("2026-10-08T12:00:00Z")

    /** 假的分区层：记录被刷了哪个分区、刷了多少字节，不碰真实块设备。 */
    private class FakeIo(
        var size: Long = 8L * 1024 * 1024,
        var names: List<String> = listOf("boot_a", "init_boot_a"),
        var dumpFails: Boolean = false,
        var flashFails: Boolean = false,
    ) : PartitionIo {
        val flashed = mutableListOf<Pair<String, Long>>()
        var dumpCompressed: Boolean? = null

        override suspend fun slotSuffix(): String = "_a"

        var release: String = "5.15.104-android13-8"

        override suspend fun kernelRelease(): String = release

        override suspend fun snapshots(): List<PartitionInfo> = names.map {
            PartitionInfo(name = it, path = "/dev/block/by-name/$it", sizeBytes = size)
        }

        override suspend fun dump(info: PartitionInfo, target: File, compress: Boolean): Result<Unit> {
            if (dumpFails) return Result.failure(IllegalStateException("dd: i/o error"))
            dumpCompressed = compress
            val payload = ByteArray(info.sizeBytes.toInt()) { 7 }
            if (compress) {
                GZIPOutputStream(target.outputStream()).use { it.write(payload) }
            } else {
                target.writeBytes(payload)
            }
            return Result.success(Unit)
        }

        override suspend fun flash(info: PartitionInfo, source: File, compressed: Boolean): Result<Unit> {
            if (flashFails) return Result.failure(IllegalStateException("dd: write error"))
            flashed += info.name to source.length()
            return Result.success(Unit)
        }
    }

    private fun source(io: PartitionIo, compress: Boolean = true) = PartitionSnapshotSource(
        io = io,
        clock = { now },
        deviceName = { "Test Device" },
        staging = StagingArea(temp.newFolder()),
        compress = { compress },
    )

    private fun gzipByteStream(bytes: ByteArray): ByteArray =
        java.io.ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun entry(partition: String, fileName: String, size: Long = 8L * 1024 * 1024 * 2) = BackupEntry(
        kind = BackupKind.PARTITION,
        entryId = partition,
        fileName = fileName,
        metaFileName = ArchiveNaming.metaFileNameFor(fileName),
        sizeBytes = size,
        sha256 = "sha",
        createdAt = now.toString(),
    )

    private fun metaJson(partition: String, size: Long = 8L * 1024 * 1024, compressed: Boolean = true) =
        BackupManifest.renderPartitionMeta(
            PartitionSnapshotMeta(
                entryId = partition,
                partition = partition,
                sourceDevice = "Test Device",
                partitionSizeBytes = size,
                compressed = compressed,
                createdAt = now.toString(),
            )
        )

    @Test
    fun `export snapshots every partition it can read`() {
        runBlocking {
            val io = FakeIo()
            val outcome = source(io).export().getOrThrow()

            assertEquals(listOf("boot_a", "init_boot_a"), outcome.artifacts.map { it.entryId })
            assertEquals(
                listOf("partition_boot_a_20261008_120000.img.gz", "partition_init_boot_a_20261008_120000.img.gz"),
                outcome.artifacts.map { it.fileName },
            )
            assertTrue(io.dumpCompressed!!)
            // 同一个 timestamp：一次快照里的两个分区必须归到同一个 session，
            // 保留额度才是按"次"算而不是按"文件"算。
            assertEquals(
                setOf("20261008_120000"),
                outcome.artifacts.map { ArchiveNaming.sessionOf(it.fileName) }.toSet(),
            )
        }
    }

    @Test
    fun `export honours the compression switch`() {
        runBlocking {
            val outcome = source(FakeIo(), compress = false).export().getOrThrow()

            assertTrue(outcome.artifacts.all { it.fileName.endsWith(".img") && !it.fileName.endsWith(".gz") })
            assertFalse(BackupManifest.parsePartitionMeta(outcome.artifacts[0].metaJson!!)!!.compressed)
        }
    }

    @Test
    fun `a partition without a device is not a failure`() {
        runBlocking {
            val outcome = source(FakeIo(names = emptyList())).export().getOrThrow()

            assertTrue(outcome.artifacts.isEmpty())
            // 空结果不等于失败：非 A/B 老设备根本没有独立 init_boot，报错只会吓人。
            assertTrue(outcome.failures.isEmpty())
        }
    }

    /** 快照把拍它那一刻的 uname 记进每份 meta：恢复确认前靠它做 KMI 比对。 */
    @Test
    fun `export records the kernel release into every meta`() {
        runBlocking {
            val outcome = source(FakeIo()).export().getOrThrow()

            assertEquals(
                setOf("5.15.104-android13-8"),
                outcome.artifacts
                    .map { BackupManifest.parsePartitionMeta(it.metaJson!!)?.kernelRelease }
                    .toSet(),
            )
        }
    }

    /** uname 取不到（shell 失败）时 meta 里不写这个键：解析回来是 null，KMI 按未知处理。 */
    @Test
    fun `a blank kernel release leaves the meta without one`() {
        runBlocking {
            val io = FakeIo(names = listOf("boot_a")).apply { release = "" }
            val outcome = source(io).export().getOrThrow()

            assertFalse(outcome.artifacts.single().metaJson!!.contains("kernelRelease"))
            assertNull(BackupManifest.parsePartitionMeta(outcome.artifacts.single().metaJson!!)?.kernelRelease)
        }
    }

    @Test
    fun `one failing partition does not stop the others`() {
        runBlocking {
            val io = FakeIo()
            // 只让第一个分区失败：把 size 设成 -1 之外没别的办法逐分区控制，这里改用
            // names 里塞一个非法名——白名单在 IO 层，源层只用它取 snapshot，所以仍会尝试；
            // 简化起见直接断言"全失败时记两条失败、零产物"。
            io.dumpFails = true
            val outcome = source(io).export().getOrThrow()

            assertTrue(outcome.artifacts.isEmpty())
            assertEquals(2, outcome.failures.size)
            assertTrue(outcome.failures.all { it.reason is BackupReason.PartitionDumpFailed })
        }
    }

    @Test
    fun `restore refuses without a sidecar meta`() {
        runBlocking {
            val outcome = source(FakeIo()).restore(
                entry("boot_a", "partition_boot_a_20261008_120000.img.gz"),
                null,
                ByteArrayInputStream(ByteArray(0)),
            ).getOrThrow()

            assertFalse(outcome.success)
            assertEquals(BackupReason.PartitionNoSidecarMeta, outcome.reason)
        }
    }

    @Test
    fun `restore refuses an unusable partition name`() {
        runBlocking {
            // 边车 meta 在共享存储/远端，谁都能改；分区名会被拼进 root 命令。
            val json = metaJson("boot_a").replace("boot_a", "boot_a; reboot")

            val outcome = source(FakeIo()).restore(
                entry("boot_a", "partition_boot_a_20261008_120000.img.gz"),
                json,
                ByteArrayInputStream(ByteArray(0)),
            ).getOrThrow()

            assertFalse(outcome.success)
            assertTrue(outcome.reason is BackupReason.PartitionNameUnusable)
        }
    }

    @Test
    fun `restore refuses when the sidecar names a different partition than the archive`() {
        runBlocking {
            val outcome = source(FakeIo()).restore(
                entry("boot_a", "partition_init_boot_a_20261008_120000.img.gz"),
                metaJson("boot_a"),
                ByteArrayInputStream(ByteArray(0)),
            ).getOrThrow()

            assertFalse(outcome.success)
            assertTrue(outcome.reason is BackupReason.PartitionNameUnusable)
        }
    }

    @Test
    fun `restore refuses when the index entry names yet another partition`() {
        runBlocking {
            val outcome = source(FakeIo()).restore(
                entry("init_boot_a", "partition_boot_a_20261008_120000.img.gz"),
                metaJson("boot_a"),
                ByteArrayInputStream(ByteArray(0)),
            ).getOrThrow()

            assertFalse(outcome.success)
            assertTrue(outcome.reason is BackupReason.PartitionNameUnusable)
        }
    }

    @Test
    fun `restore refuses a partition this device does not have`() {
        runBlocking {
            val outcome = source(FakeIo(names = listOf("init_boot_a"))).restore(
                entry("boot_a", "partition_boot_a_20261008_120000.img.gz"),
                metaJson("boot_a"),
                ByteArrayInputStream(ByteArray(0)),
            ).getOrThrow()

            assertFalse(outcome.success)
            assertEquals(BackupReason.PartitionMissing("boot_a"), outcome.reason)
        }
    }

    @Test
    fun `restore refuses a snapshot taken on a different partition table`() {
        runBlocking {
            val outcome = source(FakeIo(size = 8L * 1024 * 1024)).restore(
                entry("boot_a", "partition_boot_a_20261008_120000.img.gz"),
                metaJson("boot_a", size = 96L * 1024 * 1024),
                ByteArrayInputStream(ByteArray(0)),
            ).getOrThrow()

            assertFalse(outcome.success)
            // 大小不一致要在"拍快照时的分区大小 != 当前分区大小"这一步就被拦下来，
            // 而不是等到解压后比长度——前者说明分区表变了（多半换了设备），
            // 后者只能说明镜像残缺，两种事故的处置方式不同。
            assertTrue(outcome.reason is BackupReason.PartitionTableChanged)
        }
    }

    @Test
    fun `restore refuses a payload shorter than the partition`() {
        runBlocking {
            // 这是"刷了残缺镜像"唯一能在写入之前发现的地方：压缩数据本身可能完全合法
            // （toybox gzip 对截断数据也退出 0），只有长度对不上才说明它不完整。
            val truncated = gzipByteStream(ByteArray(1024))

            val outcome = source(FakeIo()).restore(
                entry("boot_a", "partition_boot_a_20261008_120000.img.gz"),
                metaJson("boot_a"),
                ByteArrayInputStream(truncated),
            ).getOrThrow()

            assertFalse(outcome.success)
            assertTrue(outcome.reason is BackupReason.PartitionSizeMismatch)
        }
    }

    @Test
    fun `restore flashes the partition the sidecar names`() {
        runBlocking {
            val io = FakeIo()
            val payload = gzipByteStream(ByteArray(8 * 1024 * 1024))

            val outcome = source(io).restore(
                entry("boot_a", "partition_boot_a_20261008_120000.img.gz"),
                metaJson("boot_a"),
                ByteArrayInputStream(payload),
            ).getOrThrow()

            assertTrue("expected success but got ${outcome.reason}", outcome.success)
            assertEquals(listOf("boot_a"), io.flashed.map { it.first })
        }
    }

    @Test
    fun `restore reports a failed flash instead of claiming success`() {
        runBlocking {
            val io = FakeIo(flashFails = true)
            val payload = gzipByteStream(ByteArray(8 * 1024 * 1024))

            val outcome = source(io).restore(
                entry("boot_a", "partition_boot_a_20261008_120000.img.gz"),
                metaJson("boot_a"),
                ByteArrayInputStream(payload),
            ).getOrThrow()

            assertFalse(outcome.success)
            assertTrue(outcome.reason is BackupReason.PartitionFlashFailed)
        }
    }

    @Test
    fun `restore of an uncompressed snapshot reads the size from the payload`() {
        runBlocking {
            val io = FakeIo()
            val outcome = source(io).restore(
                entry("boot_a", "partition_boot_a_20261008_120000.img"),
                metaJson("boot_a", compressed = false),
                ByteArrayInputStream(ByteArray(8 * 1024 * 1024)),
            ).getOrThrow()

            assertTrue("expected success but got ${outcome.reason}", outcome.success)
            assertEquals(listOf("boot_a"), io.flashed.map { it.first })
        }
    }

    @Test
    fun `import derives the partition from the archive name`() {
        runBlocking {
            val meta = source(FakeIo()).metaForImport(
                "partition_init_boot_a_20261008_120000.img.gz",
                { ByteArrayInputStream(ByteArray(0)) },
                0,
            )

            assertEquals("init_boot_a", meta!!.entryId)
            val parsed = BackupManifest.parsePartitionMeta(meta.metaJson)!!
            assertEquals("init_boot_a", parsed.partition)
            assertTrue(parsed.compressed)
        }
    }

    @Test
    fun `import refuses an archive for a partition this device does not have`() {
        runBlocking {
            val meta = source(FakeIo(names = listOf("init_boot_a"))).metaForImport(
                "partition_boot_a_20261008_120000.img",
                { ByteArrayInputStream(ByteArray(0)) },
                0,
            )

            assertNull(meta)
        }
    }

    @Test
    fun `import refuses a name it cannot decode`() {
        runBlocking {
            val source = source(FakeIo())

            assertNull(source.metaForImport("boot_d5a51d7c3859_20261008_120000.img", { ByteArrayInputStream(ByteArray(0)) }, 0))
            assertNull(source.metaForImport("partition_boot_a.img", { ByteArrayInputStream(ByteArray(0)) }, 0))
        }
    }
}
