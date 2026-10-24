package com.sukisu.ultra.ui.screen.upload

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sukisu.ultra.ui.LocalUiMode
import com.sukisu.ultra.ui.UiMode
import com.sukisu.ultra.ui.navigation3.LocalNavigator

/**
 * 「模块上传」路由的入口：一个路由、两屏（列表 / 表单）、两套主题。
 *
 * 状态与网络都在 [ModuleUploadViewModel] 里，这里只做三件事：
 * 挑 zip 的 SAF 启动器、一次性提示（Toast）、按当前主题与步骤分派。
 *
 * 系统返回键：列表页退出这个路由；表单页回到列表（[BackHandler]），
 * 与界面上的返回箭头一致——挑完一批还能接着挑，不用把已经选好的包丢掉。
 */
@Composable
fun ModuleUploadScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val uiMode = LocalUiMode.current
    val viewModel = viewModel<ModuleUploadViewModel>()

    val step by viewModel.step.collectAsStateWithLifecycle()
    val listState by viewModel.listState.collectAsStateWithLifecycle()
    val formState by viewModel.formState.collectAsStateWithLifecycle()

    // 人机校验的 WebView 只在需要时弹出；它自己管生命周期。
    val turnstile = remember { WebViewTurnstileProvider() }

    LaunchedEffect(Unit) { viewModel.onEnter() }

    // 表单里系统返回 = 回列表（与左上角箭头同一件事），不是退出整个路由：
    // 直接 pop 会把这一批已选好的包和算好的 sha 一起丢掉。
    BackHandler(enabled = step == UploadStep.FORM) { viewModel.backToList() }

    // 一次性提示：staging 失败、id 不一致之类。用 Toast 是因为列表与表单的
    // snackbar host 分属两套 Scaffold，而这类消息不需要「操作按钮」。
    LaunchedEffect(listState.message) {
        val message = listState.message ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        viewModel.consumeMessage()
    }
    LaunchedEffect(formState.notice) {
        val notice = formState.notice ?: return@LaunchedEffect
        Toast.makeText(context, notice, Toast.LENGTH_LONG).show()
        viewModel.consumeNotice()
    }

    // 单行挑包：先把「为哪一行挑」记下来，SAF 回来时才知道该跟谁比 id。
    // 用 rememberSaveable：挑包期间 Activity 被回收重建时，这个 id 不能丢，
    // 否则回来时会把一个别的模块的 zip 当成这一行的包加进去。
    var pendingRowId by rememberSaveable { mutableStateOf<String?>(null) }
    val singleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val expectedId = pendingRowId
        pendingRowId = null
        if (uri != null) viewModel.stageForRow(context, uri, expectedId)
    }
    val multiLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.stageMany(context, uris)
    }

    val pickForRow: (UploadRow) -> Unit = { row ->
        pendingRowId = row.id
        singleLauncher.launch(arrayOf(ZIP_MIME))
    }
    val pickMany: () -> Unit = { multiLauncher.launch(arrayOf(ZIP_MIME)) }

    when (step) {
        UploadStep.LIST -> when (uiMode) {
            UiMode.Miuix -> ModuleUploadListMiuix(
                state = listState,
                onBack = { navigator.pop() },
                onRefresh = viewModel::refresh,
                onContinueCheck = viewModel::checkPending,
                onFilter = viewModel::setFilter,
                onQueryChange = viewModel::setQuery,
                onToggleRow = viewModel::toggleExpanded,
                onPickForRow = pickForRow,
                onPickZip = pickMany,
            )

            UiMode.Material -> ModuleUploadListMaterial(
                state = listState,
                onBack = { navigator.pop() },
                onRefresh = viewModel::refresh,
                onContinueCheck = viewModel::checkPending,
                onFilter = viewModel::setFilter,
                onQueryChange = viewModel::setQuery,
                onToggleRow = viewModel::toggleExpanded,
                onPickForRow = pickForRow,
                onPickZip = pickMany,
            )
        }

        UploadStep.FORM -> when (uiMode) {
            UiMode.Miuix -> ModuleUploadFormMiuix(
                state = formState,
                limits = listState.limits,
                onBack = viewModel::backToList,
                onRemove = viewModel::removeItem,
                onSourceChange = viewModel::updateSource,
                onApplySourceToAll = viewModel::applySourceToAll,
                onUidChange = viewModel::updateUid,
                onRetryUid = viewModel::retryUidLookup,
                onSelectItem = viewModel::selectItem,
                onPickMore = pickMany,
                onSignOut = viewModel::signOut,
                onConsentChange = viewModel::setConsent,
                onSignatureConfirmedChange = viewModel::setSignatureConfirmed,
                onSubmit = { viewModel.submit(turnstile) },
            )

            UiMode.Material -> ModuleUploadFormMaterial(
                state = formState,
                limits = listState.limits,
                onBack = viewModel::backToList,
                onRemove = viewModel::removeItem,
                onSourceChange = viewModel::updateSource,
                onApplySourceToAll = viewModel::applySourceToAll,
                onUidChange = viewModel::updateUid,
                onRetryUid = viewModel::retryUidLookup,
                onSelectItem = viewModel::selectItem,
                onPickMore = pickMany,
                onSignOut = viewModel::signOut,
                onConsentChange = viewModel::setConsent,
                onSignatureConfirmedChange = viewModel::setSignatureConfirmed,
                onSubmit = { viewModel.submit(turnstile) },
            )
        }
    }

    TurnstileDialog(turnstile)
}

/** SAF 的 MIME 过滤。与契约一致，只收 `application/zip`。 */
private const val ZIP_MIME = "application/zip"
