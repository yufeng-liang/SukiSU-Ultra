package com.sukisu.ultra.ui.screen.settings.backup

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
    /** boot 行的标题前缀：那一行的 id 是一串 sha1，光看它没人知道这是原厂镜像。 */
    val bootTitle: String,
    /** 仓库源配置行的标题（这一行没有 meta，标题就是全部说明）。 */
    val repoConfigTitle: String,
    /** 模块处于禁用状态时缀在副标题里的词。 */
    val disabled: String,
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

    /** 勾了原厂镜像：恢复它等于把设备带回未 root 状态，不可逆。 */
    BOOT,

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
 * 原厂镜像不看条数：勾上一个就该问，它和别的条混在一起时也一样。
 */
internal fun restoreConfirmFor(selection: List<BackupRow>): RestoreConfirm {
    val contents = oneCopyPerContent(selection)
    return when {
        selection.any { it.kind == BackupKind.BOOT } -> RestoreConfirm.BOOT
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

    /** 与归档文件名里的 sha1 前缀等长：用户在文件名里看到的那 12 位，就是这里显示的那 12 位。 */
    private const val SHA1_PREFIX_LENGTH = 12

    fun rows(
        entries: List<OriginEntry>,
        metas: Map<String, ModuleBackupMeta>,
        labels: BackupRowLabels,
    ): List<BackupRow> = entries.map { (origin, entry) ->
        when (entry.kind) {
            BackupKind.BOOT -> bootRow(origin, entry, labels)
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
