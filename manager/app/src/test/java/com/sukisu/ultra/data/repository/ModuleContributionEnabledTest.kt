package com.sukisu.ultra.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 投稿开关默认值与存储约定。
 *
 * [SettingsRepositoryImpl] 要 Android 的 SharedPreferences 才能构造（JVM 单测里没有），
 * 所以这里锁住的是被抽出来的 [ModuleContributionPrefs]：key 与默认值的语义。
 * 这两件东西一旦改错是静默的——老用户的选择会在一次升级里被翻面，而且没有任何报错。
 */
class ModuleContributionEnabledTest {

    @Test
    fun `the key is the one the contract names`() {
        assertEquals("module_contribution_enabled", ModuleContributionPrefs.KEY)
    }

    @Test
    fun `contribution is on by default`() {
        // 默认开：投稿入口是新增功能，老用户升级上来不该在没表态过的情况下被静音。
        assertTrue(ModuleContributionPrefs.DEFAULT)
    }

    @Test
    fun `a missing key upgrades to on not off`() {
        // 从没有这个开关的版本升级上来的用户：prefs 里没有 key。
        // 这里必须回默认（开），否则一次升级会让所有人的投稿入口集体消失。
        assertTrue(ModuleContributionPrefs.resolve(contains = false))
    }

    @Test
    fun `a stored choice always wins`() {
        assertTrue(ModuleContributionPrefs.resolve(contains = true, stored = true))
        assertFalse(ModuleContributionPrefs.resolve(contains = true, stored = false))
    }

    @Test
    fun `a missing key ignores whatever the getter default happens to be`() {
        // 兜底值不该影响"没有 key"这条路径：即使有人把 getBoolean 的默认值写成 false，
        // 解析结果仍然是 DEFAULT。
        assertTrue(ModuleContributionPrefs.resolve(contains = false, stored = false))
    }
}
