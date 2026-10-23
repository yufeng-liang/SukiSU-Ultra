package com.sukisu.ultra.ui.screen.kpm

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import com.sukisu.ultra.ui.component.resolveRuntimeStatus
import com.sukisu.ultra.ui.component.ConfigResult
import com.sukisu.ultra.ui.component.ConfigRuntimeStatus
import com.sukisu.ultra.ui.screen.susfs.util.SuSFSConfig
import kotlinx.coroutines.CancellationException
import com.sukisu.ultra.ui.util.getKsuDaemonPath
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.LocalUiMode
import com.sukisu.ultra.ui.UiMode
import com.sukisu.ultra.ui.component.dialog.ConfirmResult
import com.sukisu.ultra.ui.component.dialog.rememberConfirmDialog
import com.sukisu.ultra.ui.util.getRootShell
import com.sukisu.ultra.ui.util.loadKpmModule
import com.sukisu.ultra.ui.util.unloadKpmModule
import com.sukisu.ultra.ui.viewmodel.KpmViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun KpmScreen(
    bottomInnerPadding: Dp = 0.dp
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel = viewModel<KpmViewModel>()
    val confirmDialog = rememberConfirmDialog()

    val kpmInstallFailed = stringResource(R.string.kpm_install_failed)
    val invalidFileTypeMessage = stringResource(R.string.invalid_file_type)

    val showToast: suspend (String) -> Unit = { msg ->
        scope.launch(Dispatchers.Main) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    val selectPatchLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult

        val uri = result.data?.data ?: return@rememberLauncherForActivityResult

        scope.launch(Dispatchers.IO) {
            val fileName = uri.lastPathSegment ?: "unknown.kpm"
            val encodedFileName = URLEncoder.encode(fileName, "UTF-8")
            val tempFile = File(context.cacheDir, encodedFileName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (!isValidKpmFile(tempFile, context.contentResolver.getType(uri))) {
                withContext(Dispatchers.Main) {
                    showToast(invalidFileTypeMessage)
                }
                tempFile.delete()
                return@launch
            }

            val moduleName = withContext(Dispatchers.IO) {
                extractModuleName(tempFile)
            }
            withContext(Dispatchers.Main) {
                viewModel.showInstallDialog(moduleName ?: tempFile.nameWithoutExtension)
            }

            viewModel.setTempFileForInstall(tempFile)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            viewModel.fetchModuleList()
            delay(5000)
        }
    }

    val actions = KpmActions(
        onRefresh = { viewModel.fetchModuleList() },
        onSearchStatusChange = viewModel::updateSearchStatus,
        onSearchTextChange = { text ->
            scope.launch { viewModel.updateSearchText(text) }
        },
        onRequestInstall = {
            selectPatchLauncher.launch(
                Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "application/octet-stream"
                }
            )
        },
        onConfirmInstall = { _, isEmbed ->
            scope.launch {
                val tempFile = viewModel.getTempFile()
                tempFile?.let {
                    handleModuleInstall(
                        context = context,
                        tempFilePath = it.absolutePath,
                        isEmbed = isEmbed,
                        viewModel = viewModel,
                        showToast = showToast,
                        kpmInstallFailed = kpmInstallFailed
                    )
                }
            }
        },
        onDismissInstallDialog = {
            viewModel.dismissInstallDialog()
            viewModel.clearTempFile()
        },
        onRequestUninstall = { moduleId ->
            scope.launch {
                val confirmContent = context.getString(R.string.confirm_uninstall_content, moduleId)
                val confirmResult = confirmDialog.awaitConfirm(
                    title = context.getString(R.string.confirm_uninstall_title_with_filename),
                    content = confirmContent,
                    confirm = context.getString(R.string.uninstall),
                    dismiss = context.getString(R.string.cancel)
                )

                if (confirmResult == ConfirmResult.Confirmed) {
                    handleModuleUninstall(
                        context = context,
                        moduleId = moduleId,
                        showToast = showToast,
                        viewModel = viewModel
                    )
                }
            }
        },
        onConfirmUninstall = { },
        onDismissUninstallDialog = { },
        onShowInputDialog = viewModel::showInputDialog,
        onHideInputDialog = viewModel::hideInputDialog,
        onInputArgsChange = viewModel::updateInputArgs,
        onExecuteControl = {
            scope.launch {
                val result = viewModel.executeControl()
                val message = when (result) {
                    0 -> context.getString(R.string.kpm_control_success)
                    else -> context.getString(R.string.config_exit_code, result)
                }
                showToast(message)
            }
        },
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> KpmMiuix(
            viewModel = viewModel,
            actions = actions,
            bottomInnerPadding = bottomInnerPadding
        )
        UiMode.Material -> KpmMaterial(
            viewModel = viewModel,
            actions = actions,
            bottomInnerPadding = bottomInnerPadding
        )
    }
}

private suspend fun handleModuleInstall(
    context: Context,
    tempFilePath: String,
    isEmbed: Boolean,
    viewModel: KpmViewModel,
    showToast: suspend (String) -> Unit,
    kpmInstallFailed: String
) {
    val tempFile = File(tempFilePath)
    val moduleId = extractModuleName(tempFile)
    if (moduleId.isNullOrEmpty()) {
        showToast(kpmInstallFailed)
        tempFile.delete()
        return
    }

    val targetPath = "/data/adb/kpm/$moduleId.kpm"

    var saved = false
    try {
        withContext(Dispatchers.IO) {
            if (isEmbed) {
                val result = getRootShell().newJob().add(
                    "mkdir -p /data/adb/kpm && cp ${SuSFSConfig.shellQuote(tempFile.absolutePath)} ${SuSFSConfig.shellQuote(targetPath)}"
                ).exec()
                check(result.isSuccess) {
                    result.err.joinToString("\n").ifBlank { context.getString(R.string.config_exit_code, result.code) }
                }
                saved = true
            }
            check(loadKpmModule(tempFile.absolutePath)) {
                context.getString(R.string.config_command_rejected)
            }
            delay(500)
            val probe = getRootShell().newJob().add("${getKsuDaemonPath()} kpm list").exec()
            val runtime = resolveRuntimeStatus(null, true,
                if (probe.isSuccess) probe.out.any { it.trim() == moduleId } else null)
            viewModel.setOperationResult(ConfigResult(moduleId, saved, runtime))
        }
        viewModel.fetchModuleList()
        viewModel.uiState.value.operationResults.lastOrNull()?.let {
            showToast(it.description(context))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        viewModel.setOperationResult(ConfigResult(moduleId, saved, ConfigRuntimeStatus.FAILED,
            e.localizedMessage ?: context.getString(R.string.config_command_rejected)))
        showToast(kpmInstallFailed)
    }
    tempFile.delete()
    viewModel.dismissInstallDialog()
    viewModel.clearTempFile()
}

private suspend fun handleModuleUninstall(
    context: Context,
    moduleId: String,
    showToast: suspend (String) -> Unit,
    viewModel: KpmViewModel
) {
    val moduleFilePath = "/data/adb/kpm/$moduleId.kpm"
    try {
        withContext(Dispatchers.IO) {
            check(unloadKpmModule(moduleId)) {
                context.getString(R.string.config_command_rejected)
            }
            val removal = getRootShell().newJob().add(
                "rm -f -- ${SuSFSConfig.shellQuote(moduleFilePath)}"
            ).exec()
            val probe = getRootShell().newJob().add("${getKsuDaemonPath()} kpm list").exec()
            val runtime = resolveRuntimeStatus(null, false,
                if (probe.isSuccess) probe.out.any { it.trim() == moduleId } else null)
            viewModel.setOperationResult(ConfigResult(moduleId,
                if (removal.isSuccess) false else null, runtime,
                if (removal.isSuccess) null else removal.err.joinToString("\n").ifBlank {
                    context.getString(R.string.config_exit_code, removal.code)
                }))
        }
        viewModel.fetchModuleList()
        viewModel.uiState.value.operationResults.lastOrNull()?.let { showToast(it.description(context)) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val result = ConfigResult(moduleId, null, ConfigRuntimeStatus.FAILED,
            e.localizedMessage ?: context.getString(R.string.config_command_rejected))
        viewModel.setOperationResult(result)
        showToast(result.description(context))
    }
}
