package com.sukisu.ultra.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 投稿开关默认值与存储约定。
 *
 * [SettingsRepositoryImpl] 要 Android 的 SharedPreferences 才能构造（JVM 单测里没有），
 * 所以这里锁住的是被抽出来的 [ModuleContributionPrefs]：key、默认值与升级迁移的语义。
 * 这几件东西一旦改错是静默的——用户的开关会在一次升级里被翻面，而且没有任何报错。
 */
class ModuleContributionEnabledTest {

    @Test
    fun `the key is the one the contract names`() {
        assertEquals("module_contribution_enabled", ModuleContributionPrefs.KEY)
    }

    @Test
    fun `contribution is off by default`() {
        // 默认关：投稿入口只留给主动打开的人。存量升级用户由 resolveUpgrade 单独保留为开。
        assertFalse(ModuleContributionPrefs.DEFAULT)
    }

    @Test
    fun `a missing key resolves to the default`() {
        // 没写过 key 时读 DEFAULT。存量/新装的区分不在 resolve 里做，在 resolveUpgrade 里做。
        assertFalse(ModuleContributionPrefs.resolve(contains = false))
    }

    @Test
    fun `a stored choice always wins`() {
        assertTrue(ModuleContributionPrefs.resolve(contains = true, stored = true))
        assertFalse(ModuleContributionPrefs.resolve(contains = true, stored = false))
    }

    @Test
    fun `a missing key ignores whatever the getter default happens to be`() {
        // 兜底值不该影响"没有 key"这条路径：即使有人把 getBoolean 的默认值写成 true，
        // 解析结果仍然是 DEFAULT。
        assertFalse(ModuleContributionPrefs.resolve(contains = false, stored = true))
    }

    @Test
    fun `an install from before the cutoff keeps contribution on`() {
        // 翻面前就装过 App 的存量用户：保留默认开，不被一次升级静音。
        val cutoff = ModuleContributionPrefs.UPGRADE_CUTOFF_MILLIS
        assertTrue(ModuleContributionPrefs.resolveUpgrade(firstInstallTime = cutoff - 1))
    }

    @Test
    fun `an install from after the cutoff defaults to off`() {
        // 翻面之后才装的全新用户：按新默认关。
        val cutoff = ModuleContributionPrefs.UPGRADE_CUTOFF_MILLIS
        assertFalse(ModuleContributionPrefs.resolveUpgrade(firstInstallTime = cutoff))
        assertFalse(ModuleContributionPrefs.resolveUpgrade(firstInstallTime = cutoff + 1))
    }

    @Test
    fun `a failed install-time lookup fails open to on`() {
        // firstInstallTime 查不到（0）：按存量 fail-open 保留入口——
        // 新装一定查得到，查不到的几乎只能是装了很久的设备。
        assertTrue(ModuleContributionPrefs.resolveUpgrade(firstInstallTime = 0))
    }
}
