package com.sukisu.ultra.ui.screen.settings.backup

import com.sukisu.ultra.data.backup.BackupDefaults
import com.sukisu.ultra.data.backup.BackupOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupLocationTest {

    @Test
    fun `local is the fixed download folder`() {
        assertEquals(
            BackupDefaults.LOCAL_DIR,
            BackupLocation.full(BackupOrigin.LOCAL, "https://dav.example.com/dav"),
        )
    }

    @Test
    fun `cloud is the configured address, without an extra folder`() {
        // 归档直接落在配置的地址下面，App 不会再套一层 SukiSU-Backup——地址本身就是位置。
        assertEquals(
            "https://dav.jianguoyun.com/dav/SukiSU-Backup",
            BackupLocation.full(BackupOrigin.CLOUD, "  https://dav.jianguoyun.com/dav/SukiSU-Backup  "),
        )
    }

    @Test
    fun `short keeps only the last directory`() {
        assertEquals("SukiSU-Backup", BackupLocation.short("/sdcard/Download/SukiSU-Backup"))
        assertEquals("SukiSU-Backup", BackupLocation.short("https://dav.jianguoyun.com/dav/SukiSU-Backup"))
    }

    @Test
    fun `a trailing slash does not produce an empty short form`() {
        // 用户在地址栏末尾多打一个 `/` 是很常见的，这里取到空串就会变成"存到 "。
        assertEquals("dav", BackupLocation.short("https://dav.example.com/dav/"))
        assertEquals("SukiSU-Backup", BackupLocation.short("/sdcard/Download/SukiSU-Backup/"))
    }

    @Test
    fun `an address with no path falls back to the host`() {
        assertEquals("dav.example.com", BackupLocation.short("https://dav.example.com"))
        assertEquals("dav.example.com", BackupLocation.short("https://dav.example.com/"))
    }

    @Test
    fun `a blank location stays blank rather than becoming a slash`() {
        assertEquals("", BackupLocation.short(""))
        assertEquals("", BackupLocation.short("   "))
    }
}
