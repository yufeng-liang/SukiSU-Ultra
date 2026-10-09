package com.sukisu.ultra.data.backup

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
}
