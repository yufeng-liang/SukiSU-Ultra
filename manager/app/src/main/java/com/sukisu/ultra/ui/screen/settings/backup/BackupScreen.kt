package com.sukisu.ultra.ui.screen.settings.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sukisu.ultra.R
import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.ui.LocalUiMode
import com.sukisu.ultra.ui.UiMode
import com.sukisu.ultra.ui.component.dialog.ConfirmResult
import com.sukisu.ultra.ui.component.dialog.rememberConfirmDialog
import com.sukisu.ultra.ui.navigation3.LocalNavigator
import com.sukisu.ultra.ui.viewmodel.BackupViewModel
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.SnackbarHostState as MiuixSnackbarHostState

@Composable
fun BackupScreen(viewModel: BackupViewModel = viewModel()) {
    val navigator = LocalNavigator.current
    val uiMode = LocalUiMode.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingExport by remember { mutableStateOf<String?>(null) }

    // 两个主题各有一个宿主，消息通道只有 state.message 一条：在这里消费并清掉，
    // 否则主题各写一份（Material 弹完清、Miuix 只挂在行摘要上不清）就会漂成两种行为。
    val snackbarHostState = remember { SnackbarHostState() }
    val miuixSnackbarHostState = remember { MiuixSnackbarHostState() }

    LaunchedEffect(state.message, uiMode) {
        val message = state.message ?: return@LaunchedEffect
        if (uiMode == UiMode.Miuix) miuixSnackbarHostState.showSnackbar(message)
        else snackbarHostState.showSnackbar(message)
        // 带条件清空：弹窗期间新写入的 message 不能被这条已经显示过的消息顺手抹掉。
        viewModel.clearMessage(message)
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val name = pendingExport
        pendingExport = null
        if (uri != null && name != null) viewModel.exportTo(uri, name)
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importFrom(it) } }

    // 恢复原厂镜像会把当前已打补丁的 boot 换掉，设备直接回到未 root 状态——不可逆、且用户
    // 未必知道，所以这一项必须问一次。模块恢复只是重装一个模块，不需要拦。
    val confirmDialog = rememberConfirmDialog()
    val confirmScope = rememberCoroutineScope()
    val bootConfirmTitle = stringResource(R.string.backup_boot_restore_confirm_title)
    val bootConfirmBody = stringResource(R.string.backup_boot_restore_confirm_body)

    val actions = BackupActions(
        onBack = { navigator.pop() },
        onSelectOrigin = viewModel::selectOrigin,
        onSelectKind = viewModel::selectKind,
        onBackup = viewModel::backupNow,
        onRestore = { fileName ->
            if (state.kind == BackupKind.BOOT) {
                confirmScope.launch {
                    val confirmed = confirmDialog.awaitConfirm(
                        title = bootConfirmTitle,
                        content = bootConfirmBody,
                    ) == ConfirmResult.Confirmed
                    if (confirmed) viewModel.restore(fileName)
                }
            } else {
                viewModel.restore(fileName)
            }
        },
        onExport = { fileName ->
            pendingExport = fileName
            exportLauncher.launch(fileName)
        },
        onImport = { importLauncher.launch(arrayOf("*/*")) },
        onSetAutoBackup = viewModel::setAutoBackup,
        onToggleCloud = viewModel::toggleCloud,
        onSelectPreset = viewModel::selectPreset,
        onUrlChange = { viewModel.editCloud(url = it) },
        onUserChange = { viewModel.editCloud(user = it) },
        onPassChange = { viewModel.editCloud(pass = it) },
        onTestCloud = viewModel::testCloud,
        onSaveCloud = viewModel::saveCloudFromState,
    )

    when (uiMode) {
        UiMode.Miuix -> BackupMiuix(state, actions, miuixSnackbarHostState)
        UiMode.Material -> BackupMaterial(state, actions, snackbarHostState)
    }
}
