package com.sukisu.ultra.ui.screen.settings.backup

import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin
import java.time.Instant

/**
 * 一次备份写出来的那一组归档。
 *
 * 列表按它分组：一次备份 11 个模块就是 11 行，铺开来把"我最近备了什么"这个问题埋掉了。
 * 分组之后列表是一行一次备份，点进去才看具体有哪些模块。
 */
data class BackupGroup(
    /** 稳定标识：来源 + 内容 + 会话号。刷新后同一组还是同一个 id，界面不会跳。 */
    val id: String,
    val origin: BackupOrigin,
    val kind: BackupKind,
    /** 归档名里的会话号（时间戳）；解析不出来时退化成最新那条的时间。 */
    val session: String,
    /** 已本地化的时间标题（"10月9日 17:36"）。 */
    val label: String,
    /** 这一组里的条目，新的在前。 */
    val rows: List<BackupRow>,
    /** 整组都是恢复前自动拍下的回滚点。 */
    val isRollback: Boolean,
)

/**
 * 把扁平的行按"哪一次备份"分堆。
 *
 * 分组依据不是时间戳本身，而是归档名里的会话号：一次备份的所有归档共用同一个时间戳
 * （[com.sukisu.ultra.data.backup.ArchiveNaming.timestamp] 在打包前只取一次），所以它才是
 * "同一次"的可靠标记。用索引里的 createdAt 分堆会把一次备份拆成好几组——那是每个文件各自
 * 取的时刻，彼此差几毫秒。
 *
 * 回滚点（`pre_restore_<时间戳>_<原名>`）自带自己的时间戳，因此它自成一堆，不会混进
 * 它所保护的那次备份里。
 */
object BackupGrouping {

    /** 归档名里的 `_YYYYMMDD_HHMMSS`；回滚点名字里也有这一段，取到的是恢复那一刻。 */
    private val SESSION = Regex("_(\\d{8}_\\d{6})")

    fun sessionOf(fileName: String): String? = SESSION.find(fileName)?.groupValues?.get(1)

    /**
     * @param label 把 [BackupRow.createdAt] 变成本地化的时间标题。这一层是纯 JVM 的（这样它能进
     *   单测），格式化要 `Context`，所以从外面传进来。
     *
     * 排序是"先按来源、再按时间倒序"：两侧都勾上时列表分成"本机"和"云端"两段，同一次备份在两边
     * 各有一条，混在一起排会让人以为那是同一条被列了两遍。
     */
    fun group(rows: List<BackupRow>, label: (String) -> String): List<BackupGroup> =
        rows.groupBy { Key(it.origin, it.kind, sessionOf(it.fileName) ?: it.createdAt) }
            .map { (key, groupRows) ->
                val newest = groupRows.maxByOrNull { instantOf(it.createdAt) } ?: groupRows.first()
                BackupGroup(
                    id = "${key.origin.name}/${key.kind.name}/${key.session}",
                    origin = key.origin,
                    kind = key.kind,
                    session = key.session,
                    label = label(newest.createdAt),
                    rows = groupRows.sortedByDescending { instantOf(it.createdAt) },
                    isRollback = groupRows.all { it.isRollback },
                )
            }
            .sortedWith(
                compareBy<BackupGroup> { it.origin.ordinal }
                    .thenByDescending { instantOf(it.rows.first().createdAt) },
            )

    private data class Key(val origin: BackupOrigin, val kind: BackupKind, val session: String)

    /**
     * 按时刻排，不按字符串排。
     *
     * `Instant.toString()` 在整秒时会省掉小数部分，于是 "…:56Z" 与 "…:56.100Z" 按字典序比会得出
     * 相反的结论（'.' < 'Z'），而后者其实更晚。
     */
    private fun instantOf(iso: String): Instant =
        runCatching { Instant.parse(iso) }.getOrDefault(Instant.EPOCH)
}
