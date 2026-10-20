package com.sukisu.ultra.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KMI 解析与比对的纯 JVM 单测。
 *
 * 测试样例覆盖真机上会出现的几类 `uname -r`：带 git hash 的 GKI、不带 hash 的 GKI、
 * 自定义内核（名字缀在尾部）、非 GKI 内核（没有 android 段）。
 */
class KernelKmiTest {

    @Test
    fun `a gki uname with git hash parses`() {
        assertEquals("5.15-android13-8", KernelKmi.kmiOf("5.15.104-android13-8-g1234567890ab"))
    }

    @Test
    fun `a gki uname without hash parses`() {
        assertEquals("6.1-android14-6", KernelKmi.kmiOf("6.1.75-android14-6"))
    }

    @Test
    fun `a custom kernel suffix is ignored`() {
        assertEquals("6.6-android15-8", KernelKmi.kmiOf("6.6.30-android15-8-SukiSU-custom"))
    }

    @Test
    fun `a non gki kernel has no kmi`() {
        assertNull(KernelKmi.kmiOf("4.14.190-perf+"))
        assertNull(KernelKmi.kmiOf("5.10.101"))
    }

    @Test
    fun `blank releases have no kmi`() {
        assertNull(KernelKmi.kmiOf(null))
        assertNull(KernelKmi.kmiOf(""))
        assertNull(KernelKmi.kmiOf("   "))
    }

    @Test
    fun `a patch level bump keeps the kmi`() {
        // 月度 OTA 只升补丁号，KMI 不动：这种恢复是正当场景，绝不能弹警告。
        assertFalse(
            KernelKmi.changed(
                "5.15.104-android13-8-g1234567890ab",
                "5.15.149-android13-8-gfedcba098765",
            ) ?: false,
        )
    }

    @Test
    fun `a kmi generation bump is a change`() {
        assertTrue(KernelKmi.changed("5.15.104-android13-8", "5.15.149-android13-9") ?: false)
    }

    @Test
    fun `an android release bump is a change`() {
        assertTrue(KernelKmi.changed("6.1.75-android14-6", "6.6.30-android15-8") ?: false)
    }

    @Test
    fun `an unknown side yields no verdict`() {
        assertNull(KernelKmi.changed(null, "5.15.104-android13-8"))
        assertNull(KernelKmi.changed("5.15.104-android13-8", "4.14.190-perf+"))
        assertNull(KernelKmi.changed(null, null))
    }
}
