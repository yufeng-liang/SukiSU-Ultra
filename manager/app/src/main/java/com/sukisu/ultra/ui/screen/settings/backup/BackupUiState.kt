package com.sukisu.ultra.ui.screen.settings.backup

import androidx.compose.runtime.Immutable
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
    val onUrlChange: (String) -> Unit,
    val onUserChange: (String) -> Unit,
    val onPassChange: (String) -> Unit,
    val onTestCloud: () -> Unit,
    val onSaveCloud: () -> Unit,
)
