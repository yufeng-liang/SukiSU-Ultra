package com.sukisu.ultra.data.backup

import java.io.InputStream

data class BackupEntry(
    val kind: BackupKind,
    val entryId: String,
    val fileName: String,
    val metaFileName: String?,
    val sizeBytes: Long,
    val sha256: String,
    val createdAt: String,
)

data class ModuleBackupMeta(
    val entryId: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val author: String,
    val metamodule: Boolean,
    val disabled: Boolean,
    val sourceDevice: String,
    val createdAt: String,
)

/**
 * 分区快照的边车元数据。
 *
 * [partition] 是恢复时的唯一依据（含槽位后缀，如 `init_boot_a`），所以它必须在 meta 里，
 * 不能只靠文件名推断——文件名给人看，meta 给程序用。
 *
 * [partitionSizeBytes] 是**拍快照那一刻**分区的大小。恢复前拿它和**当前**分区大小比对
 * （`PartitionInfo.sizeBytes`，来自 `blockdev --getsize64`），两者不等就拒绝：分区表变了
 * （换过系统、改过 super 布局）说明这份快照来自另一台设备或另一个系统版本，写进去就是灾难。
 * 解压后的长度另外还要与当前分区大小相等，那是最后一道闸门。
 */
data class PartitionSnapshotMeta(
    val entryId: String,
    val partition: String,
    val sourceDevice: String,
    val partitionSizeBytes: Long,
    val compressed: Boolean,
    val createdAt: String,
    /**
     * 拍快照那一刻的 `uname -r`；旧快照和导入的归档没有这个字段，为 null。
     *
     * 分区大小一致拦不住「系统升级过」：OTA 原位更新 boot/init_boot 后分区长度不变，
     * 大小两道闸全过，刷回去的却是旧内核。恢复确认前拿它和当前的 `uname -r` 比对
     * （[KernelKmi]），KMI 变了就让界面把两版本号摆出来让用户自己决定——数据层不硬拒，
     * 同 KMI 的月度 OTA 之后刷旧快照是正当操作。
     */
    val kernelRelease: String? = null,
)

/** [openContent] 每次调用都要返回一个新的流；调用方负责关闭。 */
data class BackupArtifact(
    val kind: BackupKind,
    val entryId: String,
    val fileName: String,
    val metaFileName: String?,
    val sizeBytes: Long,
    val sha256: String,
    val metaJson: String?,
    val openContent: () -> InputStream,
    /**
     * 所有后端都写完之后由引擎调用一次，用来删掉打包时落下的临时文件。
     * 归档要在所有后端写完之前一直可用，所以清理不能放在产出它的地方。
     */
    val cleanup: (() -> Unit)? = null,
)

/**
 * 一次导出的结果。
 *
 * 单条失败不再被 `getOrNull()` 吞掉：只带 artifacts 回去的话，一个模块没打包成功
 * 只会让结果少一项，UI 显示"written N"而用户不知道有模块根本没备份上。
 */
data class ExportOutcome(
    val artifacts: List<BackupArtifact> = emptyList(),
    val failures: List<BackupFailure> = emptyList(),
)

/** 归档存放的位置：本机或云端。 */
enum class BackupOrigin { LOCAL, CLOUD }

/**
 * 一条失败记录。
 *
 * [reason] 是结构化原因，不是已经拼好的字符串：同一个失败要在列表摘要、恢复提示、
 * 系统通知里出现，各自需要的详细程度和语言都不同，翻译归 UI 层。
 */
data class BackupFailure(
    val path: String,
    val storage: String,
    val reason: BackupReason,
) {
    /** 服务端拒绝凭据。UI 靠它把"请用应用密码"的提示送到用户面前。 */
    val authFailed: Boolean get() = reason is BackupReason.CloudCredentialsRejected
}

/**
 * 某一个后端这一次写了什么。
 *
 * 一次备份把同一批内容**打包一次**、写到所有勾选的来源，所以整体计数（写了几项）在两侧都成功时
 * 会翻倍。要按来源报数（结果表一行一个"来源 × 内容"、自动备份要分"本地成了云端没成"）就必须
 * 留一份按后端的明细。
 */
data class StorageOutcome(
    val storage: String,
    val written: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val failures: List<BackupFailure> = emptyList(),
)

data class BackupRunResult(
    val kind: BackupKind,
    val written: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val failures: List<BackupFailure> = emptyList(),
    /**
     * 每个后端各自的那一份结果，顺序与写入顺序一致。
     *
     * 空表示"这次没有可写的后端"（比如云端没配好），此时 [written] / [skipped] 就是全部。
     */
    val storages: List<StorageOutcome> = emptyList(),
) {
    val isSuccess: Boolean get() = failures.isEmpty()
    val isPartial: Boolean get() = failures.isNotEmpty() && written.isNotEmpty()
    val isFailure: Boolean get() = failures.isNotEmpty() && written.isEmpty()

    /**
     * 只看某一个后端的那一份结果。
     *
     * 源侧/引擎侧的失败不属于任何一个后端，却会让每一次写入都不完整，所以它们出现在**每一份**
     * 里：只报本地那一行、让云端那一行看着没事，就把"这个模块根本没打出来"藏起来了。
     *
     * 这个后端不在 [storages] 里（没被写、或这次压根没有后端）时原样返回：那种结果里的计数本来
     * 就是全部，切片无从谈起。
     */
    fun forStorage(storageId: String): BackupRunResult {
        val own = storages.firstOrNull { it.storage == storageId } ?: return this
        val shared = failures.filter { failure -> storages.none { it.storage == failure.storage } }
        return copy(
            written = own.written,
            skipped = own.skipped,
            failures = own.failures + shared,
            // 切完之后再切没有意义，清掉免得调用方拿着半份结果又切一次。
            storages = emptyList(),
        )
    }
}

/** [reason] 只在失败时有值：成功时没什么可说的，恢复到了哪一项由调用方自己知道。 */
data class RestoreOutcome(val success: Boolean, val reason: BackupReason? = null)
