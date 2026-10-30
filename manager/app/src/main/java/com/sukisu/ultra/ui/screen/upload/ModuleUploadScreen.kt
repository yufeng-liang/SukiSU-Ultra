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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.LocalUiMode
import com.sukisu.ultra.ui.component.CoolapkUidState
import com.sukisu.ultra.ui.UiMode
import com.sukisu.ultra.ui.component.dialog.ConfirmResult
import com.sukisu.ultra.ui.component.dialog.rememberConfirmDialog
import com.sukisu.ultra.ui.navigation3.LocalNavigator
import com.sukisu.ultra.ui.util.MineModule
import com.sukisu.ultra.ui.util.formatFileSize
import kotlinx.coroutines.launch

/**
 * 「模块上传」路由的入口：一个路由、四屏（身份 / 列表 / 表单 / 上传记录）、两套主题。
 *
 * 状态与网络都在 [ModuleUploadViewModel] 里，这里只做三件事：
 * 挑 zip 的 SAF 启动器、一次性提示（Toast）、按当前主题与步骤分派。
 *
 * 系统返回键：列表页退出这个路由；表单页与上传记录页回到列表（[BackHandler]），
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
    // 挑错包 / 版本对不上这类长提示：单独一条通道，走确认框而不是 Toast。
    // 名字带 upload 前缀是为了与下面表单自己的 formState.notice 分清（两者不是一回事）。
    val uploadNotice by viewModel.notice.collectAsStateWithLifecycle()

    // 顶栏头像：核验过就有地址可画（会话里存的那份是登录时留下的，令牌过期也还在）。
    val avatarUrl = (formState.uidState as? CoolapkUidState.Verified)?.avatar
        ?.ifBlank { formState.session?.avatar.orEmpty() }
        .orEmpty()

    // 人机校验的 WebView 只在需要时弹出；它自己管生命周期。
    val turnstile = remember { WebViewTurnstileProvider() }

    LaunchedEffect(Unit) { viewModel.onEnter() }

    // 上传记录页只在需要时拉一次：进列表时拉（列表行上的「上传更新」标签要它），
    // 点进记录页时另有一次（[ModuleUploadViewModel.openHistory] 自己会刷）。
    LaunchedEffect(step) {
        if (step == UploadStep.LIST) viewModel.refreshMine()
    }

    // 表单与记录页的系统返回 = 回列表（与左上角箭头同一件事），不是退出整个路由：
    // 直接 pop 会把这一批已选好的包和算好的 sha 一起丢掉。
    // 身份页是例外：那时候手上还没有令牌，回列表只是个空壳，按返回就该退出这条路由。
    BackHandler(enabled = step != UploadStep.LIST) {
        if (step == UploadStep.IDENTITY) navigator.pop() else viewModel.backToList()
    }

    // 一次性提示：复制失败、超过上限这类一句话能说完的。用 Toast 是因为列表与表单的
    // snackbar host 分属两套 Scaffold，而这类消息不需要「操作按钮」。
    // 长的那些（挑错包、版本对不上）不走这里 —— 见下面的 [UploadNotice] 通道。
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

    // 「为什么只能传原始 zip」的说明。本地状态而不是 VM 状态：它跟数据无关，
    // 只是这一段界面的开关，页面被回收后自己关掉也不可惜。
    var showUploadInfo by rememberSaveable { mutableStateOf(false) }

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
        UploadStep.IDENTITY -> when (uiMode) {
            UiMode.Miuix -> ModuleUploadIdentityMiuix(
                state = formState,
                onExit = { navigator.pop() },
                onUidChange = viewModel::updateUid,
                onRetryUid = viewModel::retryUidLookup,
                onConsentChange = viewModel::setConsent,
                onRenewCommit = viewModel::renewIdentityCommit,
                onVerify = viewModel::verifyIdentity,
            )

            UiMode.Material -> ModuleUploadIdentityMaterial(
                state = formState,
                onExit = { navigator.pop() },
                onUidChange = viewModel::updateUid,
                onRetryUid = viewModel::retryUidLookup,
                onConsentChange = viewModel::setConsent,
                onRenewCommit = viewModel::renewIdentityCommit,
                onVerify = viewModel::verifyIdentity,
            )
        }

        UploadStep.LIST -> when (uiMode) {
            UiMode.Miuix -> ModuleUploadListMiuix(
                state = listState,
                onBack = { navigator.pop() },
                onRefresh = viewModel::refresh,
                onFilter = viewModel::setFilter,
                onSearchStatusChange = viewModel::updateSearchStatus,
                onToggleRow = viewModel::toggleExpanded,
                onPickForRow = pickForRow,
                onUseArchived = useArchived,
                onOpenInfo = { showUploadInfo = true },
                onPickZip = pickMany,
                onOpenArchiveSettings = { showArchiveSettings = true },
                mine = mineState,
                avatarUrl = avatarUrl,
                onOpenHistory = viewModel::openHistory,
            )

            UiMode.Material -> ModuleUploadListMaterial(
                state = listState,
                onBack = { navigator.pop() },
                onRefresh = viewModel::refresh,
                onFilter = viewModel::setFilter,
                onSearchTextChange = viewModel::updateSearchText,
                onSearchStatusChange = viewModel::updateSearchStatus,
                onToggleRow = viewModel::toggleExpanded,
                onPickForRow = pickForRow,
                onUseArchived = useArchived,
                onOpenInfo = { showUploadInfo = true },
                onPickZip = pickMany,
                onOpenArchiveSettings = { showArchiveSettings = true },
                mine = mineState,
                avatarUrl = avatarUrl,
                onOpenHistory = viewModel::openHistory,
            )
        }

        UploadStep.HISTORY -> when (uiMode) {
            UiMode.Miuix -> ModuleUploadHistoryMiuix(
                state = listState,
                mine = mineState,
                onBack = viewModel::backToList,
                onRefresh = viewModel::refreshMine,
                onPickForMine = pickForMine,
                onSignOut = viewModel::signOut,
            )

            UiMode.Material -> ModuleUploadHistoryMaterial(
                state = listState,
                mine = mineState,
                onBack = viewModel::backToList,
                onRefresh = viewModel::refreshMine,
                onPickForMine = pickForMine,
                onSignOut = viewModel::signOut,
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

    // 顶栏 i 的说明。与「这个包不能用」共用同一个对话框外壳：两处都是「读完才关」，
    // 而且都不能被系统截断。
    if (showUploadInfo) {
        ModuleUploadNoticeDialog(
            notice = UploadNotice(
                title = stringResource(R.string.module_upload_info_title),
                body = stringResource(R.string.module_upload_info_body),
            ),
            onDismiss = { showUploadInfo = false },
        )
    }

    // 放在最外层：挑包可能发生在列表页（为某一行挑）也可能发生在表单/记录页
    // （为已收录的模块挑新版），而它是整屏级别的阻断提示，不该跟着某一屏走。
    uploadNotice?.let { ModuleUploadNoticeDialog(notice = it, onDismiss = viewModel::dismissNotice) }
}

/** SAF 的 MIME 过滤。与契约一致，只收 `application/zip`。 */
private const val ZIP_MIME = "application/zip"
