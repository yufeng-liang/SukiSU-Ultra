package com.sukisu.ultra.ui.screen.settings.backup

import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动备份那一栏的**显示**判定。
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

    @Test
    fun `the auto-backup dropdown follows what will really be written`() {
        // 下拉框的选中项从 autoBackupOrigins 来，不是从偏好项来：云端没配好时偏好项可能还是
        // true，照着它画就会显示"云端"，而实际一次都没传出去。
        val state = BackupUiState(
            autoBackupEnabled = true,
            autoBackupLocal = true,
            autoBackupCloud = true,
            cloudConfigured = false,
        )
        assertEquals(setOf(BackupOrigin.LOCAL), state.autoBackupOrigins)
        assertEquals(
            setOf(BackupOrigin.LOCAL, BackupOrigin.CLOUD),
            state.copy(cloudConfigured = true).autoBackupOrigins,
        )
        assertEquals(
            setOf(BackupOrigin.CLOUD),
            state.copy(cloudConfigured = true, autoBackupLocal = false).autoBackupOrigins,
        )
    }

    @Test
    fun `the dropdown index and the option round-trip`() {
        // 下标含义两个下拉框共用：0 = 本地，1 = 云端，2 = 本地+云端。
        assertEquals(0, originOptionIndex(setOf(BackupOrigin.LOCAL), cloudReady = true))
        assertEquals(1, originOptionIndex(setOf(BackupOrigin.CLOUD), cloudReady = true))
        assertEquals(
            2,
            originOptionIndex(setOf(BackupOrigin.LOCAL, BackupOrigin.CLOUD), cloudReady = true),
        )
        // 云端没配好时列表里只有一档，下标必须回落到 0，否则下拉框会指着不存在的选项。
        assertEquals(0, originOptionIndex(setOf(BackupOrigin.CLOUD), cloudReady = false))
        assertEquals(setOf(BackupOrigin.LOCAL), originsForOption(cloudReady = false, index = 2))
        assertEquals(setOf(BackupOrigin.CLOUD), originsForOption(cloudReady = true, index = 1))
        assertEquals(
            setOf(BackupOrigin.LOCAL, BackupOrigin.CLOUD),
            originsForOption(cloudReady = true, index = 2),
        )
    }

    @Test
    fun `a device with no modules can still back up`() {
        // 一个模块都没装时，"勾了模块这一类但一条都没勾上"不能把立即备份永久锁死：这一类本身
        // 就是要备份的东西，只是这次没有可选项。
        val state = BackupUiState(
            origins = setOf(BackupOrigin.LOCAL),
            kinds = setOf(BackupKind.MODULE),
            modules = emptyList(),
            selectedModuleIds = emptySet(),
        )
        assertTrue(state.canBackUp)
    }
}
