package com.sukisu.ultra.ui.screen.settings.backup

import androidx.annotation.StringRes
import com.sukisu.ultra.R
import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin

/**
 * 备份界面里那几个"跟着语言走"的短标签。
 *
 * 单独放一处：分组卡片（两个主题各画一遍）、备份详情页和提示消息都要说"本机/云端"和
 * "模块/分区快照"，各写一份 `if (kind == PARTITION) …` 迟早会有一处漏改。
 */
object BackupLabels {

    @StringRes
    fun tab(tab: BackupTab): Int =
        if (tab == BackupTab.RESTORE) R.string.backup_tab_restore else R.string.backup_tab_backup

    @StringRes
    fun origin(origin: BackupOrigin): Int =
        if (origin == BackupOrigin.CLOUD) R.string.backup_origin_cloud else R.string.backup_origin_local

    @StringRes
    fun kind(kind: BackupKind): Int = when (kind) {
        // 旧的 BOOT（原厂 boot 镜像）不再有源，也不会出现在列表里，走到这里只是兜底。
        BackupKind.PARTITION -> R.string.backup_kind_partition
        BackupKind.REPO_CONFIG -> R.string.backup_kind_repo
        else -> R.string.backup_kind_module
    }

    /**
     * 分组卡片上"这一组是什么"那一栏。
     *
     * 一次备份可能不止一类内容（模块 + 仓库源配置），所以返回列表：调用方按同一顺序取字符串、
     * 再用分隔符拼起来，措辞不会两个主题各写一遍。
     */
    fun groupKinds(group: BackupGroup): List<Int> = group.kinds.map { kind(it) }

    /** 分组卡片上"写到哪几侧"那一栏，同上：一次备份可能本机和云端各写了一份。 */
    fun groupOrigins(group: BackupGroup): List<Int> = group.origins.map { origin(it) }

    /** 分组卡片的副标题：内容 · 项数（写到哪几侧由来源标签写，不在这里重复）。 */
    fun groupSummary(kindLabel: String, countText: String): String =
        listOfNotNull(kindLabel, countText).joinToString(BackupListFormatter.SEPARATOR)
}
