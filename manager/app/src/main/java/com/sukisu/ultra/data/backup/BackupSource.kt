package com.sukisu.ultra.data.backup

import java.io.InputStream

/** 导入外部归档时由源补出来的身份信息：boot 需要它才能被 ksud 认出来。 */
data class ImportMeta(val entryId: String, val metaJson: String)

/**
 * 一类可备份内容（模块 / boot）。
 *
 * 实现负责“产出归档”和“从归档恢复”，不关心归档存在哪里——存储由 [BackupStorage] 决定。
 */
interface BackupSource {
    val kind: BackupKind

    /**
     * 打包当前状态。
     *
     * [selected] 是"只要这些 entryId"；null = 源里有什么就打包什么。模块用它支持用户勾选，
     * boot 忽略它（那一份镜像本来就只有一张）。单个条目失败应跳过该条目、并记进
     * [ExportOutcome.failures]，而不是整体失败。
     */
    suspend fun export(selected: Set<String>? = null): Result<ExportOutcome>

    /** 从 [content] 恢复。[metaJson] 是归档的边车元数据，可能为 null（导入的第三方归档）。 */
    suspend fun restore(entry: BackupEntry, metaJson: String?, content: InputStream): Result<RestoreOutcome>

    /**
     * 从外部导入的归档需要什么才能被恢复。
     *
     * 默认什么都不需要（模块 zip 自带 module.prop，装上去即可）。boot 例外：
     * ksud 只认 `/data/adb/ksu/ksu_backup_<原厂镜像 sha1>`，而 sha1 只存在于内容里，
     * 所以必须读一遍内容把身份算出来，否则导入的 boot 归档永远恢复不了。
     */
    suspend fun metaForImport(open: () -> InputStream, size: Long): ImportMeta? = null
}
