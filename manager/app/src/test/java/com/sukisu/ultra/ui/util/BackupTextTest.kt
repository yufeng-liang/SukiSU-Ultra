package com.sukisu.ultra.ui.util

import com.sukisu.ultra.R
import com.sukisu.ultra.data.backup.BackupFailure
import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin
import com.sukisu.ultra.data.backup.BackupReason
import com.sukisu.ultra.data.backup.BackupRunResult
import com.sukisu.ultra.data.backup.HttpOperation
import com.sukisu.ultra.data.backup.LocalBackupStorage
import com.sukisu.ultra.data.backup.WebDavBackupStorage
import com.sukisu.ultra.ui.screen.settings.backup.BackupTargetResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 只测**纯**的那一半映射（资源 id + 参数）：`Context.getString` 在 JVM 单测里跑不了，
 * 而这里真正会出错的只有参数顺序、哈希截断和脱敏。
 * "每个 [BackupReason] 都有对应文案"由编译器的穷尽 `when` 保证。
 */
class BackupTextTest {

    @Test
    fun `the duplicate reason names the archive it matched`() {
        assertEquals(
            ReasonText(R.string.backup_reason_duplicate_content, listOf("module_x_1_20261008_130000.zip")),
            BackupText.reasonText(BackupReason.DuplicateContent("module_x_1_20261008_130000.zip")),
        )
    }

    @Test
    fun `a backup that wrote nothing but skipped everything says so`() {
        // 按了「立即备份」，看到"已备份 0 项"像是失败；这一格要的是那句话本身："内容和已有的
        // 备份一样"。格子里写短形式（"已是最新（3 项）"），"已是最新"是什么意思由表下面
        // 那条释义说一次（见 anyUnchanged）。
        val cell = BackupText.outcomeCell(
            kind = BackupKind.MODULE,
            result = BackupRunResult(
                kind = BackupKind.MODULE,
                written = emptyList(),
                skipped = listOf("a.zip", "b.zip", "c.zip"),
            ),
        )

        assertEquals(ReasonText(R.string.backup_result_unchanged, listOf(3)), cell)
    }

    @Test
    fun `a partly skipped backup still reports both counts`() {
        val cell = BackupText.outcomeCell(
            kind = BackupKind.MODULE,
            result = BackupRunResult(kind = BackupKind.MODULE, written = listOf("a.zip"), skipped = listOf("b.zip")),
        )

        assertEquals(ReasonText(R.string.backup_result_partial, listOf(1, 1)), cell)
    }

    @Test
    fun `a target with nothing to back up names the kind it looked at`() {
        // 分区快照和模块的"没有可备的东西"是两件事：前者是这台设备上找不到可拍照的分区，
        // 后者是用户一个模块都没勾。同一句话套给两边，用户会去查错方向。
        val nothing = BackupRunResult(kind = BackupKind.PARTITION)

        assertEquals(
            ReasonText(R.string.backup_nothing_partition),
            BackupText.outcomeCell(BackupKind.PARTITION, nothing),
        )
        assertEquals(
            ReasonText(R.string.backup_nothing_module),
            BackupText.outcomeCell(BackupKind.MODULE, nothing),
        )
    }

    @Test
    fun `a target that only failed gets the short failure cell`() {
        // 原因（HTTP 状态码、文件路径）比"结果"那一列宽得多，塞进格子会把列宽撑歪：
        // 格子写"失败"，原因整行宽度排在表下面。
        val failed = BackupRunResult(
            kind = BackupKind.MODULE,
            failures = listOf(BackupFailure(path = "", storage = WebDavBackupStorage.ID, reason = BackupReason.CloudNotConfigured)),
        )

        assertEquals(
            ReasonText(R.string.backup_result_failed),
            BackupText.outcomeCell(BackupKind.MODULE, failed),
        )
    }

    @Test
    fun `a target that wrote some and failed some reports what it wrote`() {
        val mixed = BackupRunResult(
            kind = BackupKind.MODULE,
            written = listOf("a.zip", "b.zip"),
            failures = listOf(BackupFailure(path = "c.zip", storage = LocalBackupStorage.ID, reason = BackupReason.WriteFailed("c.zip", null))),
        )

        assertEquals(
            ReasonText(R.string.backup_summary_written, listOf(2)),
            BackupText.outcomeCell(BackupKind.MODULE, mixed),
        )
    }

    @Test
    fun `the up-to-date note is only needed when a cell says it`() {
        val unchanged = BackupRunResult(kind = BackupKind.MODULE, skipped = listOf("a.zip"))
        val written = BackupRunResult(kind = BackupKind.MODULE, written = listOf("a.zip"))

        assertEquals(true, BackupText.anyUnchanged(listOf(written, unchanged)))
        assertEquals(false, BackupText.anyUnchanged(listOf(written)))
    }

    @Test
    fun `the stock-image note is only needed when a boot cell says it`() {
        // 结果框里写着"没有可拍照的分区"，用户分不清"本来就该没有"和"这次漏备了"：
        // 只有分区快照那一格真的这么写了，才需要在表下面补一句。
        fun target(kind: BackupKind, result: BackupRunResult) =
            BackupTargetResult(BackupOrigin.LOCAL, kind, result)

        val partitionNothing = BackupRunResult(kind = BackupKind.PARTITION)
        val partitionWritten =
            BackupRunResult(kind = BackupKind.PARTITION, written = listOf("partition_boot_a.img"))

        assertEquals(true, BackupText.anyNothingPartition(listOf(target(BackupKind.PARTITION, partitionNothing))))
        assertEquals(false, BackupText.anyNothingPartition(listOf(target(BackupKind.PARTITION, partitionWritten))))
        // 模块一个没勾也会给出"没有可备的东西"，但那是另一件事，不套这句话。
        assertEquals(
            false,
            BackupText.anyNothingPartition(
                listOf(target(BackupKind.MODULE, BackupRunResult(kind = BackupKind.MODULE))),
            ),
        )
        // 云端分区快照也走同一格：本机没有可拍照的分区时，云端同样没东西可传。
        assertEquals(
            true,
            BackupText.anyNothingPartition(
                listOf(BackupTargetResult(BackupOrigin.CLOUD, BackupKind.PARTITION, partitionNothing)),
            ),
        )
    }

    @Test
    fun `the same failure for every file is listed once`() {
        // 凭据被拒：11 个文件各报一条，原因和路径都一样——列 11 遍只会把结果框变成一堵墙。
        val same = (1..11).map {
            BackupFailure(path = "", storage = WebDavBackupStorage.ID, reason = BackupReason.CloudCredentialsRejected(401))
        }
        // 两个具体文件没传上去：这是两条不同的信息，不能并掉。
        val perFile = listOf(
            BackupFailure(path = "a.zip", storage = WebDavBackupStorage.ID, reason = BackupReason.HttpFailed(HttpOperation.UPLOAD, "a.zip", 500)),
            BackupFailure(path = "b.zip", storage = WebDavBackupStorage.ID, reason = BackupReason.HttpFailed(HttpOperation.UPLOAD, "b.zip", 500)),
        )

        val lines = BackupText.failureLines(BackupRunResult(kind = BackupKind.MODULE, failures = same + perFile))

        assertEquals(3, lines.size)
    }

    @Test
    fun `the http status code keeps its place after the file name`() {
        assertEquals(
            ReasonText(R.string.backup_reason_http_upload, listOf("a.zip", 500)),
            BackupText.reasonText(BackupReason.HttpFailed(HttpOperation.UPLOAD, "a.zip", 500)),
        )
        assertEquals(
            ReasonText(R.string.backup_reason_http_mkcol, listOf("/", 405)),
            BackupText.reasonText(BackupReason.HttpFailed(HttpOperation.CREATE_DIRECTORY, "/", 405)),
        )
    }

    @Test
    fun `the credentials reason carries the status code`() {
        assertEquals(
            ReasonText(R.string.backup_reason_cloud_credentials, listOf(401)),
            BackupText.reasonText(BackupReason.CloudCredentialsRejected(401)),
        )
    }

    @Test
    fun `hashes are truncated so a snackbar stays readable`() {
        val long = "9f2c8a1b4e6d00112233445566778899aabbccdd"
        val expected = "9f2c8a1b4e6d…"

        assertEquals(
            ReasonText(R.string.backup_reason_corrupted, listOf("boot.img", expected, expected)),
            BackupText.reasonText(BackupReason.Corrupted("boot.img", long, long)),
        )
        assertEquals(
            ReasonText(R.string.backup_reason_corrupted, listOf("init_boot_a", expected, expected)),
            BackupText.reasonText(BackupReason.Corrupted("init_boot_a", long, long)),
        )
    }

    @Test
    fun `a partition name is shown as it is`() {
        // 分区名本来就很短，不存在截断；被截断的只有 sha1 那一类长哈希。
        assertEquals(
            ReasonText(R.string.backup_reason_partition_dump_failed, listOf("boot_a")),
            BackupText.reasonText(BackupReason.PartitionDumpFailed("boot_a", null)),
        )
        assertEquals(
            ReasonText(R.string.backup_reason_partition_flash_failed, listOf("init_boot_b")),
            BackupText.reasonText(BackupReason.PartitionFlashFailed("init_boot_b", "dd failed")),
        )
    }

    @Test
    fun `third party text is redacted before it becomes an argument`() {
        val text = BackupText.reasonText(
            BackupReason.External("PUT failed for https://bob:secret@nas.local/dav/x"),
        )

        assertEquals(R.string.backup_reason_external, text.resId)
        val rendered = text.args.single() as String
        assertEquals(false, rendered.contains("secret"))
        assertEquals(true, rendered.contains("***:***@nas.local"))
    }

    @Test
    fun `details are surfaced for the reasons that carry them`() {
        assertEquals("not json", BackupText.detailOf(BackupReason.IndexUnreadable("not json")))
        assertEquals("ksud said no", BackupText.detailOf(BackupReason.ModuleInstallFailed("a", "ksud said no")))
        assertNull(BackupText.detailOf(BackupReason.ReadFailed("x", null)))
        assertNull(BackupText.detailOf(BackupReason.PartitionNoSidecarMeta))
        assertNull(BackupText.detailOf(BackupReason.CloudNotConfigured))
    }

    @Test
    fun `details are redacted too`() {
        val detail = BackupText.detailOf(BackupReason.ReadFailed("x", "https://bob:secret@nas.local/dav/x"))

        assertEquals(false, detail?.contains("secret"))
    }

    @Test
    fun `only the local and cloud backends get a location label`() {
        assertEquals(R.string.backup_storage_local, BackupText.storageLabelRes(LocalBackupStorage.ID))
        assertEquals(R.string.backup_storage_cloud, BackupText.storageLabelRes(WebDavBackupStorage.ID))
        // 源侧/引擎侧的失败没有"位置"可讲，加前缀只会让人以为那是备份存放的地方。
        assertNull(BackupText.storageLabelRes("source"))
        assertNull(BackupText.storageLabelRes("engine"))
    }
}
