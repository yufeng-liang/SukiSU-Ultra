package com.sukisu.ultra.ui.screen.module

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.LocalUiMode
import com.sukisu.ultra.ui.UiMode
import com.sukisu.ultra.ui.component.ModuleContributionHost
import com.sukisu.ultra.ui.component.SearchStatus
import com.sukisu.ultra.ui.navigation3.LocalNavigator
import com.sukisu.ultra.ui.navigation3.Route
import com.sukisu.ultra.ui.screen.flash.FlashIt
import com.sukisu.ultra.ui.util.download
import com.sukisu.ultra.ui.util.module.Shortcut
import com.sukisu.ultra.ui.viewmodel.ModuleViewModel
import com.sukisu.ultra.ui.webui.WebUIActivity

@Composable
fun ModulePager(
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    val uiMode = LocalUiMode.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val resource = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel = viewModel<ModuleViewModel>()
    val scope = rememberCoroutineScope()
    val rawUiState by viewModel.uiState.collectAsStateWithLifecycle()

    val webUILauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { viewModel.fetchModuleList(resort = false) }

    // Request notification permission for download progress notifications
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Download works regardless of result */ }
    val latestIsCurrentPage by rememberUpdatedState(isCurrentPage)
    val initialResumeHandled = rememberSaveable { mutableStateOf(false) }

    var hasActivated by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) {
            if (!hasActivated) {
                hasActivated = true
                viewModel.refreshEnvironmentState()
                viewModel.initializePreferences()
                val state = viewModel.uiState.value
                if (!state.hasLoaded && !state.isRefreshing) {
                    viewModel.fetchModuleList()
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else if (!rawUiState.searchStatus.isCollapsed()) {
            viewModel.updateSearchStatus(rawUiState.searchStatus.copy(searchText = "", current = SearchStatus.Status.COLLAPSED))
        }
    }

    LifecycleResumeEffect(Unit) {
        if (initialResumeHandled.value && latestIsCurrentPage) {
            val state = viewModel.uiState.value
            if (!state.isRefreshing) {
                viewModel.fetchModuleList(
                    checkUpdate = !state.hasLoaded || viewModel.isNeedRefresh,
                    resort = !state.hasLoaded,
                )
            }
        }
        initialResumeHandled.value = true
        onPauseOrDispose {}
    }

    // ── 投稿入口 ────────────────────────────────────────────────────────────
    // 两套界面各自的 snackbar host 里弹「分享」提示，所以控制器只负责"该不该弹"，
    // 真正弹出去与点「分享」后打开对话框都在界面那一侧完成。
    val submittingText = stringResource(R.string.module_contribution_submitting)
    val contribution = rememberModuleContributionController(
        scope = scope,
        submittingText = { submittingText },
    )
    LaunchedEffect(isCurrentPage) {
        // 回到模块页时重读一次开关：设置页刚改过的话这里要立刻生效。
        if (isCurrentPage) contribution.refreshEnabled()
    }

    // 本地 zip 安装：走 Flash 路由，装完回到这一页时**主动**查一次收录状态，
    // 只在查出来是新模块时提示分享。
    //
    // 从模块仓库下载安装的那条路（onConfirmUpdate）不挂这个标记，所以它**不会被主动提示**——
    // 那条路上的模块本来就来自公开索引，再问一次"要不要分享"是在烦人。
    // 注意这说的只是"主动提示"：列表渲染到某一格时仍会静默查一次
    // （见 ModuleMaterial / ModuleMiuix 里的 contribution.rememberChecked），
    // 那次查的结果只用来决定投稿按钮显示与否，不会弹任何东西。
    //
    // 认人的办法是"装之前记下已装 id，装之后看谁是新来的"，而不是拿文件名猜 id：
    // 文件名与 module.prop 里的 id 没有必然关系（用户随便改名），猜错的话这次就静默了。
    var pendingLocalZipIds by remember { mutableStateOf<Map<String, Long>?>(null) }
    LaunchedEffect(pendingLocalZipIds, rawUiState.modules) {
        val before = pendingLocalZipIds ?: return@LaunchedEffect
        val current = rawUiState.modules.associate { it.id to it.versionCode }
        // 关键：列表没变就继续等，绝不在这里清掉标记。
        // 按下安装那一刻这个 effect 会立刻跑一次，此时 before 与当前列表完全相同，
        // 若那时就"结算"，标记会在 ksud 开装之前就被清掉，装完再也认不出新模块。
        //
        // 比的是 id→versionCode 而不只是 id 集合：原地升级同一个模块时 id 不变，
        // 只看 id 会认不出"装完了"，标记就一直挂着。
        if (current == before) return@LaunchedEffect
        pendingLocalZipIds = null
        // 装失败时列表不会有任何变化，installed 为 null → 静默，不提示。
        val installed = rawUiState.modules.firstOrNull { it.versionCode != before[it.id] }
        // 一次只提示一个：多个 zip 一起装也只弹一条，弹三条就是在催人关掉开关。
        contribution.checkAfterLocalZipInstall(installed)
    }

    val actions = ModuleActions(
        onRefresh = {
            viewModel.fetchModuleList(checkUpdate = true)
        },
        onSearchStatusChange = {
            viewModel.updateSearchStatus(it)
        },
        onSearchTextChange = { text ->
            viewModel.updateSearchText(text)
        },
        onClearSearch = {
            viewModel.updateSearchText("")
        },
        onRequestUpdateConfirmation = { module, updateInfo ->
            viewModel.requestUpdateConfirmation(module, updateInfo)
        },
        onRequestUninstallConfirmation = { module ->
            viewModel.requestUninstallConfirmation(module)
        },
        onDismissConfirmRequest = {
            viewModel.dismissConfirmRequest()
        },
        onConfirmUpdate = { request ->
            scope.launch {
                download(
                    url = request.downloadUrl,
                    fileName = request.fileName,
                    onDownloaded = { uri ->
                        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            navigator.push(Route.Flash(FlashIt.FlashModules(listOf(uri))))
                            viewModel.markNeedRefresh()
                        }
                    },
                    onDownloading = {
                        viewModel.emitEffect(
                            ModuleEffect.Toast(
                                resource.getString(R.string.module_downloading).format(request.module.name)
                            )
                        )
                    },
                )
            }
            viewModel.dismissConfirmRequest()
        },
        onOpenRepo = { navigator.push(Route.ModuleRepo) },
        onToggleSortActionFirst = {
            viewModel.toggleSortActionFirst()
        },
        onToggleSortEnabledFirst = {
            viewModel.toggleSortEnabledFirst()
        },
        onOpenWebUi = { module ->
            webUILauncher.launch(
                Intent(context, WebUIActivity::class.java)
                    .setData(
                        Shortcut.buildShortcutUri(module.id, ShortcutType.WebUI)
                    )
            )
        },
        onToggleModule = { module ->
            viewModel.toggleModule(module)
        },
        onUninstallModule = { module ->
            viewModel.uninstallModule(module)
        },
        onUndoUninstallModule = { module ->
            viewModel.undoUninstallModule(module)
        },
        onOpenFlash = { uris ->
            if (uris.isNotEmpty()) {
                // 记下"现在装了哪些、各是什么版本"，装完回到列表时用它认出新装上的那一个。
                // 用列表里的 id 而不是文件名猜：文件名与 module.prop 里的 id 没有必然关系。
                if (pendingLocalZipIds == null) {
                    pendingLocalZipIds = rawUiState.modules.associate { it.id to it.versionCode }
                }
                navigator.push(Route.Flash(FlashIt.FlashModules(uris)))
                viewModel.markNeedRefresh()
            }
        },
        onExecuteModuleAction = { module ->
            navigator.push(Route.ExecuteModuleAction(module.id))
            viewModel.markNeedRefresh()
        },
    )

    when (uiMode) {
        UiMode.Miuix -> ModulePagerMiuix(
            uiState = rawUiState,
            confirmDialogState = rawUiState.confirmDialogState,
            moduleEvent = viewModel.moduleEvent,
            actions = actions,
            contribution = contribution,
            bottomInnerPadding = bottomInnerPadding,
        )

        UiMode.Material -> ModulePagerMaterial(
            uiState = rawUiState,
            confirmDialogState = rawUiState.confirmDialogState,
            moduleEvent = viewModel.moduleEvent,
            actions = actions,
            contribution = contribution,
            bottomInnerPadding = bottomInnerPadding,
        )
    }

    ModuleContributionHost(contribution.dialog)
}
