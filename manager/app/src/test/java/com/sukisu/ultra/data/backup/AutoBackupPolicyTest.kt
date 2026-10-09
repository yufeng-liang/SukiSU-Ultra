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
