package com.sukisu.ultra.ui.screen.settings.backup

import com.sukisu.ultra.data.backup.BackupEntry
import com.sukisu.ultra.data.backup.ModuleBackupMeta
import com.sukisu.ultra.data.backup.RollbackPolicy
import java.util.Locale

data class BackupRow(
    val id: String,
    val title: String,
    val subtitle: String,
    val fileName: String,
    /** 恢复前自动拍下的安全网条目。UI 要标出来，否则用户会把它当成一份普通备份。 */
    val isRollback: Boolean = false,
)

object BackupListFormatter {

    /**
     * 一条 message 里拼多段信息用的分隔符。
     *
     * 只有这一个定义：[BackupViewModel.showEmpty] 按它分段去重，所以它同时是"拼接"与"去重"的契约，
     * 两边各写一份字面量的话，改动一边就会让"应用密码只提示一次"这类保证悄悄失效。
     */
    const val SEPARATOR = " · "

    fun rows(entries: List<BackupEntry>, metas: Map<String, ModuleBackupMeta>): List<BackupRow> = entries.map { entry ->
        val meta = entry.metaFileName?.let { metas[it] }
        BackupRow(
            id = entry.fileName,
            title = meta?.name?.takeIf { it.isNotBlank() } ?: entry.entryId,
            subtitle = listOfNotNull(
                meta?.versionName?.takeIf { it.isNotBlank() }?.let { "v$it" },
                humanSize(entry.sizeBytes),
                entry.createdAt,
                "disabled".takeIf { meta?.disabled == true },
            ).joinToString(SEPARATOR),
            fileName = entry.fileName,
            isRollback = RollbackPolicy.isRollback(entry),
        )
    }

    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB")
        var value = bytes.toDouble() / 1024
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }

    /**
     * 把新的提示并进已有的 message。
     *
     * UI 只有一条 message 通道，但"刚做完的操作结果"和"紧接着刷新列表也失败了"是两件事，
     * 直接覆盖就等于告诉用户什么都没发生。按 [SEPARATOR] 分段、去空白、去重后重新拼——
     * 去重是必要的：同一句话可能被两个来源各说一遍，不去重就会在同一行里出现两次。
     */
    fun mergeMessages(existing: String?, incoming: String): String =
        listOfNotNull(existing, incoming)
            .flatMap { part -> part.split(SEPARATOR) }
            .map { part -> part.trim() }
            .filter { part -> part.isNotEmpty() }
            .distinct()
            .joinToString(SEPARATOR)
}
