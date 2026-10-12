package com.sukisu.ultra.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模块仓库页免责说明的存储约定。
 *
 * [SettingsRepositoryImpl] 要 Android 的 SharedPreferences 才能构造（JVM 单测里没有），
 * 所以这里锁住被抽出来的 [ModuleRepoDisclaimerPrefs]：key、默认值，以及"什么情况下还要再弹"。
 *
 * 判据只有一句"没勾过就弹"，但它决定每次进入模块仓库页要不要打断用户：默认值一旦翻面，
 * 没表过态的用户会在一次升级里集体不再看到免责说明，而这种失败没有任何报错。
 */
class ModuleRepoDisclaimerTest {

    @Test
    fun `the key is the one the contract names`() {
        assertEquals("module_repo_disclaimer_accepted", ModuleRepoDisclaimerPrefs.KEY)
    }

    @Test
    fun `the disclaimer is not accepted by default`() {
        // 默认没接受：免责说明至少要完整出现过一次。
        assertFalse(ModuleRepoDisclaimerPrefs.DEFAULT)
    }

    @Test
    fun `an unaccepted disclaimer is shown`() {
        assertTrue(ModuleRepoDisclaimerPrefs.shouldShow(accepted = false))
    }

    @Test
    fun `an accepted disclaimer is not shown again`() {
        // 勾了"不再提示"就不再弹——这是那个勾选框唯一的含义。
        assertFalse(ModuleRepoDisclaimerPrefs.shouldShow(accepted = true))
    }

    @Test
    fun `with no stored value the default decides`() {
        // 没写过 key 时走默认值（false）→ 要弹，也就是每次进入都弹。
        assertTrue(ModuleRepoDisclaimerPrefs.shouldShow())
    }
}
