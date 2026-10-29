package com.sukisu.ultra.ui.screen.home

import com.sukisu.ultra.KernelVersion
import com.sukisu.ultra.ui.util.module.LatestVersionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `showLkmUpdate` 曾经用 `ksuVersion != currentManagerVersionCode` 判断，只要两边数字
 * 不等就一直挂「有可用更新」横幅——包括内核比管理器还新的情况。此时点安装刷进去的
 * 还是同一个 LKM，版本号仍然不等，提示永远消不掉。
 *
 * 改成「小于」之后又暴露第二个问题：versionCode 每提交都涨，只改管理器 UI 也会让
 * 内核看起来「旧」，于是每次发版都催人刷内核，而 LKM 二进制根本没变。
 *
 * 现在用两侧的 LKM 指纹（构建期 kernel/ 子树的 tree hash）做最终裁决：
 * 指纹一致 = 这次升级没带新 .ko，不提示；任一侧缺指纹则退回比版本号。
 */
class HomeUiStateTest {

    private fun state(
        ksuVersion: Int?,
        managerVersionCode: Long = 41259L,
        lkmMode: Boolean? = true,
        isLkmBundled: Boolean = true,
        requiresNewKernel: Boolean = false,
        requiresNewManager: Boolean = false,
        bundledLkmRev: String = "",
        kernelLkmRev: String = "",
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
        bundledLkmRev = bundledLkmRev,
        kernelLkmRev = kernelLkmRev,
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

    /**
     * 本次收紧的核心：内核版本号落后，但两侧 LKM 指纹一致 —— 说明这次 APK 只是
     * 改了管理器（UI/文案/CI），没带新 .ko，不该催用户刷内核。
     */
    @Test
    fun `same lkm fingerprint suppresses banner despite older kernel`() {
        assertFalse(
            state(
                ksuVersion = 41260,
                managerVersionCode = 41263L,
                bundledLkmRev = "b44da79c",
                kernelLkmRev = "b44da79c",
            ).showLkmUpdate
        )
    }

    @Test
    fun `different lkm fingerprint asks for update`() {
        assertTrue(
            state(
                ksuVersion = 41260,
                managerVersionCode = 41263L,
                bundledLkmRev = "c0ffee12",
                kernelLkmRev = "b44da79c",
            ).showLkmUpdate
        )
    }

    /** 旧内核不报指纹，退回比版本号，宁可多提示也不能漏掉真正需要的更新。 */
    @Test
    fun `missing kernel fingerprint falls back to version compare`() {
        assertTrue(
            state(
                ksuVersion = 41260,
                managerVersionCode = 41263L,
                bundledLkmRev = "b44da79c",
            ).showLkmUpdate
        )
    }

    @Test
    fun `missing bundled fingerprint falls back to version compare`() {
        assertTrue(
            state(
                ksuVersion = 41260,
                managerVersionCode = 41263L,
                kernelLkmRev = "b44da79c",
            ).showLkmUpdate
        )
    }

    @Test
    fun `parse lkm rev from kernel full version`() {
        assertEquals("b44da79c", parseLkmRev("v4.3.0-869992ff@ci+lkm.b44da79c"))
        assertEquals("b44da79c", parseLkmRev("v4.3.0-869992ff@release+lkm.b44da79c\n"))
    }

    /** 旧内核、tarball 构建、以及被版本伪装改写的串都解析不出指纹，必须返回空串。 */
    @Test
    fun `parse lkm rev returns empty when suffix absent`() {
        assertEquals("", parseLkmRev("v4.2.1-r2-20437a36@ci"))
        assertEquals("", parseLkmRev("v4.2.1-custom@spoof"))
        assertEquals("", parseLkmRev(null))
        assertEquals("", parseLkmRev(""))
    }
}
