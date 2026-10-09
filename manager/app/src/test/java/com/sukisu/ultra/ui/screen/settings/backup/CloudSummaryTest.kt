package com.sukisu.ultra.ui.screen.settings.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudSummaryTest {

    private val unset = "未配置"

    @Test
    fun `unconfigured shows the placeholder even if the box already has text`() {
        // 点了预设但没保存：地址栏里已经有内容，此时选不了「云端」，右边就该还是"未配置"。
        assertEquals(
            unset,
            CloudSummary.of("https://dav.jianguoyun.com/dav/SukiSU-Backup", configured = false, unset = unset),
        )
    }

    @Test
    fun `configured shows only the host`() {
        assertEquals(
            "dav.jianguoyun.com",
            CloudSummary.of("https://dav.jianguoyun.com/dav/SukiSU-Backup", configured = true, unset = unset),
        )
    }

    @Test
    fun `a long nextcloud template collapses to its host`() {
        val long = "https://your-cloud/remote.php/dav/files/USERNAME/SukiSU-Backup"
        assertEquals("your-cloud", CloudSummary.of(long, configured = true, unset = unset))
    }

    @Test
    fun `host and port survive`() {
        assertEquals(
            "your-nas:5006",
            CloudSummary.of("https://your-nas:5006/SukiSU-Backup", configured = true, unset = unset),
        )
    }

    @Test
    fun `blank url falls back to the placeholder`() {
        assertEquals(unset, CloudSummary.of("   ", configured = true, unset = unset))
    }

    @Test
    fun `unparseable url is shown as typed rather than hidden`() {
        // 用户可能填的是内网名或直接粘了半截地址：解析不出来时宁可原样显示，也不能显示空白。
        assertEquals("my-nas", CloudSummary.of("my-nas", configured = true, unset = unset))
    }
}
