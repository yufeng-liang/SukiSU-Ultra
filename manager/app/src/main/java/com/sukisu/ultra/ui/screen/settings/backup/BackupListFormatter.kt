package com.sukisu.ultra.ui.screen.settings.backup

import com.sukisu.ultra.data.backup.BackupEntry
import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin
import com.sukisu.ultra.data.backup.ModuleBackupMeta
import com.sukisu.ultra.data.backup.RollbackPolicy
import java.util.Locale

data class BackupRow(
    val id: String,
    val title: String,
    /**
     * 标题下面那行：版本 · 大小（· 已禁用）。
     *
     * 不写时间：条目是按"哪一次备份"分组的，同一组里每一行的时间都一样，而那个时刻已经写在
     * 组标题上了——11 行都缀一句"3 分钟前"只是噪音。
     */
    val subtitle: String,
    val fileName: String,
    /**
     * 这一条存在哪。
     *
     * 列表可以同时列本机和云端（两边都勾上时），而"恢复/导出"必须回到它自己那一侧——
     * 只看文件名的话，同名的两条会指到错误的后端。
     */
    val origin: BackupOrigin,
    /** 来源标签（已本地化）。只有一个来源时界面不显示它，避免每行都挂一个没信息量的词。 */
    val originLabel: String,
    /**
     * 这一条是模块还是原厂镜像。
     *
     * 界面需要它来区分后果：恢复模块只是重装一个模块，恢复 boot 会把设备带回未 root 状态，
     * 必须先问一次。列表里两种混在一起，光看标题分不出来。
     */
    val kind: BackupKind,
    /**
     * 索引里的原始时间戳（ISO-8601）。
     *
     * 列表按"哪一次备份"分组，而组标题要的是时刻本身——[subtitle] 里那个已经本地化、也已经被
     * 拼进一句话里的时间拿不回来。
     */
    val createdAt: String,
    /** 恢复前自动拍下的安全网条目。UI 要标出来，否则用户会把它当成一份普通备份。 */
    val isRollback: Boolean = false,
)

/**
 * 列表里的一项：一条归档 + 它在哪一侧。
 *
 * 本地和云端各有一份索引，同一条模块在两边的文件名是一样的，所以来源必须跟着条目走。
 */
data class OriginEntry(val origin: BackupOrigin, val entry: BackupEntry)

/**
 * 列表里由调用方提供的那几段文案。
 *
 * [BackupListFormatter] 是纯 JVM 的（这样它能进单测），拿不到 `Context`；而这几段必须跟着语言走，
 * 所以从外面传进来，不在这里写死英文。
 */
data class BackupRowLabels(
    /** boot 行的标题前缀：那一行的 id 是一串 sha1，光看它没人知道这是原厂镜像。 */
    val bootTitle: String,
    /** 模块处于禁用状态时缀在副标题里的词。 */
    val disabled: String,
    /** 来源标签：本机 / 云端。 */
    val originLabel: (BackupOrigin) -> String,
)

object BackupListFormatter {

    /**
     * 一条 message 里拼多段信息用的分隔符。
     *
     * 只有这一个定义：[mergeMessages] 按它分段去重，所以它同时是"拼接"与"去重"的契约，
     * 两边各写一份字面量的话，改动一边就会让"应用密码只提示一次"这类保证悄悄失效。
     */
    const val SEPARATOR = " · "

    /** 与归档文件名里的 sha1 前缀等长：用户在文件名里看到的那 12 位，就是这里显示的那 12 位。 */
    private const val SHA1_PREFIX_LENGTH = 12

    fun rows(
        entries: List<OriginEntry>,
        metas: Map<String, ModuleBackupMeta>,
        labels: BackupRowLabels,
    ): List<BackupRow> = entries.map { (origin, entry) ->
        if (entry.kind == BackupKind.BOOT) {
            bootRow(origin, entry, labels)
        } else {
            moduleRow(origin, entry, metas, labels)
        }
    }

    /**
     * boot 行的标题不能用 [BackupEntry.entryId]——那是一条 40 位 sha1。
     *
     * boot 的边车 meta 不是模块 meta（没有名称/版本），所以这里也不去查 [metas]：能讲的只有
     * "这是哪一张原厂镜像"，用标题 + sha1 前缀（与归档文件名里的前缀一致，用户能对上）。
     */
    private fun bootRow(origin: BackupOrigin, entry: BackupEntry, labels: BackupRowLabels) = BackupRow(
        id = rowId(origin, entry),
        title = "${labels.bootTitle} ${entry.entryId.take(SHA1_PREFIX_LENGTH)}",
        subtitle = humanSize(entry.sizeBytes),
        fileName = entry.fileName,
        origin = origin,
        originLabel = labels.originLabel(origin),
        kind = BackupKind.BOOT,
        createdAt = entry.createdAt,
        isRollback = RollbackPolicy.isRollback(entry),
    )

    private fun moduleRow(
        origin: BackupOrigin,
        entry: BackupEntry,
        metas: Map<String, ModuleBackupMeta>,
        labels: BackupRowLabels,
    ): BackupRow {
        val meta = entry.metaFileName?.let { metas[it] }
        return BackupRow(
            id = rowId(origin, entry),
            title = meta?.name?.takeIf { it.isNotBlank() } ?: entry.entryId,
            subtitle = listOfNotNull(
                meta?.versionName?.takeIf { it.isNotBlank() }?.let { "v$it" },
                humanSize(entry.sizeBytes),
                labels.disabled.takeIf { meta?.disabled == true },
            ).joinToString(SEPARATOR),
            fileName = entry.fileName,
            origin = origin,
            originLabel = labels.originLabel(origin),
            kind = BackupKind.MODULE,
            createdAt = entry.createdAt,
            isRollback = RollbackPolicy.isRollback(entry),
        )
    }

    /** 两侧可能各有一条同名归档，键里必须带来源，否则 LazyColumn 会因为重复键直接崩。 */
    private fun rowId(origin: BackupOrigin, entry: BackupEntry): String = "${origin.name}/${entry.fileName}"

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
