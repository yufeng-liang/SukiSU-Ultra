package com.sukisu.ultra.ui.screen.settings.backup

import com.sukisu.ultra.data.backup.BackupDefaults
import com.sukisu.ultra.data.backup.BackupOrigin

/**
 * 「备份存在哪」。
 *
 * 列表上方那行常驻文字要**完整**位置：用户过几天回来找文件时得能照着打开。短形式（只留最后一段
 * 目录名）随结果弹窗改成表格之后没有了用处——表格按"本地/云端"分组，位置由那一行表头说了。
 */
object BackupLocation {

    /** 完整位置：本机是固定目录，云端就是配置里那个地址（归档直接落在它下面，不再套一层）。 */
    fun full(origin: BackupOrigin, cloudUrl: String): String =
        if (origin == BackupOrigin.CLOUD) cloudUrl.trim() else BackupDefaults.LOCAL_DIR
}
