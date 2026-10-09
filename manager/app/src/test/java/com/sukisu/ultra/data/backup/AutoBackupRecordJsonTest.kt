package com.sukisu.ultra.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBackupRecordJsonTest {

    /**
     * 每一种原因都要能落盘再读回来。
     *
     * 这条用例是 [AutoBackupRecordJson] 的覆盖性保证：加了新的 [BackupReason] 却忘了
     * 在编解码里处理，它会在这里挂掉，而不是等到用户看到一条读不出来的历史记录。
     */
    private val everyReason = listOf(
        BackupReason.CloudNotConfigured,
        BackupReason.CloudCredentialsRejected(401),
        BackupReason.HttpFailed(HttpOperation.UPLOAD, "a.zip", 500),
        BackupReason.HttpFailed(HttpOperation.CREATE_DIRECTORY, "/", 405),
        BackupReason.IndexUnreadable("not json"),
        BackupReason.ReadFailed("/sdcard/x.zip", null),
        BackupReason.WriteFailed("/sdcard/x.zip", "no space"),
        BackupReason.DeleteFailed("/sdcard/x.zip", null),
        BackupReason.DirectoryNotWritable("/sdcard/Download/SukiSU-Backup", null),
        BackupReason.Corrupted("boot.img", "aaaa", "bbbb"),
        BackupReason.ArchiveFailed("zygisk-assistant", "boom"),
        BackupReason.ModuleInstallFailed("zygisk-assistant", "ksud said no"),
        BackupReason.ModuleDisableFailed("zygisk-assistant", null),
        BackupReason.NoSource(BackupKind.BOOT),
        BackupReason.BootNoSidecarMeta,
        BackupReason.BootForeignStockImage("1111", "2222"),
        BackupReason.BootNoChecksum,
        BackupReason.BootStockImageMissing("9f2c8a1b4e6d00112233445566778899aabbccdd"),
        BackupReason.BootIdentityMismatch("9f2c8a1b4e6d00112233445566778899aabbccdd"),
        BackupReason.BootFlashFailed("no matching backup"),
        BackupReason.FileUnreadable,
        BackupReason.FileNameUnresolved,
        BackupReason.DuplicateContent("module_x_1_20261008_130000.zip"),
        BackupReason.External("unexpected"),
    )

    @Test
    fun `every reason survives a round trip`() {
        everyReason.forEach { reason ->
            val record = AutoBackupRecord(
                atEpochMs = 1_760_000_000_000L,
                outcome = AutoBackupOutcome.PARTIAL,
                writtenCount = 2,
                failures = listOf(BackupFailure(path = "a.zip", storage = "webdav", reason = reason)),
            )

            val parsed = AutoBackupRecordJson.parse(AutoBackupRecordJson.render(record))

            assertEquals(reason, parsed?.failures?.single()?.reason)
        }
    }

    @Test
    fun `the record itself survives a round trip`() {
        val record = AutoBackupRecord(
            atEpochMs = 1_760_000_000_000L,
            outcome = AutoBackupOutcome.FAILED,
            writtenCount = 0,
            failures = listOf(
                BackupFailure(path = "", storage = "webdav", reason = BackupReason.CloudNotConfigured),
                BackupFailure(path = "a.zip", storage = "local", reason = BackupReason.WriteFailed("a.zip", null)),
            ),
        )

        val parsed = AutoBackupRecordJson.parse(AutoBackupRecordJson.render(record))

        assertEquals(record, parsed)
    }

    @Test
    fun `the skipped count survives a round trip and defaults to zero for older records`() {
        val record = AutoBackupRecord(
            atEpochMs = 1_760_000_000_000L,
            outcome = AutoBackupOutcome.OK,
            writtenCount = 0,
            skippedCount = 11,
            failures = emptyList(),
        )

        assertEquals(11, AutoBackupRecordJson.parse(AutoBackupRecordJson.render(record))?.skippedCount)

        // 更早版本写下的记录里没有这一项：当成 0，而不是让整条记录读不出来。
        val older = AutoBackupRecordJson.render(record)
            .replace("\"skippedCount\":11,", "")
            .replace(",\"skippedCount\":11", "")
        assertEquals(0, AutoBackupRecordJson.parse(older)?.skippedCount)
    }

    @Test
    fun `a missing or empty record is null`() {
        assertNull(AutoBackupRecordJson.parse(null))
        assertNull(AutoBackupRecordJson.parse(""))
    }

    @Test
    fun `a broken record is null rather than an exception`() {
        assertNull(AutoBackupRecordJson.parse("{ not json"))
        // 结构对了但 schema 不是我们的：宁可当作"没有记录"，也不要拿别人的字段硬解。
        assertNull(AutoBackupRecordJson.parse("""{"schema":"something.else","outcome":"OK"}"""))
    }

    @Test
    fun `an unknown reason type degrades to its raw name instead of failing the whole record`() {
        val json = """
            {"schema":"sukisu.backup.auto","version":1,"atEpochMs":1,"outcome":"FAILED","writtenCount":0,
             "failures":[{"path":"a","storage":"local","reason":{"type":"some_future_reason"}}]}
        """.trimIndent()

        val parsed = AutoBackupRecordJson.parse(json)

        assertTrue(parsed?.failures?.single()?.reason is BackupReason.External)
        assertEquals("some_future_reason", (parsed?.failures?.single()?.reason as BackupReason.External).text)
    }
}
