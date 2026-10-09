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

/** 归档存放的位置。回滚点例外：它始终落在 [LOCAL]，即使被恢复的那一项在云端。 */
enum class BackupOrigin { LOCAL, CLOUD }

data class BackupFailure(
    val operation: String,
    val path: String,
    val storage: String,
    val cause: String,
    /** 服务端拒绝凭据（401/403）。UI 靠它把"请用应用密码"的提示送到用户面前。 */
    val authFailed: Boolean = false,
)

data class BackupRunResult(
    val kind: BackupKind,
    val written: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val failures: List<BackupFailure> = emptyList(),
) {
    val isSuccess: Boolean get() = failures.isEmpty()
    val isPartial: Boolean get() = failures.isNotEmpty() && written.isNotEmpty()
    val isFailure: Boolean get() = failures.isNotEmpty() && written.isEmpty()
}

data class RestoreOutcome(val success: Boolean, val detail: String)
