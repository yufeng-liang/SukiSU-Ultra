package com.sukisu.ultra.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * checkNewVersion() 的资产名识别。
 *
 * 这个函数是更新流程的唯一入口：认错会把用户引导去装签名不同的 APK（覆盖安装会被
 * Android 拒绝，用户只能卸载重装并丢掉仓库源配置）；漏认只是不提示，代价小得多。
 * 所以这几组用例的重点是「哪些名字必须答错」，而不是能认出多少种。
 */
class SelfUpdateApkTest {

    @Test
    fun `the fork release apk matches`() {
        // versionName 现为 mod，文件名形如 SukiSU_mod_<versionCode>-release.apk。
        assertEquals(41151L, matchSelfUpdateApk("SukiSU_mod_41151-release.apk"))
    }

    @Test
    fun `the version name may contain dots`() {
        // 版本名历史上是 custom-repo&backup，上传时文件名里的 & 会被替换成 .，
        // 所以正则用的是 .+，不能假设分隔符只有下划线。留着这条防改回去时踩坑。
        assertEquals(41090L, matchSelfUpdateApk("SukiSU_custom.repo.backup_41090-release.apk"))
    }

    @Test
    fun `a lkm asset does not match`() {
        assertNull(matchSelfUpdateApk("android14-6.1-aarch64_kernelsu.ko"))
    }

    @Test
    fun `the spoofed variant does not match`() {
        // 随机包名变体，用在隐藏管理器身份的场景，不该被当成常规升级推给用户。
        assertNull(matchSelfUpdateApk("SukiSU_mod_41151-release-spoofed.apk"))
    }

    @Test
    fun `an upstream apk naming still does not match on purpose`() {
        // 官方命名 SukiSU_v4.2.1_40994-release.apk：即使误发了也不该本机认领，
        // 它的签名与本机不同。
        assertNull(matchSelfUpdateApk("SukiSU_v4.2.1_40994-release.apk"))
    }

    @Test
    fun `a name without a version does not match`() {
        assertNull(matchSelfUpdateApk("SukiSU_mod-release.apk"))
    }
}
