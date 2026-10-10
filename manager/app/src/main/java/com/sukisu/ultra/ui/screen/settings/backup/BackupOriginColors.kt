package com.sukisu.ultra.ui.screen.settings.backup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.sukisu.ultra.data.backup.BackupOrigin
import com.sukisu.ultra.ui.theme.isInDarkTheme

/**
 * 「本地 / 云端」标签的配色：本地黄底、云端蓝底。
 *
 * 不取主题里的 container 色：两套 container 都由同一个 key color 生成，摆在一起几乎是同一个
 * 颜色——而"这份备份在哪一侧"正是恢复前最该一眼看清的事，所以这里用两组固定的色相。
 *
 * 四组（浅/深 × 本地/云端）都按 WCAG 相对亮度算过，字色与底色的对比度在 7:1 以上（AA 要求
 * 4.5:1）。标签字号只有 11–12sp，余量是故意留的：它小到不能靠"大概看得清"过关。
 */
object BackupOriginColors {

    @Composable
    @ReadOnlyComposable
    fun container(origin: BackupOrigin): Color = if (origin == BackupOrigin.CLOUD) {
        if (isInDarkTheme()) CLOUD_DARK else CLOUD_LIGHT
    } else {
        if (isInDarkTheme()) LOCAL_DARK else LOCAL_LIGHT
    }

    @Composable
    @ReadOnlyComposable
    fun onContainer(origin: BackupOrigin): Color = if (origin == BackupOrigin.CLOUD) {
        if (isInDarkTheme()) ON_CLOUD_DARK else ON_CLOUD_LIGHT
    } else {
        if (isInDarkTheme()) ON_LOCAL_DARK else ON_LOCAL_LIGHT
    }

    /** 浅色：底色淡、字色深。深色：底色沉、字色亮。 */
    private val LOCAL_LIGHT = Color(0xFFFFF3C4)
    private val ON_LOCAL_LIGHT = Color(0xFF5A4200)
    private val LOCAL_DARK = Color(0xFF4A3708)
    private val ON_LOCAL_DARK = Color(0xFFFFD97A)
    private val CLOUD_LIGHT = Color(0xFFD6E8FF)
    private val ON_CLOUD_LIGHT = Color(0xFF0A3D91)
    private val CLOUD_DARK = Color(0xFF143A6B)
    private val ON_CLOUD_DARK = Color(0xFFAFD3FF)
}
