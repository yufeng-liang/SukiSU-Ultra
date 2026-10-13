package com.sukisu.ultra.ui.util

import android.content.Context
import androidx.annotation.StringRes
import com.sukisu.ultra.R
import com.sukisu.ultra.data.backup.AutoBackupOutcome
import com.sukisu.ultra.data.backup.AutoBackupRecord
import com.sukisu.ultra.data.backup.BackupFailure
import com.sukisu.ultra.data.backup.BackupKind
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
        is BackupReason.ModuleIdUnusable ->
            ReasonText(R.string.backup_reason_module_id_unusable, listOf(Redaction.redactMessage(reason.entryId)))
        is BackupReason.RepoConfigInvalid -> ReasonText(R.string.backup_reason_repo_config_invalid)
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
        is BackupReason.ImportTooLarge -> ReasonText(
            R.string.backup_reason_import_too_large,
            listOf(reason.path, reason.limitBytes / (1024L * 1024L)),
        )
        is BackupReason.ImportIndexWriteFailed -> ReasonText(R.string.backup_reason_import_index_write_failed)
        BackupReason.FileNameUnresolved -> ReasonText(R.string.backup_reason_file_name)
        is BackupReason.DuplicateContent -> ReasonText(R.string.backup_reason_duplicate_content, listOf(reason.existing))
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
        // 解析器抛的具体原因（不认识的 schema/version、缺哪个字段），正文只有一句
        // "读不出来或格式不对"，细节走这里。
        is BackupReason.RepoConfigInvalid -> reason.external
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
     * 结果表里"这一格"写什么。
     *
     * 一格只讲这一侧这一类内容的结果：写了几个、跳过几个、有没有可备的。失败原因不进格子——
     * 那是整行宽度的东西（HTTP 状态码、文件路径），挤进表格右列会把列宽撑歪，而一次失败往往
     * 每个文件一句；它们排在表下面（见 [allFailureLines]）。
     *
     * "全跳过"和"写了 0 项"必须分开说：前者是"内容跟已有的备份一样，这次没白按、也没费流量"，
     * 后者在用户眼里就是失败。这两句话以前挤在同一行里（"已写入 0 项 · 跳过 3 项"），
     * 表格把每格缩短之后反而说得更清楚。
     *
     * 拆成纯函数是为了能进 JVM 单测：`Context.getString` 在单测里跑不了，而这里会出错的正是
     * "哪种组合该出哪一句"。
     */
    fun outcomeCell(kind: BackupKind, result: BackupRunResult): ReasonText = when {
        // 一项没写、没跳过、也没失败：源里根本没有可备的东西（boot 就是这种情况：ksud 只在
        // 打补丁时留下原厂镜像）。
        result.written.isEmpty() && result.skipped.isEmpty() && result.failures.isEmpty() ->
            ReasonText(if (kind == BackupKind.BOOT) R.string.backup_nothing_boot else R.string.backup_nothing_module)
        // 写了也跳了：两句都说，否则"已备份 8 项"会让人以为另外 2 项丢了。
        result.written.isNotEmpty() && result.skipped.isNotEmpty() ->
            ReasonText(R.string.backup_result_partial, listOf(result.written.size, result.skipped.size))
        result.written.isNotEmpty() -> ReasonText(R.string.backup_summary_written, listOf(result.written.size))
        result.skipped.isNotEmpty() -> ReasonText(R.string.backup_result_unchanged, listOf(result.skipped.size))
        // 只剩失败：格子写不下原因，写一句"失败"，原因在表下面。
        else -> ReasonText(R.string.backup_result_failed)
    }

    /** 结果里有没有"已是最新"那一格。有才需要在表下面解释这个词，没有就是多余的说明。 */
    fun anyUnchanged(results: List<BackupRunResult>): Boolean =
        results.any { it.written.isEmpty() && it.skipped.isNotEmpty() }

    /**
     * 表下面的失败清单：整行宽度，跨目标去重。
     *
     * 两侧同时失败（凭据被拒就是这种）时两行文字一模一样，只说一遍。
     */
    fun allFailureLines(context: Context, results: List<BackupRunResult>): List<String> =
        results.flatMap { failureLines(it) }.map { failure(context, it) }.distinct()

    /**
     * 一次目标里要列出的失败。
     *
     * 同一个位置、同一个文件、同一个原因只留一条：凭据被拒时每一个文件都会给出同一句话
     * （原因里没有文件名），重复 11 遍就把结果框变成一堵墙。**不同文件**的失败不能并掉——
     * 那是"这两个文件没传上去"，用户得知道是哪两个。
     *
     * 拆成纯函数是为了能断言这条规矩：`Context.getString` 在单测里跑不了。
     */
    fun failureLines(result: BackupRunResult): List<BackupFailure> =
        result.failures.distinctBy { failure -> Triple(failure.storage, failure.path, failure.reason) }

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
                val when_ = formatWhen(context, record.atEpochMs)
                val result = when (record.outcome) {
                    // 写入 0 项、跳过若干项 = 内容全没变：说"成功 · 写入 0 项"看着像没干活。
                    AutoBackupOutcome.OK -> if (record.writtenCount == 0 && record.skippedCount > 0) {
                        context.getString(R.string.backup_auto_result_unchanged, record.skippedCount)
                    } else {
                        context.getString(R.string.backup_auto_result_ok, record.writtenCount)
                    }
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

    /**
     * 「上次自动备份」的时间说法：今天/昨天带钟点，再往前数天、数周，一个月开外才回到日期。
     *
     * 只给钟点（21:12）的话，读的人得自己回忆那是今天还是上个月——自动备份是后台自己跑的，
     * 恰恰是用户最记不住"哪一天"的那类事。但也不能一路"35 天前"数下去：越远越没人按天算，
     * 到那时写日期反而更好认。
     */
    private fun formatWhen(context: Context, epochMs: Long): String {
        val days = daysAgo(epochMs)
        return when {
            // 未来时间（设备时钟被改过）也算"今天"，不写"−1 天前"这种话。
            days <= 0L -> context.getString(R.string.backup_auto_time_today, formatTime(context, epochMs))
            days == 1L -> context.getString(R.string.backup_auto_time_yesterday, formatTime(context, epochMs))
            days < 7L -> context.getString(R.string.backup_auto_time_days, days)
            days < 14L -> context.getString(R.string.backup_auto_time_last_week)
            days < 28L -> context.getString(R.string.backup_auto_time_weeks, days / 7L)
            else -> android.text.format.DateFormat.getMediumDateFormat(context).format(java.util.Date(epochMs))
        }
    }

    /** 两个时刻差几个"自然日"。按日历天算，不按 24 小时算：23:59 到次日 00:01 该是"昨天"。 */
    private fun daysAgo(epochMs: Long, nowMs: Long = System.currentTimeMillis()): Long {
        val zone = java.time.ZoneId.systemDefault()
        val then = java.time.Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return java.time.temporal.ChronoUnit.DAYS.between(then, today)
    }

    private fun shortHash(value: String): String =
        if (value.length <= HASH_PREFIX) value else value.take(HASH_PREFIX) + "…"
}
