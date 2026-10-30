package com.sukisu.ultra.ui.screen.home

import androidx.compose.runtime.Immutable
import com.sukisu.ultra.KernelVersion
import com.sukisu.ultra.ui.util.module.LatestVersionInfo

@Immutable
data class HomeLoadState(
    val content: HomeUiState? = null,
    val isRefreshing: Boolean = true,
    val error: String? = null,
)

@Immutable
data class HomeUiState(
    val kernelVersion: KernelVersion,
    val ksuVersion: Int?,
    val managerUAPIVersion: Int,
    val kernelUAPIVersion: Int?,
    val lkmMode: Boolean?,
    val isLkmBundled: Boolean,
    val isManager: Boolean,
    val isManagerPrBuild: Boolean,
    val requiresNewKernel: Boolean,
    val requiresNewManager: Boolean,
    val isRootAvailable: Boolean,
    val isSafeMode: Boolean,
    val isLateLoadMode: Boolean,
    val checkUpdateEnabled: Boolean,
    val latestVersionInfo: LatestVersionInfo,
    val currentManagerVersionCode: Long,
    val systemInfo: SystemInfo,
    val showFullStatus: Boolean = true,
    val hideHomeWarnings: Boolean = false,
    // Root without a SukiSU kernel: pages are browsable but kernel features are unavailable.
    val isPreviewMode: Boolean = false,
    val hideVersionMismatchWarning: Boolean = false,
    // Whether the kernel supports the version-spoof extension. When false the
    // home warning card is meaningless (the user cannot spoof to resolve the mismatch).
    val isVersionSpoofSupported: Boolean = true,
    // Whether the user has actually set a spoofed version value. The home warning is only
    // meaningful when this is true; without a spoof the version mismatch simply means an
    // update is needed, not that the spoof is broken.
    val isVersionSpoofed: Boolean = false,
    // Null until loaded through the root shell; kpmCount stays null when KPM is unavailable.
    val superuserCount: Int? = null,
    val moduleCount: Int? = null,
    val kpmCount: Int? = null,
    // LKM 内容指纹，两侧都来自构建期 kernel/ 子树的 tree hash：
    // bundledLkmRev 是「本 APK 自带的 .ko」，kernelLkmRev 是「设备上正在跑的 .ko」
    // （内核把它编在完整版本串尾部，形如 v4.3.0-abcd1234@ci+lkm.b44da79c）。
    // 任一侧为空说明对端还不支持该指纹（旧内核 / tarball 构建），此时退回比版本号。
    val bundledLkmRev: String = "",
    val kernelLkmRev: String = "",
    val customHomeTitle: String = "",
) {
    val isSELinuxPermissive: Boolean
        get() = systemInfo.selinuxStatus == "Permissive"

    val showGkiWarning: Boolean
        get() = ksuVersion != null && lkmMode == false

    val showLkmUpdate: Boolean
        get() = isManager &&
                lkmMode == true &&
                isLkmBundled &&
                kernelLkmOutdated &&
                // 收紧成「LKM 真的变了才提示」：只升级管理器（改 UI、改文案、改 CI）
                // 不会改变 kernel/ 子树的 hash，两侧指纹相同就说明这次 APK 里装的还是
                // 同一个 .ko，刷内核除了重启没有任何收益。
                lkmContentChanged &&
                !requiresNewKernel &&
                !requiresNewManager

    // 只有内核比管理器内附的 LKM 旧，才叫「有可用更新」。
    // 这里必须是「小于」而不是「不等于」：APK 里的 LKM 版本号与 versionCode 未必相等
    // （构建链路两处各算一次就会出现偏差），用 != 的话装完更新版本号依旧不等，
    // 横幅永远消不掉——用户会陷入「点了安装、提示还在」的死循环。
    private val kernelLkmOutdated: Boolean
        get() = ksuVersion?.toLong()?.let { it < currentManagerVersionCode } ?: false

    private val lkmContentChanged: Boolean
        get() = bundledLkmRev.isEmpty() || kernelLkmRev.isEmpty() || kernelLkmRev != bundledLkmRev

    // Jailbreak mode runs on locked bootloaders, so flashing a boot image would brick the device.
    val canInstallKernelUpdate: Boolean
        get() = lkmMode == true && !isLateLoadMode

    val showCustomLkmBadge: Boolean
        get() = lkmMode == true && !isLkmBundled

    val isVersionMismatch: Boolean
        get() = isManager && (kernelUAPIVersion != managerUAPIVersion || ksuVersion?.toUInt()?.toLong() != currentManagerVersionCode)

    val showVersionMismatchWarning: Boolean
        get() = isVersionMismatch && isVersionSpoofSupported && isVersionSpoofed &&
            !hideVersionMismatchWarning

    val showRootWarning: Boolean
        get() = ksuVersion != null && !isRootAvailable

    val showManagerPrBuildWarning: Boolean
        get() = isManager && isManagerPrBuild

    val hasUpdate: Boolean
        get() = latestVersionInfo.versionCode > currentManagerVersionCode
}

@Immutable
data class HomeActions(
    val onInstallClick: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onJailbreakClick: () -> Unit = {},
    val onSuperuserClick: () -> Unit = {},
    val onModuleClick: () -> Unit = {},
    val onReleaseUpdateClick: () -> Unit = {},
    val onOpenVersionSpoof: () -> Unit = {},
)

private val LKM_REV_SUFFIX = Regex("""\+lkm\.([0-9a-fA-F]{6,40})\s*$""")

/**
 * 从内核完整版本串里取 LKM 指纹：`v4.3.0-abcd1234@ci+lkm.b44da79c` -> `b44da79c`。
 *
 * 取不到就返回空串（旧内核没有该后缀、无 .git 构建、或者用户开了版本伪装把整串换掉了），
 * 调用方据此退回「比版本号」的老逻辑，不会因为缺指纹就漏掉真正必要的更新提示。
 */
internal fun parseLkmRev(fullVersion: String?): String =
    fullVersion?.trim()?.let { LKM_REV_SUFFIX.find(it)?.groupValues?.get(1) }.orEmpty()
