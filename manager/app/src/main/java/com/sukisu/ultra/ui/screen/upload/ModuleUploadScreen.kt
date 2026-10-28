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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.LocalUiMode
import com.sukisu.ultra.ui.UiMode
import com.sukisu.ultra.ui.component.dialog.ConfirmResult
import com.sukisu.ultra.ui.component.dialog.rememberConfirmDialog
import com.sukisu.ultra.ui.navigation3.LocalNavigator
import com.sukisu.ultra.ui.util.MineModule
import com.sukisu.ultra.ui.util.formatFileSize
import kotlinx.coroutines.launch

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
    val mineState by viewModel.mineState.collectAsStateWithLifecycle()

    // 人机校验的 WebView 只在需要时弹出；它自己管生命周期。
    val turnstile = remember { WebViewTurnstileProvider() }

    LaunchedEffect(Unit) { viewModel.onEnter() }

    // 「我的模块」只在列表页可见，所以要按「进入列表」来拉：初进、从表单返回、
    // 投完一批回来都算；放在 Unit 里只拉一次，投完回来就还是旧名单。
    LaunchedEffect(step) {
        if (step == UploadStep.LIST) viewModel.refreshMine()
    }

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
    // 「我的模块」的补充说明（例如「名单还在，但这次没刷新成功」）同样是一次性提示：
    // 常态的四种落点由列表自己按标志渲染，这里只消化会过期的那一句。
    LaunchedEffect(mineState.message) {
        val message = mineState.message ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        viewModel.consumeMineMessage()
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
    // 给已收录的模块挑新版：与「为某一行挑包」走同一个启动器、同一个 pendingRowId，
    // 区别只在期望的模块 id 从哪来——这里来自服务端签名的索引，不是本地解析。
    // 回来时照样比对包里的 id，选错了包不会被悄悄装到另一个模块名下。
    val pickForMine: (MineModule) -> Unit = { module ->
        pendingRowId = module.id
        singleLauncher.launch(arrayOf(ZIP_MIME))
    }
    val pickMany: () -> Unit = { multiLauncher.launch(arrayOf(ZIP_MIME)) }
    val useArchived: (UploadRow) -> Unit = { row -> viewModel.stageArchived(row) }

    // 顶栏「更多」背后的小面板：留存开关 + 清理入口。用状态位而不是对话框句柄，
    // 因为它要显示实时占用（archivedCount / archivedBytes），必须跟着 state 重组。
    // 用 rememberSaveable：面板开着时被系统回收，回来不该自己关掉（与上面 pendingRowId 同理）。
    var showArchiveSettings by rememberSaveable { mutableStateOf(false) }

    // 清理留存包是删数据，走一次确认；数字与容量在弹窗里说清楚，
    // 免得用户点完才发现「下次上传要手动挑包了」。
    val confirmDialog = rememberConfirmDialog()
    val scope = rememberCoroutineScope()
    var clearing by remember { mutableStateOf(false) }
    val clearArchived: (onCancelled: () -> Unit) -> Unit = { onCancelled ->
        if (!clearing) {
            clearing = true
            scope.launch {
                try {
                    val result = confirmDialog.awaitConfirm(
                        title = context.getString(R.string.module_upload_clear_archived_zip),
                        content = context.getString(
                            R.string.module_upload_clear_archived_zip_confirm,
                            listState.archivedCount,
                            formatFileSize(listState.archivedBytes),
                        ),
                        confirm = context.getString(R.string.module_upload_clear_archived_zip),
                        dismiss = context.getString(R.string.cancel),
                    )
                    if (result == ConfirmResult.Confirmed) viewModel.clearArchived() else onCancelled()
                } finally {
                    clearing = false
                }
            }
        }
    }

    // 只删「已卸载模块」遗留的包。与上面共用一个 clearing 闸门：两者改的是同一个目录，
    // 允许并发只会让确认弹窗叠在一起、报数互相覆盖。
    val clearOrphans: (onCancelled: () -> Unit) -> Unit = { onCancelled ->
        if (!clearing) {
            clearing = true
            scope.launch {
                try {
                    val result = confirmDialog.awaitConfirm(
                        title = context.getString(R.string.module_upload_clear_orphan_zip),
                        content = context.getString(
                            R.string.module_upload_clear_orphan_zip_confirm,
                            listState.orphanCount,
                        ),
                        confirm = context.getString(R.string.module_upload_clear_orphan_zip),
                        dismiss = context.getString(R.string.cancel),
                    )
                    if (result == ConfirmResult.Confirmed) viewModel.clearOrphanArchived() else onCancelled()
                } finally {
                    clearing = false
                }
            }
        }
    }

    when (step) {
        UploadStep.LIST -> when (uiMode) {
            UiMode.Miuix -> ModuleUploadListMiuix(
                state = listState,
                onBack = { navigator.pop() },
                onRefresh = viewModel::refresh,
                onContinueCheck = viewModel::checkPending,
                onFilter = viewModel::setFilter,
                onSearchStatusChange = viewModel::updateSearchStatus,
                onToggleRow = viewModel::toggleExpanded,
                onPickForRow = pickForRow,
                onUseArchived = useArchived,
                onPickZip = pickMany,
                onOpenArchiveSettings = { showArchiveSettings = true },
                mine = mineState,
                onPickForMine = pickForMine,
            )

            UiMode.Material -> ModuleUploadListMaterial(
                state = listState,
                onBack = { navigator.pop() },
                onRefresh = viewModel::refresh,
                onContinueCheck = viewModel::checkPending,
                onFilter = viewModel::setFilter,
                onSearchTextChange = viewModel::updateSearchText,
                onSearchStatusChange = viewModel::updateSearchStatus,
                onToggleRow = viewModel::toggleExpanded,
                onPickForRow = pickForRow,
                onUseArchived = useArchived,
                onPickZip = pickMany,
                onOpenArchiveSettings = { showArchiveSettings = true },
                mine = mineState,
                onPickForMine = pickForMine,
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

    if (showArchiveSettings) {
        ModuleUploadArchiveDialog(
            state = listState,
            onKeepZipChange = viewModel::setKeepModuleZip,
            onClearArchived = {
                // 先收起面板再弹确认：两层对话框叠着会把「删多少个」挡住。
                // 取消时把面板放回来，否则用户想改开关得重新点一次「更多」。
                showArchiveSettings = false
                clearArchived { showArchiveSettings = true }
            },
            onClearOrphans = {
                showArchiveSettings = false
                clearOrphans { showArchiveSettings = true }
            },
            onDismissRequest = { showArchiveSettings = false },
        )
    }

    TurnstileDialog(turnstile)
}

/** SAF 的 MIME 过滤。与契约一致，只收 `application/zip`。 */
private const val ZIP_MIME = "application/zip"
