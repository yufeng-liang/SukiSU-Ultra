package com.sukisu.ultra.ui.screen.settings.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动备份那两个勾选框的**显示**判定。
 *
 * 界面必须和数据层用同一套结论：云端没配好时偏好项可能还是 true，若界面照偏好项画成"已勾"，
 * 用户会以为自动备份在传云端——实际一次都没传出去。所以这里锁住的是"显示 = 实际会写"。
 */
class BackupUiStateTest {

    @Test
    fun `an unconfigured cloud shows as unchecked`() {
        val state = BackupUiState(
            autoBackupEnabled = true,
            autoBackupLocal = true,
            autoBackupCloud = true,
            cloudConfigured = false,
        )
        assertFalse(state.autoBackupTargets.cloud)
        // 本机还写着，所以不是"什么都不写"。
        assertFalse(state.autoBackupWritesNothing)
    }

    @Test
    fun `a configured cloud shows as checked`() {
        val state = BackupUiState(
            autoBackupEnabled = true,
            autoBackupLocal = true,
            autoBackupCloud = true,
            cloudConfigured = true,
        )
        assertTrue(state.autoBackupTargets.cloud)
    }

    @Test
    fun `a cloud that is not configured does not count as a destination`() {
        // 本机取消勾选、云端没配：实际一处都写不了，界面要挂那句说明。
        val state = BackupUiState(
            autoBackupEnabled = true,
            autoBackupLocal = false,
            autoBackupCloud = true,
            cloudConfigured = false,
        )
        assertTrue(state.autoBackupWritesNothing)
    }

    @Test
    fun `the master switch off is not reported as writing nothing`() {
        // 关着总开关是用户明确关掉的，不是"配置漏了一处"，不用再挂一句警告。
        val state = BackupUiState(
            autoBackupEnabled = false,
            autoBackupLocal = false,
            autoBackupCloud = false,
            cloudConfigured = false,
        )
        assertFalse(state.autoBackupWritesNothing)
    }
}
