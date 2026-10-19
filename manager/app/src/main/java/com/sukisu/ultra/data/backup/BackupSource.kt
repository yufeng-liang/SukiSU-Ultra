package com.sukisu.ultra.data.backup

import java.io.InputStream

/** 导入外部归档时由源补出来的身份信息：分区快照需要它才知道该刷哪儿。 */
data class ImportMeta(val entryId: String, val metaJson: String)

/**
 * 一类可备份内容（模块 / 分区快照 / 仓库源配置）。
 *
 * 实现负责“产出归档”和“从归档恢复”，不关心归档存在哪里——存储由 [BackupStorage] 决定。
 */
interface BackupSource {
    val kind: BackupKind

    /**
     * 打包当前状态。
     *
     * [selected] 是"只要这些 entryId"；null = 源里有什么就打包什么。模块用它支持用户勾选，
     * 分区快照忽略它（一次就抓当前槽位的全部分区）。单个条目失败应跳过该条目、并记进
     * [ExportOutcome.failures]，而不是整体失败。
     *
     * [onPacking] 报打包到第几个条目了：这一段没有任何字节数可报（归档还在生成），而它往往是整个
     * 备份里最慢的一段，不报的话界面只能干等着（见 [PackingProgress]）。条目数一眼数得清的源
     * （分区快照、仓库源配置）也报一条，界面据此知道"打包已经开始了"。
     */
    suspend fun export(
        selected: Set<String>? = null,
        onPacking: (PackingProgress) -> Unit = {},
    ): Result<ExportOutcome>

    /** 从 [content] 恢复。[metaJson] 是归档的边车元数据，可能为 null（导入的第三方归档）。 */
    suspend fun restore(entry: BackupEntry, metaJson: String?, content: InputStream): Result<RestoreOutcome>

    /**
     * 从外部导入的归档需要什么才能被恢复。
     *
     * 默认什么都不需要（模块 zip 自带 module.prop，装上去即可）。分区快照例外：归档里只
     * 记了"刷回哪个分区"这一件事，而它必须由源在导入时补进边车 meta——没有 meta 的归档在
     * 恢复时只能被拒绝（[BackupReason.PartitionNoSidecarMeta]）。
     *
     * [fileName] 是归档的文件名，导入时分区名就从这里认（`partition_init_boot_a_...img.gz`）。
     */
    suspend fun metaForImport(
        fileName: String,
        open: () -> InputStream,
        size: Long,
    ): ImportMeta? = null
}
