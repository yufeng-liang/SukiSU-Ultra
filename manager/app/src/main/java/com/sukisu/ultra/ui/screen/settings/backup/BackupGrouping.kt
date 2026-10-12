package com.sukisu.ultra.ui.screen.settings.backup

import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin
import java.time.Instant

/**
 * 一次备份写出来的那一组归档。
 *
 * 列表按它分组：一次备份 11 个模块就是 11 行，铺开来把"我最近备了什么"这个问题埋掉了。
 * 分组之后列表是一行一次备份，点进去才看具体有哪些模块。
 *
 * 一行装的是**一次备份动作**的全部内容与全部去向：同一次里勾了模块和仓库源配置、又勾了本机和
 * 云端，仍然只是一行——卡片上写出包含哪几类（[kinds]）和写到了哪几侧（[origins]）。每一行
 * 条目自己带着来源，恢复/删除按它走，所以合并不会让人动错那一侧。
 */
data class BackupGroup(
    /** 稳定标识：就是会话号。刷新后同一组还是同一个 id，界面不会跳。 */
    val id: String,
    /** 这一次备份写到了哪几侧，按 [BackupOrigin.ordinal] 排序（本机在前）。 */
    val origins: List<BackupOrigin>,
    /** 这一次备份包含哪几类内容，按 [BackupKind.ordinal] 排序（列表里的固定顺序）。 */
    val kinds: List<BackupKind>,
    /** 归档名里的会话号（时间戳）；解析不出来时退化成最新那条的时间。 */
    val session: String,
    /** 已本地化的时间标题（"10月9日 17:36"）。 */
    val label: String,
    /** 这一组里的条目，新的在前。 */
    val rows: List<BackupRow>,
)

/**
 * 把扁平的行按"哪一次备份"分堆。
 *
 * 分组依据不是时间戳本身，而是归档名里的会话号：一次备份的所有归档共用同一个时间戳
 * （[com.sukisu.ultra.data.backup.ArchiveNaming.timestamp] 在打包前只取一次），所以它才是
 * "同一次"的可靠标记。用索引里的 createdAt 分堆会把一次备份拆成好几组——那是每个文件各自
 * 取的时刻，彼此差几毫秒。
 *
 * 分堆键里**不含内容类别，也不含来源**：一次备份勾了模块和仓库源配置、写到了本机和云端，
 * 那是同一行里的几条内容、两侧副本，不是几次备份。拆开会让"我最近备了什么"又变成好几行。
 * 列表因此是纯按时间倒序的一段，不再按来源分段。
 */
object BackupGrouping {

    /** 归档名里的 `_YYYYMMDD_HHMMSS`，即备份那一刻的时间戳。 */
    private val SESSION = Regex("_(\\d{8}_\\d{6})")

    fun sessionOf(fileName: String): String? = SESSION.find(fileName)?.groupValues?.get(1)

    /**
     * @param label 把 [BackupRow.createdAt] 变成本地化的时间标题。这一层是纯 JVM 的（这样它能进
     *   单测），格式化要 `Context`，所以从外面传进来。
     */
    fun group(rows: List<BackupRow>, label: (String) -> String): List<BackupGroup> =
        rows.groupBy { sessionOf(it.fileName) ?: it.createdAt }
            .map { (session, groupRows) ->
                val newest = groupRows.maxByOrNull { instantOf(it.createdAt) } ?: groupRows.first()
                BackupGroup(
                    id = session,
                    origins = groupRows.map { it.origin }.distinct().sortedBy { it.ordinal },
                    kinds = groupRows.map { it.kind }.distinct().sortedBy { it.ordinal },
                    session = session,
                    label = label(newest.createdAt),
                    rows = groupRows.sortedByDescending { instantOf(it.createdAt) },
                )
            }
            .sortedByDescending { group -> group.rows.maxOf { instantOf(it.createdAt) } }

    /**
     * 按时刻排，不按字符串排。
     *
     * `Instant.toString()` 在整秒时会省掉小数部分，于是 "…:56Z" 与 "…:56.100Z" 按字典序比会得出
     * 相反的结论（'.' < 'Z'），而后者其实更晚。
     */
    private fun instantOf(iso: String): Instant =
        runCatching { Instant.parse(iso) }.getOrDefault(Instant.EPOCH)
}
