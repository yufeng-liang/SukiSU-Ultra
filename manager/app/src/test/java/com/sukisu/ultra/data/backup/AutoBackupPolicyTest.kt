package com.sukisu.ultra.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBackupPolicyTest {

    @Test
    fun `runs for modules when enabled`() {
        assertTrue(AutoBackupPolicy.shouldRun(enabled = true, kind = BackupKind.MODULE))
    }

    @Test
    fun `does not run when disabled`() {
        assertFalse(AutoBackupPolicy.shouldRun(enabled = false, kind = BackupKind.MODULE))
    }

    @Test
    fun `never runs for boot images`() {
        assertFalse(AutoBackupPolicy.shouldRun(enabled = true, kind = BackupKind.BOOT))
    }

    @Test
    fun `both sides clean is a success`() {
        assertEquals(AutoBackupOutcome.OK, AutoBackupPolicy.classify(run(), run()))
    }

    @Test
    fun `a local-only run only looks at the local side`() {
        assertEquals(AutoBackupOutcome.OK, AutoBackupPolicy.classify(run(), null))
        assertEquals(AutoBackupOutcome.FAILED, AutoBackupPolicy.classify(run(failed = true), null))
    }

    @Test
    fun `one side failing is a partial result, not a failure`() {
        // 本地成功、云端失败：本地那份已经是可用的安全网，说"失败"会让用户以为什么都没备上。
        assertEquals(AutoBackupOutcome.PARTIAL, AutoBackupPolicy.classify(run(), run(failed = true)))
        assertEquals(AutoBackupOutcome.PARTIAL, AutoBackupPolicy.classify(run(failed = true), run()))
    }

    @Test
    fun `both sides failing is a failure`() {
        assertEquals(AutoBackupOutcome.FAILED, AutoBackupPolicy.classify(run(failed = true), run(failed = true)))
    }

    @Test
    fun `a cloud-only run only looks at the cloud side`() {
        // 用户把「本机」取消勾选、只留云端：本机那份压根没跑，它的成败不该参与判定。
        assertEquals(AutoBackupOutcome.OK, AutoBackupPolicy.classify(null, run()))
        assertEquals(AutoBackupOutcome.FAILED, AutoBackupPolicy.classify(null, run(failed = true)))
    }

    @Test
    fun `targets keeps whatever the user checked`() {
        val targets = AutoBackupPolicy.targets(local = true, cloud = true, cloudConfigured = true)
        assertTrue(targets.local)
        assertTrue(targets.cloud)
        assertTrue(targets.any)
    }

    @Test
    fun `an unchecked cloud is dropped even when it is configured`() {
        val targets = AutoBackupPolicy.targets(local = true, cloud = false, cloudConfigured = true)
        assertTrue(targets.local)
        assertFalse(targets.cloud)
    }

    @Test
    fun `a cloud that is not configured cannot be written even when checked`() {
        val targets = AutoBackupPolicy.targets(local = false, cloud = true, cloudConfigured = false)
        assertFalse(targets.cloud)
        // 本机也没勾：一个目的地都没有，自动备份整体跳过。
        assertFalse(targets.any)
    }

    private fun run(failed: Boolean = false) = BackupRunResult(
        kind = BackupKind.MODULE,
        written = if (failed) emptyList() else listOf("module_a_1_x.zip"),
        failures = if (failed) {
            listOf(BackupFailure("a.zip", "local", BackupReason.External("boom")))
        } else {
            emptyList()
        },
    )
}
