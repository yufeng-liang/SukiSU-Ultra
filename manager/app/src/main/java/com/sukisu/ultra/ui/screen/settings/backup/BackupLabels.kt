package com.sukisu.ultra.ui.screen.settings.backup

import androidx.annotation.StringRes
import com.sukisu.ultra.R
import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin

/**
 * 备份界面里那几个"跟着语言走"的短标签。
 *
 * 单独放一处：分组卡片（两个主题各画一遍）、备份详情页和提示消息都要说"本机/云端"和
 * "模块/原厂 boot 镜像"，各写一份 `if (kind == BOOT) …` 迟早会有一处漏改。
 */
object BackupLabels {

    @StringRes
    fun tab(tab: BackupTab): Int =
        if (tab == BackupTab.RESTORE) R.string.backup_tab_restore else R.string.backup_tab_backup

    @StringRes
    fun origin(origin: BackupOrigin): Int =
        if (origin == BackupOrigin.CLOUD) R.string.backup_origin_cloud else R.string.backup_origin_local

    @StringRes
    fun kind(kind: BackupKind): Int =
        if (kind == BackupKind.BOOT) R.string.backup_kind_boot else R.string.backup_kind_module

    /** 分组卡片上"这一组是什么"那一栏。 */
    @StringRes
    fun groupKind(group: BackupGroup): Int = kind(group.kind)

    /** 分组卡片的副标题：来源（两侧都勾上时才写）· 内容 · 项数。 */
    fun groupSummary(originLabel: String?, kindLabel: String, countText: String): String =
        listOfNotNull(originLabel, kindLabel, countText).joinToString(BackupListFormatter.SEPARATOR)
}
