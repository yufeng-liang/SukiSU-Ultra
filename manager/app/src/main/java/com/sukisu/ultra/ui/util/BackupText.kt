package com.sukisu.ultra.ui.util

import android.content.Context
import androidx.annotation.StringRes
import com.sukisu.ultra.R
import com.sukisu.ultra.data.backup.AutoBackupOutcome
import com.sukisu.ultra.data.backup.AutoBackupRecord
import com.sukisu.ultra.data.backup.BackupFailure
import com.sukisu.ultra.data.backup.BackupReason
import com.sukisu.ultra.data.backup.BackupRunResult
import com.sukisu.ultra.data.backup.HttpOperation
import com.sukisu.ultra.data.backup.LocalBackupStorage
import com.sukisu.ultra.data.backup.Redaction
import com.sukisu.ultra.data.backup.WebDavBackupStorage
import com.sukisu.ultra.ui.screen.settings.backup.BackupListFormatter

/**
 * 一条原因对应的文案：资源 id + 参数。
 *
 * 拆成"资源 id + 参数"而不是直接返回字符串，是为了让**映射本身**能在纯 JVM 单测里断言
 * （`Context.getString` 在单测里跑不了），而渲染只是一句 `getString`。漏掉一个
 * [BackupReason] 分支会编译不过，所以覆盖性由编译器保证，测试只需要盯住参数顺序。
 */
data class ReasonText(@StringRes val resId: Int, val args: List<Any> = emptyList())

/**
 * 备份相关文案的唯一出口。
 *
 * 数据层给的是结构化事实（[BackupReason]），"人话 + 一句下一步 + 括号里的技术细节"
 * 这一层负责。三条纪律：
 * 1. 用户看到的每一句都不含内部术语——没有 `PUT`/`MKCOL`/`index-read`，没有 `written 0`。
 * 2. 技术细节不丢，但压到括号里：HTTP 状态码、截断的 sha1/sha256、文件名。
 * 3. 第三方原文（ksud 输出）只加一句本地化前缀，绝不改写它。
 */
object BackupText {

    /** 截断哈希：完整 64 位十六进制在 snackbar 里没人读得下去，前 12 位足够对得上。 */
    private const val HASH_PREFIX = 12

    fun reasonText(reason: BackupReason): ReasonText = when (reason) {
        BackupReason.CloudNotConfigured -> ReasonText(R.string.backup_reason_cloud_not_configured)
        is BackupReason.CloudCredentialsRejected -> ReasonText(R.string.backup_reason_cloud_credentials, listOf(reason.code))
        is BackupReason.HttpFailed -> when (reason.operation) {
            HttpOperation.CREATE_DIRECTORY -> ReasonText(R.string.backup_reason_http_mkcol, listOf(reason.path, reason.code))
            HttpOperation.UPLOAD -> ReasonText(R.string.backup_reason_http_upload, listOf(reason.path, reason.code))
            HttpOperation.DOWNLOAD -> ReasonText(R.string.backup_reason_http_download, listOf(reason.path, reason.code))
            HttpOperation.DELETE -> ReasonText(R.string.backup_reason_http_delete, listOf(reason.path, reason.code))
        }
        is BackupReason.IndexUnreadable -> ReasonText(R.string.backup_reason_index_unreadable)
        is BackupReason.ReadFailed -> ReasonText(R.string.backup_reason_read_failed, listOf(reason.path))
        is BackupReason.WriteFailed -> ReasonText(R.string.backup_reason_write_failed, listOf(reason.path))
        is BackupReason.DeleteFailed -> ReasonText(R.string.backup_reason_delete_failed, listOf(reason.path))
        is BackupReason.DirectoryNotWritable -> ReasonText(R.string.backup_reason_dir_not_writable, listOf(reason.path))
        is BackupReason.Corrupted -> ReasonText(
            R.string.backup_reason_corrupted,
            listOf(reason.path, shortHash(reason.expected), shortHash(reason.actual)),
        )
        is BackupReason.ArchiveFailed -> ReasonText(R.string.backup_reason_archive_failed, listOf(reason.entryId))
        is BackupReason.ModuleInstallFailed -> ReasonText(R.string.backup_reason_module_install_failed, listOf(reason.entryId))
        is BackupReason.ModuleDisableFailed -> ReasonText(R.string.backup_reason_module_disable_failed, listOf(reason.entryId))
        is BackupReason.NoSource -> ReasonText(R.string.backup_reason_no_source)
        BackupReason.BootNoSidecarMeta -> ReasonText(R.string.backup_reason_boot_no_meta)
        is BackupReason.BootForeignStockImage -> ReasonText(
            R.string.backup_reason_boot_foreign,
            listOf(shortHash(reason.recorded), shortHash(reason.expected)),
        )
        BackupReason.BootNoChecksum -> ReasonText(R.string.backup_reason_boot_no_checksum)
        is BackupReason.BootStockImageMissing -> ReasonText(R.string.backup_reason_boot_missing, listOf(shortHash(reason.sha1)))
        is BackupReason.BootIdentityMismatch -> ReasonText(R.string.backup_reason_boot_identity, listOf(shortHash(reason.sha1)))
        is BackupReason.BootFlashFailed -> ReasonText(R.string.backup_reason_boot_flash_failed)
        BackupReason.FileUnreadable -> ReasonText(R.string.backup_reason_file_unreadable)
        BackupReason.FileNameUnresolved -> ReasonText(R.string.backup_reason_file_name)
        is BackupReason.External -> ReasonText(R.string.backup_reason_external, listOf(Redaction.redactMessage(reason.text)))
    }

    /** 原因自带的第三方细节（ksud 输出、IO 异常原文）。没有就返回 null，别渲染空括号。 */
    fun detailOf(reason: BackupReason): String? = when (reason) {
        is BackupReason.IndexUnreadable -> reason.external
        is BackupReason.ReadFailed -> reason.external
        is BackupReason.WriteFailed -> reason.external
        is BackupReason.DeleteFailed -> reason.external
        is BackupReason.DirectoryNotWritable -> reason.external
        is BackupReason.ArchiveFailed -> reason.external
        is BackupReason.ModuleInstallFailed -> reason.external
        is BackupReason.ModuleDisableFailed -> reason.external
        is BackupReason.BootFlashFailed -> reason.external
        else -> null
    }?.takeIf { it.isNotBlank() }?.let { Redaction.redactMessage(it) }

    /** 后端标签。源侧/引擎侧的失败没有"位置"可讲，返回 null 让调用方不加前缀。 */
    @StringRes
    fun storageLabelRes(storage: String): Int? = when (storage) {
        LocalBackupStorage.ID -> R.string.backup_storage_local
        WebDavBackupStorage.ID -> R.string.backup_storage_cloud
        else -> null
    }

    fun render(context: Context, text: ReasonText): String =
        context.getString(text.resId, *text.args.toTypedArray())

    fun reason(context: Context, reason: BackupReason): String {
        val base = render(context, reasonText(reason))
        val detail = detailOf(reason) ?: return base
        return context.getString(R.string.backup_reason_with_detail, base, detail)
    }

    /** 一条失败：`位置 路径：原因`。路径为空（整体性失败）时不加前缀。 */
    fun failure(context: Context, failure: BackupFailure): String {
        val body = reason(context, failure.reason)
        if (failure.path.isBlank()) return body
        val location = storageLabelRes(failure.storage)?.let(context::getString)
        val prefix = if (location == null) failure.path else "$location ${failure.path}"
        return context.getString(R.string.backup_failure_line, prefix, body)
    }

    /**
     * 一次备份的摘要。
     *
     * 失败行按文本去重：凭据被拒时每一个文件都会给出同一句话，重复五遍会把 snackbar
     * 变成一堵墙，而用户需要的信息只有一句。具体哪几个文件失败了在备份列表里能看到。
     *
     * [location] 是这次写进去的位置（短形式）。只在**真的写进去了**的时候才说：整次失败时
     * 一句"存到 X"会让人以为文件在那儿，而那里什么都没有。
     *
     * [compact] 用于"一行一个目标"的场合（位置/内容勾了多个）。snackbar 只给两行，一行长了
     * 就把后面那行连同失败原因整段截掉，所以这里省掉"跳过 0 项"——跳过为 0 是正常情况，
     * 写出来只占宽度。非 0 时照旧显示，那才是用户要知道的。
     */
    fun summary(context: Context, result: BackupRunResult, location: String? = null, compact: Boolean = false): String =
        summaryParts(result, location, compact).joinToString(BackupListFormatter.SEPARATOR) { part ->
            render(context, part)
        }

    /**
     * [summary] 的纯形式：资源 id + 参数，取字符串交给 [render]。
     *
     * 拆出来是为了能进 JVM 单测——`Context.getString` 在单测里跑不了，而这里会出错的正是
     * "什么时候该出现哪一段"（跳过为 0 要不要写、没写进去要不要报位置）。
     */
    fun summaryParts(result: BackupRunResult, location: String? = null, compact: Boolean = false): List<ReasonText> {
        val parts = mutableListOf(ReasonText(R.string.backup_summary_written, listOf(result.written.size)))
        if (result.skipped.isNotEmpty() || !compact) {
            parts += ReasonText(R.string.backup_summary_skipped, listOf(result.skipped.size))
        }
        if (location != null && result.written.isNotEmpty()) {
            parts += ReasonText(R.string.backup_saved_to, listOf(location))
        }
        return parts
    }

    /** 恢复失败的提示。 */
    fun restoreFailure(context: Context, reason: BackupReason?): String =
        reason?.let { this.reason(context, it) } ?: context.getString(R.string.backup_restore_failed)

    /**
     * 「上次自动备份」那一行。
     *
     * 成功也要显示：用户分不清"成功"和"这个功能根本没跑"。开关关掉后保留历史，只缀一句
     * 「已关闭」——历史是事实，不该被一个开关抹掉。
     */
    fun autoBackupLine(context: Context, record: AutoBackupRecord?, enabled: Boolean): String {
        val body = when {
            record == null -> context.getString(R.string.backup_auto_never)
            else -> {
                val when_ = formatTime(context, record.atEpochMs)
                val result = when (record.outcome) {
                    AutoBackupOutcome.OK -> context.getString(R.string.backup_auto_result_ok, record.writtenCount)
                    AutoBackupOutcome.PARTIAL -> context.getString(R.string.backup_auto_result_partial)
                    AutoBackupOutcome.FAILED -> context.getString(R.string.backup_auto_result_failed)
                }
                val reasons = record.failures.map { failure(context, it) }.distinct()
                (listOf(when_, result) + reasons).joinToString(BackupListFormatter.SEPARATOR)
            }
        }
        val line = context.getString(R.string.backup_auto_last, body)
        return if (enabled) line else "$line${BackupListFormatter.SEPARATOR}${context.getString(R.string.backup_auto_off)}"
    }

    /** 通知正文：本地成了就明说本地成了，别让用户以为什么都没有。 */
    fun autoBackupNotificationText(context: Context, record: AutoBackupRecord): String {
        val reasons = record.failures.map { failure(context, it) }.distinct().joinToString(BackupListFormatter.SEPARATOR)
        return when (record.outcome) {
            AutoBackupOutcome.PARTIAL -> context.getString(R.string.backup_auto_notify_partial, reasons)
            else -> reasons.ifBlank { context.getString(R.string.backup_auto_result_failed) }
        }
    }

    private fun formatTime(context: Context, epochMs: Long): String =
        android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(epochMs))

    private fun shortHash(value: String): String =
        if (value.length <= HASH_PREFIX) value else value.take(HASH_PREFIX) + "…"
}
