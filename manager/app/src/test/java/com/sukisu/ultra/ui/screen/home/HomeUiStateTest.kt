package com.sukisu.ultra.ui.screen.home

import com.sukisu.ultra.KernelVersion
import com.sukisu.ultra.ui.util.module.LatestVersionInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `showLkmUpdate` 曾经用 `ksuVersion != currentManagerVersionCode` 判断，只要两边数字
 * 不等就一直挂「有可用更新」横幅——包括内核比管理器还新的情况。此时点安装刷进去的
 * 还是同一个 LKM，版本号仍然不等，提示永远消不掉。
 *
 * 这里锁住「只有内核更旧才算有更新」这条语义，避免再退回 `!=`。
 */
class HomeUiStateTest {

    private fun state(
        ksuVersion: Int?,
        managerVersionCode: Long = 41259L,
        lkmMode: Boolean? = true,
        isLkmBundled: Boolean = true,
        requiresNewKernel: Boolean = false,
        requiresNewManager: Boolean = false,
    ) = HomeUiState(
        kernelVersion = KernelVersion(6, 1, 0),
        ksuVersion = ksuVersion,
        managerUAPIVersion = 6,
        kernelUAPIVersion = 6,
        lkmMode = lkmMode,
        isLkmBundled = isLkmBundled,
        isManager = true,
        isManagerPrBuild = false,
        requiresNewKernel = requiresNewKernel,
        requiresNewManager = requiresNewManager,
        isRootAvailable = true,
        isSafeMode = false,
        isLateLoadMode = false,
        checkUpdateEnabled = true,
        latestVersionInfo = LatestVersionInfo(),
        currentManagerVersionCode = managerVersionCode,
        systemInfo = SystemInfo(
            kernelVersion = "6.1.0",
            managerVersion = "mod",
            deviceModel = "23116PN5BC",
            kernelFullVersion = "v4.2.1-r2-20437a36@ci",
            fingerprint = "test",
            selinuxStatus = "Enforcing",
            seccompStatus = 2,
        ),
    )

    @Test
    fun `kernel older than manager asks for update`() {
        assertTrue(state(ksuVersion = 41106, managerVersionCode = 41259L).showLkmUpdate)
    }

    @Test
    fun `kernel equal to manager has no update`() {
        assertFalse(state(ksuVersion = 41259, managerVersionCode = 41259L).showLkmUpdate)
    }

    /** 本用例是本次修复的核心：旧实现（`!=`）在这里会误报。 */
    @Test
    fun `kernel newer than manager has no update`() {
        assertFalse(state(ksuVersion = 41300, managerVersionCode = 41259L).showLkmUpdate)
    }

    @Test
    fun `unknown kernel version never asks for update`() {
        assertFalse(state(ksuVersion = null).showLkmUpdate)
    }

    @Test
    fun `non bundled lkm has no update banner`() {
        assertFalse(state(ksuVersion = 41106, isLkmBundled = false).showLkmUpdate)
    }

    @Test
    fun `non lkm mode has no update banner`() {
        assertFalse(state(ksuVersion = 41106, lkmMode = false).showLkmUpdate)
    }

    @Test
    fun `requires new manager suppresses lkm update banner`() {
        assertFalse(state(ksuVersion = 41106, requiresNewManager = true).showLkmUpdate)
    }

    @Test
    fun `requires new kernel suppresses lkm update banner`() {
        assertFalse(state(ksuVersion = 41106, requiresNewKernel = true).showLkmUpdate)
    }
}
