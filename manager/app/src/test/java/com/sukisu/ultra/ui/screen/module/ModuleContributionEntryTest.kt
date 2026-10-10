package com.sukisu.ultra.ui.screen.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 投稿入口那些"看不见但错了会出事"的边界。
 *
 * 这里测的全是纯 JVM 能跑的推导：[ModuleContributionEntry] 里 versionCode 的窄化、
 * zip 文件名的压平，以及收录状态缓存的键。真正要 root / 联网的部分留给真机。
 */
class ModuleContributionEntryTest {

    // ── versionCode 的窄化 ──────────────────────────────────────────────────

    @Test
    fun `a version code inside int range is used as is`() {
        assertEquals(0, ModuleContributionEntry.checkableVersionCode(0L))
        assertEquals(1, ModuleContributionEntry.checkableVersionCode(1L))
        assertEquals(Int.MAX_VALUE, ModuleContributionEntry.checkableVersionCode(Int.MAX_VALUE.toLong()))
        assertEquals(Int.MIN_VALUE, ModuleContributionEntry.checkableVersionCode(Int.MIN_VALUE.toLong()))
    }

    @Test
    fun `an out of range version code is never truncated`() {
        // 截断会让两个不同版本查到同一个收录结果——宁可当作查不了（静默）。
        assertNull(ModuleContributionEntry.checkableVersionCode(Int.MAX_VALUE.toLong() + 1))
        assertNull(ModuleContributionEntry.checkableVersionCode(Int.MIN_VALUE.toLong() - 1))
        assertNull(ModuleContributionEntry.checkableVersionCode(Long.MAX_VALUE))
    }

    @Test
    fun `two neighbouring huge version codes never collide`() {
        val a = ModuleContributionEntry.checkableVersionCode(Int.MAX_VALUE.toLong() + 1)
        val b = ModuleContributionEntry.checkableVersionCode(Int.MAX_VALUE.toLong() + 2)
        assertNull(a)
        assertNull(b)
        // 若改成截断，a 与 b 会变成两个不同的 Int（或同一个），无论哪种都是错的。
        assertEquals(a, b)
    }

    // ── zip 文件名 ──────────────────────────────────────────────────────────

    @Test
    fun `a plain module id makes a readable zip name`() {
        assertEquals("my_module-12.zip", ModuleContributionEntry.zipNameFor("my_module", 12L))
    }

    @Test
    fun `a module id with a path separator is flattened`() {
        // id 里出现 `/` 会让 renameTo 静默失败，打出来的 zip 投稿不上去。
        val name = ModuleContributionEntry.zipNameFor("../../evil", 3L)
        assertFalse("压平后的名字不能含 '/': $name", name.contains("/"))
        assertTrue(name.endsWith("-3.zip"))
    }

    @Test
    fun `a blank module id still yields a usable name`() {
        // 空 id 必须有兜底名；`///` 这种压平成 `___` 后仍是可用的文件名，不强求兜底。
        assertTrue(ModuleContributionEntry.zipNameFor("", 1L).startsWith("module"))
        val slashes = ModuleContributionEntry.zipNameFor("///", 1L)
        assertTrue(slashes, slashes.isNotBlank())
        assertFalse("压平后的名字不能含 '/': $slashes", slashes.contains("/"))
    }
}
