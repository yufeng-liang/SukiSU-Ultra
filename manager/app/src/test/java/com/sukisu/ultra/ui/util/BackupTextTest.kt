package com.sukisu.ultra.ui.util

import com.sukisu.ultra.R
import com.sukisu.ultra.data.backup.BackupReason
import com.sukisu.ultra.data.backup.HttpOperation
import com.sukisu.ultra.data.backup.LocalBackupStorage
import com.sukisu.ultra.data.backup.WebDavBackupStorage
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
            ReasonText(R.string.backup_reason_boot_missing, listOf(expected)),
            BackupText.reasonText(BackupReason.BootStockImageMissing(long)),
        )
        assertEquals(
            ReasonText(R.string.backup_reason_boot_foreign, listOf(expected, expected)),
            BackupText.reasonText(BackupReason.BootForeignStockImage(long, long)),
        )
        assertEquals(
            ReasonText(R.string.backup_reason_corrupted, listOf("boot.img", expected, expected)),
            BackupText.reasonText(BackupReason.Corrupted("boot.img", long, long)),
        )
    }

    @Test
    fun `a short hash is left alone`() {
        assertEquals(
            ReasonText(R.string.backup_reason_boot_identity, listOf("abc")),
            BackupText.reasonText(BackupReason.BootIdentityMismatch("abc")),
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
        assertNull(BackupText.detailOf(BackupReason.BootNoChecksum))
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
