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
}
