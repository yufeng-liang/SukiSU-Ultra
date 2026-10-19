package com.sukisu.ultra.ui.screen.settings.backup

import com.sukisu.ultra.data.backup.ArchiveNaming
import com.sukisu.ultra.data.backup.BackupEntry
import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin
import com.sukisu.ultra.data.backup.ModuleBackupMeta
import com.sukisu.ultra.data.backup.OriginEntry
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
)

/**
 * 列表里由调用方提供的那几段文案。
 *
 * [BackupListFormatter] 是纯 JVM 的（这样它能进单测），拿不到 `Context`；而这几段必须跟着语言走，
 * 所以从外面传进来，不在这里写死英文。
 */
data class BackupRowLabels(
    /**
     * 分区快照行的标题前缀。
     *
     * 标题本身由分区名（`boot_a` / `init_boot_a`）加这个前缀拼成——分区名就是那一行的身份，
     * 也是恢复时唯一要看的东西，不能藏进详情页。
     */
    val partitionTitle: String,
    /** 仓库源配置行的标题（这一行没有 meta，标题就是全部说明）。 */
    val repoConfigTitle: String,
    /** 模块处于禁用状态时缀在副标题里的词。 */
    val disabled: String,
    /** 分区快照是 gzip 压缩过的（从文件名后缀认出来，不需要读 meta）。 */
    val compressed: String,
    /** 分区快照没压缩。与 [compressed] 对称：只说"已压缩"的话，没标的那一行像是漏看了。 */
    val uncompressed: String,
    /** 来源标签：本机 / 云端。 */
    val originLabel: (BackupOrigin) -> String,
)

/**
 * 同一份内容本机与云端各有一条时只留一条，优先本机。
 *
 * 列表把两侧并成了一行，点进去就能看到两条同名的条目（"仓库源配置 · 云端"和"仓库源配置 · 本地"），
 * 而恢复是逐条做的：默认全选就会把同一个模块装两遍、把同一张镜像刷两遍，"已恢复 2 项"也让用户
 * 以为动了两个东西。两侧的归档名是一样的（同一次备份写出来的），所以按"类别 + 文件名"就能认出
 * 是同一份内容。
 *
 * 优先本机：离线、快，而且两边本来就是同一份内容（读的时候还会按索引里的 sha256 校验）。
 * 用户想指定云端那份时，把它单独勾上即可——只勾了一条就没有可去重的。
 */
internal fun oneCopyPerContent(rows: List<BackupRow>): List<BackupRow> {
    val seen = mutableSetOf<Pair<BackupKind, String>>()
    // 本机在前，于是"先到先留"留下的就是本机那份；同一侧内部的原有顺序不变。
    return rows.sortedBy { it.origin.ordinal }.filter { seen.add(it.kind to it.fileName) }
}

/**
 * 结果表按来源分好组，本机在前。
 *
 * 弹窗要先写一行来源表头（本地 / 云端）再列这一侧的几格，所以分组顺序必须定下来：
 * 集合和 map 都不保证顺序，靠 `groupBy` 的插入序等于靠输入顺序——输入顺序变了表就跟着翻。
 */
internal fun resultGroups(rows: List<BackupResultRow>): List<List<BackupResultRow>> =
    rows.groupBy { it.origin }.entries.sortedBy { it.key.ordinal }.map { it.value }

/** 「恢复勾选」按下之后要先问哪一句。 */
internal enum class RestoreConfirm {
    /** 不用问，直接恢复。 */
    NONE,

    /**
     * 勾了分区快照：恢复它就是往块设备写镜像，写错分区变砖、且没有撤销。
     *
     * 这一档比 [MANY] 优先，因为它的后果不是"备份可能旧了"而是"可能开不了机"。
     */
    PARTITION,

    /** 一次恢复好几条：备份可能比现在旧，而且恢复没有撤销。 */
    MANY,

    /** 单条仓库源也是整批替换：那条"只动一个模块"的豁免理由对它不成立。 */
    REPO_CONFIG,
}

/**
 * 这次「恢复勾选」该弹哪个确认框。
 *
 * 条数按**内容**数算，不按行数：本机与云端各一份的只恢复一次（见 [oneCopyPerContent]），
 * 拿行数去判会两头出错——标题说"恢复这 2 项"而实际只动一个，同时"两条同名、一份内容"的
 * 仓库源恢复会从确认框下面溜过去。
 *
 * 分区快照不看条数：勾上一个就该问，它和别的条混在一起时也一样。
 */
internal fun restoreConfirmFor(selection: List<BackupRow>): RestoreConfirm {
    val contents = oneCopyPerContent(selection)
    return when {
        selection.any { it.kind == BackupKind.PARTITION } -> RestoreConfirm.PARTITION
        contents.size > 1 -> RestoreConfirm.MANY
        contents.singleOrNull()?.kind == BackupKind.REPO_CONFIG -> RestoreConfirm.REPO_CONFIG
        else -> RestoreConfirm.NONE
    }
}

object BackupListFormatter {

    /**
     * 一条 message 里拼多段信息用的分隔符。
     *
     * 只有这一个定义：[mergeMessages] 按它分段去重，所以它同时是"拼接"与"去重"的契约，
     * 两边各写一份字面量的话，改动一边就会让"应用密码只提示一次"这类保证悄悄失效。
     */
    const val SEPARATOR = " · "

    fun rows(
        entries: List<OriginEntry>,
        metas: Map<String, ModuleBackupMeta>,
        labels: BackupRowLabels,
    ): List<BackupRow> = entries.map { (origin, entry) ->
        when (entry.kind) {
            BackupKind.PARTITION -> partitionRow(origin, entry, labels)
            BackupKind.REPO_CONFIG -> repoConfigRow(origin, entry, labels)
            else -> moduleRow(origin, entry, metas, labels)
        }
    }

    /**
     * 仓库源配置行：一份 JSON 里装着全部源，没有逐条的 meta 可讲。
     *
     * 标题用固定文案（labels.repoConfigTitle）而不是 entryId——那个 id 是内部主键
     * "repo-config"，对用户没有信息量；副标题只报大小。
     */
    private fun repoConfigRow(origin: BackupOrigin, entry: BackupEntry, labels: BackupRowLabels) = BackupRow(
        id = rowId(origin, entry),
        title = labels.repoConfigTitle,
        subtitle = humanSize(entry.sizeBytes),
        fileName = entry.fileName,
        origin = origin,
        originLabel = labels.originLabel(origin),
        kind = BackupKind.REPO_CONFIG,
        createdAt = entry.createdAt,
    )

    /**
     * 分区快照行。
     *
     * 标题是"前缀 + 分区名"：`boot_a` / `init_boot_a` 就是这一行的全部身份，恢复时也是按它
     * 决定刷哪儿。副标题给大小和是否压缩——用户要判断"这一份占了多少地方"。
     *
     * 这里刻意**不查** [metas]：那份 map 是模块 meta（名称/版本），分区快照的边车是另一套
     * 结构（见 [PartitionSnapshotMeta]）。压缩与否从文件名后缀就能看出来，不必读 meta。
     */
    private fun partitionRow(
        origin: BackupOrigin,
        entry: BackupEntry,
        labels: BackupRowLabels,
    ): BackupRow = BackupRow(
        id = rowId(origin, entry),
        title = "${labels.partitionTitle} ${entry.entryId}",
        subtitle = listOfNotNull(
            humanSize(entry.sizeBytes),
            if (ArchiveNaming.isCompressed(entry.fileName)) labels.compressed else labels.uncompressed,
        ).joinToString(SEPARATOR),
        fileName = entry.fileName,
        origin = origin,
        originLabel = labels.originLabel(origin),
        kind = BackupKind.PARTITION,
        createdAt = entry.createdAt,
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
