package com.sukisu.ultra.ui.screen.settings.backup

import androidx.compose.runtime.Immutable
import com.sukisu.ultra.data.backup.AutoBackupRecord
import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin

@Immutable
data class BackupUiState(
    val loading: Boolean = false,
    val origin: BackupOrigin = BackupOrigin.LOCAL,
    val kind: BackupKind = BackupKind.MODULE,
    val rows: List<BackupRow> = emptyList(),
    val message: String? = null,
    val cloudUrl: String = "",
    val cloudUser: String = "",
    val cloudPass: String = "",
    val cloudConfigured: Boolean = false,
    /** 安装模块成功后是否自动备份一次。默认开。 */
    val autoBackupEnabled: Boolean = true,
    /**
     * 上一次自动备份的结果。
     *
     * 它可能在任何一个界面跑完，所以状态必须从磁盘读回来；null = 还没跑过。
     * 关闭开关后仍然显示——历史是事实，不该被一个开关抹掉。
     */
    val autoBackupRecord: AutoBackupRecord? = null,
)

@Immutable
data class BackupActions(
    val onBack: () -> Unit,
    val onSelectOrigin: (BackupOrigin) -> Unit,
    val onSelectKind: (BackupKind) -> Unit,
    val onBackup: () -> Unit,
    val onRestore: (String) -> Unit,
    val onExport: (String) -> Unit,
    val onImport: () -> Unit,
    val onSetAutoBackup: (Boolean) -> Unit,
    val onUrlChange: (String) -> Unit,
    val onUserChange: (String) -> Unit,
    val onPassChange: (String) -> Unit,
    val onTestCloud: () -> Unit,
    val onSaveCloud: () -> Unit,
)
