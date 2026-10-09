package com.sukisu.ultra.ui.screen.settings.backup

import com.sukisu.ultra.data.backup.BackupDefaults
import com.sukisu.ultra.data.backup.BackupOrigin

/**
 * 「备份存在哪」。
 *
 * 两处要用它，而且需要的粒度不同：
 * - 列表上方那行常驻文字要**完整**位置，用户过几天回来找文件时得能照着打开；
 * - 备份成功的提示里要**短**位置，snackbar 只有一行，塞一整条 URL 会折行，
 *   而完整位置就在屏幕上方，写个目录名足够对上号。
 */
object BackupLocation {

    /** 完整位置：本机是固定目录，云端就是配置里那个地址（归档直接落在它下面，不再套一层）。 */
    fun full(origin: BackupOrigin, cloudUrl: String): String =
        if (origin == BackupOrigin.CLOUD) cloudUrl.trim() else BackupDefaults.LOCAL_DIR

    /**
     * 提示里用的短位置：最后一段目录名。
     *
     * 尾部斜杠先去掉，否则 `https://dav.example.com/dav/` 会取到空串。取不到（地址里没有 `/`）
     * 时原样返回——`https://dav.example.com` 走 `substringAfterLast` 正好得到主机名，可读。
     */
    fun short(location: String): String {
        val trimmed = location.trim().trimEnd('/')
        if (trimmed.isEmpty()) return trimmed
        return trimmed.substringAfterLast('/').ifEmpty { trimmed }
    }
}
