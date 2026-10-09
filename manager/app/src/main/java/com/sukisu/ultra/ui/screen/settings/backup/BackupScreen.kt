package com.sukisu.ultra.ui.screen.settings.backup

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sukisu.ultra.BuildConfig
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
    val context = LocalContext.current
    // 列表的滚动位置挂在这一层：点进详情页时列表会被换掉，位置若跟着它一起销毁，退回来就
    // 回到最顶上了——用户是"点开看一眼再回来"，不该为此丢掉刚才翻到的地方。两个分页各一份，
    // 表单和清单的长度差得远。
    val backupListState = rememberLazyListState()
    val restoreListState = rememberLazyListState()

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

    // 导出走系统分享面板而不是 SAF 的"另存为"：一次备份是一整份（11 个模块打成 zip），
    // 分享面板同时给"发给谁"和"存到文件"，SAF 只能覆盖后一半。
    LaunchedEffect(state.pendingShare) {
        val file = state.pendingShare ?: return@LaunchedEffect
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        val mime = if (file.name.endsWith(".zip", ignoreCase = true)) "application/zip" else "application/octet-stream"
        val share = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(share, context.getString(R.string.backup_share_chooser))
        )
        viewModel.consumeShare()
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
    val deleteConfirmTitle = stringResource(R.string.backup_delete_confirm_title)
    val deleteSelectedConfirmTitle = stringResource(R.string.backup_delete_selected_confirm_title)

    val actions = BackupActions(
        onBack = { navigator.pop() },
        onSelectTab = viewModel::selectTab,
        onToggleOrigin = viewModel::toggleOrigin,
        onToggleKind = viewModel::toggleKind,
        onBackup = viewModel::backupNow,
        onOpenGroup = viewModel::openGroup,
        onCloseGroup = viewModel::closeGroup,
        onToggleGroupEntry = viewModel::toggleGroupEntry,
        onSetAllGroupEntries = viewModel::setAllGroupEntries,
        onRestoreSelected = {
            // 选中的里面有原厂镜像就必须先问一次：恢复它等于把设备带回未 root 状态，
            // 不可逆，而且用户未必知道自己勾上了它。
            if (state.openGroupSelection.any { it.kind == BackupKind.BOOT }) {
                confirmScope.launch {
                    val confirmed = confirmDialog.awaitConfirm(
                        title = bootConfirmTitle,
                        content = bootConfirmBody,
                    ) == ConfirmResult.Confirmed
                    if (confirmed) viewModel.restoreSelected()
                }
            } else {
                viewModel.restoreSelected()
            }
        },
        onShareSelected = viewModel::shareSelected,
        onShareConsumed = viewModel::consumeShare,
        onDismissBackupRun = viewModel::dismissBackupRun,
        onDeleteGroup = {
            // 删的是文件，删完找不回来；而且云端那份也一起删，所以要说清动的是哪一侧。
            state.openGroup?.let { group ->
                confirmScope.launch {
                    val originLabel = context.getString(BackupLabels.origin(group.origin))
                    val confirmed = confirmDialog.awaitConfirm(
                        title = deleteConfirmTitle,
                        content = context.getString(
                            R.string.backup_delete_confirm_body,
                            originLabel,
                            group.rows.size,
                        ),
                    ) == ConfirmResult.Confirmed
                    if (confirmed) viewModel.deleteGroup()
                }
            }
        },
        onImport = { importLauncher.launch(arrayOf("*/*")) },
        onStartSelection = viewModel::startSelection,
        onToggleGroupSelection = viewModel::toggleGroupSelection,
        onClearSelection = viewModel::clearSelection,
        onDeleteSelected = {
            // 一次删好几份、两侧的文件都动，所以要把"几份、几条"都说出来——只说"删掉选中的"
            // 用户没法判断自己是不是多勾了一份。
            val groups = state.selectedGroups
            if (groups.isNotEmpty()) {
                confirmScope.launch {
                    val confirmed = confirmDialog.awaitConfirm(
                        title = deleteSelectedConfirmTitle,
                        content = context.getString(
                            R.string.backup_delete_selected_confirm_body,
                            groups.size,
                            groups.sumOf { it.rows.size },
                        ),
                    ) == ConfirmResult.Confirmed
                    if (confirmed) viewModel.deleteSelected()
                }
            }
        },
        onSetAutoBackup = viewModel::setAutoBackup,
        onSetAutoBackupLocal = viewModel::setAutoBackupLocal,
        onSetAutoBackupCloud = viewModel::setAutoBackupCloud,
        onToggleCloud = viewModel::toggleCloud,
        onToggleModules = viewModel::toggleModules,
        onToggleModule = viewModel::toggleModule,
        onSetAllModules = viewModel::setAllModules,
        onSelectPreset = viewModel::selectPreset,
        onUrlChange = { viewModel.editCloud(url = it) },
        onUserChange = { viewModel.editCloud(user = it) },
        onPassChange = { viewModel.editCloud(pass = it) },
        onTestCloud = viewModel::testCloud,
        onSaveCloud = viewModel::saveCloudFromState,
    )

    // 备份详情是这一页里的一层，不是另一条路由：系统返回键得先退回列表，否则用户从详情页
    // 往回滑就直接离开了整个备份页。
    val openGroup = state.openGroup
    BackHandler(enabled = openGroup != null) { viewModel.closeGroup() }

    // 多选是这一页里的另一层：返回键先退出多选，而不是把整页关掉——手上还勾着几份东西时
    // 一下退出整页，那几步勾选就白做了。
    BackHandler(enabled = state.selecting) { viewModel.clearSelection() }

    // 进/出详情走一次横向推入推出：硬切会让人以为刚才那下点空了。返回时列表滑回来的方向
    // 反过来，和系统返回手势的方向一致。
    //
    // contentKey 只看"是不是详情页"：刷新列表（恢复/删除之后）会让分组对象换一份新的，
    // 按对象比较会把它当成一次页面切换，白白重放一遍动画。
    AnimatedContent(
        targetState = openGroup,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally { it } + fadeIn(tween(PAGE_MS))) togetherWith
                    (slideOutHorizontally { -it / 4 } + fadeOut(tween(PAGE_MS)))
            } else {
                (slideInHorizontally { -it / 4 } + fadeIn(tween(PAGE_MS))) togetherWith
                    (slideOutHorizontally { it } + fadeOut(tween(PAGE_MS)))
            }
        },
        contentKey = { it != null },
        label = "backupDetail",
    ) { group ->
        when (uiMode) {
            UiMode.Miuix -> if (group != null) {
                BackupDetailMiuix(group, state, actions, miuixSnackbarHostState)
            } else {
                BackupMiuix(state, actions, miuixSnackbarHostState, backupListState, restoreListState)
            }

            UiMode.Material -> if (group != null) {
                BackupDetailMaterial(group, state, actions, snackbarHostState)
            } else {
                BackupMaterial(state, actions, snackbarHostState, backupListState, restoreListState)
            }
        }
    }
}

/** 进/出备份详情的动画时长。 */
private const val PAGE_MS = 260

/**
 * 分组卡片的一行说明：来源（两侧都勾上时才写）· 内容 · 项数。
 *
 * 两个主题共用：分组卡片的措辞只有一处，改的时候不会只改一边。
 */
@Composable
fun groupSummary(group: BackupGroup, showsOrigin: Boolean): String = BackupLabels.groupSummary(
    originLabel = stringResource(BackupLabels.origin(group.origin)).takeIf { showsOrigin },
    kindLabel = stringResource(BackupLabels.groupKind(group)),
    countText = pluralStringResource(R.plurals.backup_group_items, group.rows.size, group.rows.size),
)

/**
 * 分页标签。恢复页带上"列表里有几份"：切过去之前就知道那边有没有东西，省一次来回。
 *
 * 一份都没有时不写 `(0)`——括号里挂个零看着像出错，而且那时候列表里本来就有空状态那句话。
 */
@Composable
fun tabLabel(tab: BackupTab, count: Int): String = if (tab == BackupTab.RESTORE && count > 0) {
    stringResource(R.string.backup_tab_restore_count, count)
} else {
    stringResource(BackupLabels.tab(tab))
}
