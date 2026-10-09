package com.sukisu.ultra.data.backup

import org.json.JSONArray
import org.json.JSONObject

/** 一次自动备份的整体结论。 */
enum class AutoBackupOutcome { OK, PARTIAL, FAILED }

/**
 * 上一次自动备份的结果。
 *
 * 自动备份跑在脱离页面的协程里，跑完时用户早就离开了刷入页；这份记录是唯一能告诉他
 * "那次到底备上了没有"的东西，所以必须落盘——进程被杀、隔天再打开 app 都还要在。
 *
 * [writtenCount] 是本地 + 云端写入的总数；[failures] 带着各自的 storage，UI 靠它区分
 * "本地失败"和"云端失败"（见 [AutoBackupOutcome.PARTIAL]）。
 *
 * [skippedCount] 是"内容没变、没再存一份"的条目数。装的是同一个版本的模块时它会等于模块总数，
 * 而 [writtenCount] 是 0——只报"写入 0 项"看着像什么都没干，得说清是"没有新东西可存"。
 */
data class AutoBackupRecord(
    val atEpochMs: Long,
    val outcome: AutoBackupOutcome,
    val writtenCount: Int,
    val failures: List<BackupFailure>,
    /** 老记录里没有这一项，解析时缺省 0。 */
    val skippedCount: Int = 0,
)

/**
 * 记录的 JSON 编解码。
 *
 * [BackupReason] 是密封接口，不能直接序列化，所以带一个 `type` 判别字段手写编解码。
 * 解不出来时退化成 [BackupReason.External] 而不是抛异常：一条读不出来的历史记录
 * 不该让整个备份页崩掉。
 */
object AutoBackupRecordJson {

    private const val SCHEMA = "sukisu.backup.auto"

    fun render(record: AutoBackupRecord): String = JSONObject().apply {
        put("schema", SCHEMA)
        put("version", ArchiveNaming.MANIFEST_VERSION)
        put("atEpochMs", record.atEpochMs)
        put("outcome", record.outcome.name)
        put("writtenCount", record.writtenCount)
        put("skippedCount", record.skippedCount)
        put(
            "failures",
            JSONArray().apply {
                record.failures.forEach { failure ->
                    put(
                        JSONObject().apply {
                            put("path", failure.path)
                            put("storage", failure.storage)
                            put("reason", encodeReason(failure.reason))
                        }
                    )
                }
            },
        )
    }.toString()

    fun parse(json: String?): AutoBackupRecord? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(json)
            if (root.optString("schema") != SCHEMA) return null
            val outcome = AutoBackupOutcome.entries.firstOrNull { it.name == root.optString("outcome") }
                ?: return null
            val array = root.optJSONArray("failures")
            val failures = (0 until (array?.length() ?: 0)).mapNotNull { index ->
                val item = array?.optJSONObject(index) ?: return@mapNotNull null
                BackupFailure(
                    path = item.optString("path"),
                    storage = item.optString("storage"),
                    reason = decodeReason(item.optJSONObject("reason") ?: JSONObject()),
                )
            }
            AutoBackupRecord(
                atEpochMs = root.optLong("atEpochMs"),
                outcome = outcome,
                writtenCount = root.optInt("writtenCount"),
                skippedCount = root.optInt("skippedCount"),
                failures = failures,
            )
        }.getOrNull()
    }

    private fun encodeReason(reason: BackupReason): JSONObject = JSONObject().apply {
        when (reason) {
            BackupReason.CloudNotConfigured -> put("type", "cloud_not_configured")
            is BackupReason.CloudCredentialsRejected -> put("type", "cloud_credentials_rejected").put("code", reason.code)
            is BackupReason.HttpFailed -> put("type", "http_failed")
                .put("operation", reason.operation.name).put("path", reason.path).put("code", reason.code)
            is BackupReason.IndexUnreadable -> put("type", "index_unreadable").putExternal(reason.external)
            is BackupReason.ReadFailed -> put("type", "read_failed").put("path", reason.path).putExternal(reason.external)
            is BackupReason.WriteFailed -> put("type", "write_failed").put("path", reason.path).putExternal(reason.external)
            is BackupReason.DeleteFailed -> put("type", "delete_failed").put("path", reason.path).putExternal(reason.external)
            is BackupReason.DirectoryNotWritable -> put("type", "directory_not_writable")
                .put("path", reason.path).putExternal(reason.external)
            is BackupReason.Corrupted -> put("type", "corrupted")
                .put("path", reason.path).put("expected", reason.expected).put("actual", reason.actual)
            is BackupReason.ArchiveFailed -> put("type", "archive_failed")
                .put("entryId", reason.entryId).putExternal(reason.external)
            is BackupReason.ModuleInstallFailed -> put("type", "module_install_failed")
                .put("entryId", reason.entryId).putExternal(reason.external)
            is BackupReason.ModuleDisableFailed -> put("type", "module_disable_failed")
                .put("entryId", reason.entryId).putExternal(reason.external)
            is BackupReason.NoSource -> put("type", "no_source").put("kind", reason.kind.wireName)
            BackupReason.BootNoSidecarMeta -> put("type", "boot_no_sidecar_meta")
            is BackupReason.BootForeignStockImage -> put("type", "boot_foreign_stock_image")
                .put("recorded", reason.recorded).put("expected", reason.expected)
            BackupReason.BootNoChecksum -> put("type", "boot_no_checksum")
            is BackupReason.BootStockImageMissing -> put("type", "boot_stock_image_missing").put("sha1", reason.sha1)
            is BackupReason.BootIdentityMismatch -> put("type", "boot_identity_mismatch").put("sha1", reason.sha1)
            is BackupReason.BootFlashFailed -> put("type", "boot_flash_failed").putExternal(reason.external)
            BackupReason.FileUnreadable -> put("type", "file_unreadable")
            BackupReason.FileNameUnresolved -> put("type", "file_name_unresolved")
            is BackupReason.DuplicateContent -> put("type", "duplicate_content").put("existing", reason.existing)
            is BackupReason.External -> put("type", "external").put("text", reason.text)
        }
    }

    private fun decodeReason(json: JSONObject): BackupReason {
        val type = json.optString("type")
        return when (type) {
            "cloud_not_configured" -> BackupReason.CloudNotConfigured
            "cloud_credentials_rejected" -> BackupReason.CloudCredentialsRejected(json.optInt("code"))
            "http_failed" -> BackupReason.HttpFailed(
                operation = HttpOperation.entries.firstOrNull { it.name == json.optString("operation") }
                    ?: HttpOperation.UPLOAD,
                path = json.optString("path"),
                code = json.optInt("code"),
            )
            "index_unreadable" -> BackupReason.IndexUnreadable(json.externalOrNull())
            "read_failed" -> BackupReason.ReadFailed(json.optString("path"), json.externalOrNull())
            "write_failed" -> BackupReason.WriteFailed(json.optString("path"), json.externalOrNull())
            "delete_failed" -> BackupReason.DeleteFailed(json.optString("path"), json.externalOrNull())
            "directory_not_writable" -> BackupReason.DirectoryNotWritable(json.optString("path"), json.externalOrNull())
            "corrupted" -> BackupReason.Corrupted(
                path = json.optString("path"),
                expected = json.optString("expected"),
                actual = json.optString("actual"),
            )
            "archive_failed" -> BackupReason.ArchiveFailed(json.optString("entryId"), json.externalOrNull())
            "module_install_failed" -> BackupReason.ModuleInstallFailed(json.optString("entryId"), json.externalOrNull())
            "module_disable_failed" -> BackupReason.ModuleDisableFailed(json.optString("entryId"), json.externalOrNull())
            "no_source" -> BackupReason.NoSource(
                BackupKind.entries.firstOrNull { it.wireName == json.optString("kind") } ?: BackupKind.MODULE,
            )
            "boot_no_sidecar_meta" -> BackupReason.BootNoSidecarMeta
            "boot_foreign_stock_image" -> BackupReason.BootForeignStockImage(
                recorded = json.optString("recorded"),
                expected = json.optString("expected"),
            )
            "boot_no_checksum" -> BackupReason.BootNoChecksum
            "boot_stock_image_missing" -> BackupReason.BootStockImageMissing(json.optString("sha1"))
            "boot_identity_mismatch" -> BackupReason.BootIdentityMismatch(json.optString("sha1"))
            "boot_flash_failed" -> BackupReason.BootFlashFailed(json.externalOrNull())
            "file_unreadable" -> BackupReason.FileUnreadable
            "file_name_unresolved" -> BackupReason.FileNameUnresolved
            "duplicate_content" -> BackupReason.DuplicateContent(json.optString("existing"))
            "external" -> BackupReason.External(json.optString("text"))
            // 认不出来的类型（更旧的记录、或将来加了新原因又回退版本）：退化成原文，
            // 至少让用户看到"有这么一条"，而不是让整页解析失败。
            else -> BackupReason.External(type)
        }
    }

    private fun JSONObject.putExternal(value: String?) {
        put("external", value ?: JSONObject.NULL)
    }

    private fun JSONObject.externalOrNull(): String? =
        optString("external").takeIf { it.isNotBlank() && it != "null" }
}
