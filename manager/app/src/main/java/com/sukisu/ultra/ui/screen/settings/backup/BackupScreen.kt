package com.sukisu.ultra.ui.screen.settings.backup

import android.content.Intent
import android.os.Build
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
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sukisu.ultra.BuildConfig
import com.sukisu.ultra.R
import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin
import com.sukisu.ultra.data.backup.partitionFromName
import com.sukisu.ultra.ui.LocalUiMode
import com.sukisu.ultra.ui.UiMode
import com.sukisu.ultra.ui.component.dialog.ConfirmResult
import com.sukisu.ultra.ui.component.dialog.rememberConfirmDialog
import com.sukisu.ultra.ui.navigation3.LocalNavigator
import com.sukisu.ultra.ui.viewmodel.BackupViewModel
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.SnackbarHostState as MiuixSnackbarHostState
import top.yukonga.miuix.kmp.theme.MiuixTheme

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

    // 恢复分区快照是往块设备写镜像：写错分区、或者镜像和现在的分区长度不符，都可能开不了机，
    // 而且没有撤销——所以这一项必须问一次。单个模块恢复只是重装那一个模块，不必拦；但单条
    // 仓库源恢复是整批替换（点一下就把现在配的全部源换成归档里的），也要问；详情页一进来就是
    // 全选，"恢复"一下可能把整组模块按备份里的版本重装一遍，同样要问（见下）。
    val confirmDialog = rememberConfirmDialog()
    val confirmScope = rememberCoroutineScope()
    val partitionConfirmTitle = stringResource(R.string.backup_partition_restore_confirm_title)
    val partitionConfirmBody = stringResource(R.string.backup_partition_restore_confirm_body)
    val deleteConfirmTitle = stringResource(R.string.backup_delete_confirm_title)
    val deleteSelectedConfirmTitle = stringResource(R.string.backup_delete_selected_confirm_title)
    // 标题里要拼条数，而拼的时候在普通 lambda 里：stringResource 只能在 @Composable 里调，
    // 正文先在这里取好。
    val restoreManyConfirmBody = stringResource(R.string.backup_restore_many_confirm_body)
    val repoRestoreConfirmTitle = stringResource(R.string.backup_repo_restore_confirm_title)
    val repoRestoreConfirmBody = stringResource(R.string.backup_repo_restore_confirm_body)

    val actions = BackupActions(
        onBack = { navigator.pop() },
        onSelectTab = viewModel::selectTab,
        onToggleOrigin = viewModel::toggleOrigin,
        onSelectOrigins = viewModel::setOrigins,
        onToggleKind = viewModel::toggleKind,
        onBackup = viewModel::backupNow,
        onOpenGroup = viewModel::openGroup,
        onCloseGroup = viewModel::closeGroup,
        onToggleGroupOrigin = viewModel::toggleGroupOrigin,
        onSetOriginEntries = viewModel::setOriginEntries,
        onToggleGroupEntry = viewModel::toggleGroupEntry,
        onRestoreSelected = {
            // 恢复要过两道确认：先问「这份包该不该装到这台机器上」（设备闸门），
            // 再问「这一步会造成什么后果」（分区不可逆 / 多条覆盖 / 整批替换）。
            val selection = state.openGroupSelection
            // 设备闸门：比「恢复几条」「刷哪个分区」都更靠前——后两者问的是「怎么恢复」，
            // 这一步问的是「这份包该不该装到这台机器上」。
            //
            // 模块备份存的是当时解包出来的文件（含当时选定的架构变体、安装脚本写进去的
            // 机型配置），恢复不会重跑安装脚本。换机型恢复未必出事，但用户有权事先知道
            // 自己在跨机型搬东西，而不是装完发现不对劲。
            confirmScope.launch {
                val foreign = restoreSourceDevices(oneCopyPerContent(selection), Build.MODEL.orEmpty())
                if (foreign.isNotEmpty()) {
                    val confirmed = confirmDialog.awaitConfirm(
                        title = context.getString(R.string.backup_device_mismatch_title),
                        content = context.getString(
                            R.string.backup_device_mismatch_body,
                            foreign.joinToString(
                                context.getString(R.string.backup_list_separator),
                            ),
                            Build.MODEL.orEmpty(),
                        ),
                    ) == ConfirmResult.Confirmed
                    if (!confirmed) return@launch
                }
                when (restoreConfirmFor(selection)) {
                    RestoreConfirm.PARTITION -> confirmScope.launch {
                        // 原子选择保证勾中的分区一定是整组；把分区名说进正文，按下去之前
                        // 用户知道刷回的是哪几个（boot_a / init_boot_a / vendor_boot_a）。
                        // 这份快照同时也是执行的输入：KMI 检查/弹窗期间用户再改勾选，改的只是
                        // 屏幕状态，不会让"弹窗说的"和"实际恢复的"变成两份东西。
                        val chosen = oneCopyPerContent(selection)
                        val moduleCount = chosen.count { it.kind == BackupKind.MODULE }
                        val separator = context.getString(R.string.backup_list_separator)
                        val partitions = chosen
                            .filter { it.kind == BackupKind.PARTITION }
                            .mapNotNull { partitionFromName(it.fileName) }
                            // 名单必须数得齐：mapNotNull 漏掉的行进不了正文，那就退回通用文案——
                            // 宁可少说细节，也不能把「将刷回 2 个分区」说成实际刷 3 个。
                            .takeIf { it.size == chosen.count { row -> row.kind == BackupKind.PARTITION } }
                        // 先比 KMI：快照记了拍它时的 uname，和当前的属于不同 KMI 时（系统升级过）
                        // 刷回去大概率开不了机——这句话必须赶在普通确认之前说。检查期间恢复按钮
                        // 已被 busy 禁掉（见 partitionKmiCheck），不会在等待中再点出第二个确认。
                        val kmi = viewModel.partitionKmiCheck(selection)
                        if (kmi != null) {
                            val kmiConfirmed = confirmDialog.awaitConfirm(
                                title = context.getString(R.string.backup_kmi_mismatch_title),
                                content = context.getString(
                                    R.string.backup_kmi_mismatch_body,
                                    kmi.snapshotRelease,
                                    kmi.currentRelease,
                                ),
                            ) == ConfirmResult.Confirmed
                            // KMI 警告是多出来的一道提醒，不是替代：确认后仍走普通确认，
                            // 分区名单/模块说明在普通框里说，两层各管各的信息。
                            if (!kmiConfirmed) return@launch
                        }
                        val count = { n: Int ->
                            context.resources.getQuantityString(R.plurals.backup_partition_count, n, n)
                        }
                        val body = when {
                            // 理论上到不了（分区行必有可认的文件名），认不出时退回普通文案兜底。
                            partitions.isNullOrEmpty() && moduleCount > 0 -> context.getString(
                                R.string.backup_partition_restore_confirm_body_mixed,
                                moduleCount,
                            )
                            partitions.isNullOrEmpty() -> partitionConfirmBody
                            moduleCount > 0 -> context.getString(
                                R.string.backup_partition_restore_confirm_body_mixed_partitions,
                                count(partitions.size),
                                partitions.joinToString(separator),
                                moduleCount,
                            )
                            else -> context.getString(
                                R.string.backup_partition_restore_confirm_body_partitions,
                                count(partitions.size),
                                partitions.joinToString(separator),
                            )
                        }
                        val confirmed = confirmDialog.awaitConfirm(
                            title = partitionConfirmTitle,
                            content = body,
                        ) == ConfirmResult.Confirmed
                        if (confirmed) {
                            viewModel.restoreSelected(chosen, deduped = chosen.size < selection.size)
                        }
                    }

                    // 一次恢复好几条：详情页默认全选，这一下会把整组模块按备份里的版本重装一遍
                    // ——备份可能比现在旧，而且恢复没有撤销。多选里带仓库源时正文已说明会整批替换。
                    RestoreConfirm.MANY -> confirmScope.launch {
                        // 弹窗数字和执行用同一份快照：等待确认的间隙改勾选，弹窗说的仍是确认时那份。
                        val chosen = oneCopyPerContent(selection)
                        val confirmed = confirmDialog.awaitConfirm(
                            title = context.getString(
                                R.string.backup_restore_many_confirm_title,
                                chosen.size,
                            ),
                            content = restoreManyConfirmBody,
                        ) == ConfirmResult.Confirmed
                        if (confirmed) {
                            viewModel.restoreSelected(chosen, deduped = chosen.size < selection.size)
                        }
                    }

                    // 单条仓库源恢复同样是整批替换：只恢复一条不拦的豁免理由（"只动那一个模块"）
                    // 对它不成立，所以单独问一次。
                    RestoreConfirm.REPO_CONFIG -> confirmScope.launch {
                        val chosen = oneCopyPerContent(selection)
                        val confirmed = confirmDialog.awaitConfirm(
                            title = repoRestoreConfirmTitle,
                            content = repoRestoreConfirmBody,
                        ) == ConfirmResult.Confirmed
                        if (confirmed) {
                            viewModel.restoreSelected(chosen, deduped = chosen.size < selection.size)
                        }
                    }

                    RestoreConfirm.NONE -> {
                        val chosen = oneCopyPerContent(selection)
                        viewModel.restoreSelected(chosen, deduped = chosen.size < selection.size)
                    }
                }
            }
        },
        onShareSelected = viewModel::shareSelected,
        onShareConsumed = viewModel::consumeShare,
        onDismissBackupRun = viewModel::dismissBackupRun,
        onCancelBackupRun = viewModel::cancelBackupRun,
        onCancelRestore = viewModel::cancelRestore,
        onDismissRestoreRun = viewModel::dismissRestoreRun,
        onDeleteChecked = {
            // 删的是文件，删完找不回来；而且这一组可能本机和云端都有，所以要说清动的是哪些地方。
            // 说的是**勾中的那几条**：只勾了本地就只删本地，正文里的"从本地删除这 1 项"就是
            // 接下来真正会发生的事——以前这里说的是整组，用户勾了一半也会看到"这 2 项"。
            val checked = state.openGroupSelection
            if (checked.isNotEmpty()) {
                confirmScope.launch {
                    val separator = context.getString(R.string.backup_list_separator)
                    val originLabel = checked.map { it.origin }.distinct().sorted()
                        .joinToString(separator) { context.getString(BackupLabels.origin(it)) }
                    val confirmed = confirmDialog.awaitConfirm(
                        title = deleteConfirmTitle,
                        content = context.getString(
                            R.string.backup_delete_confirm_body,
                            originLabel,
                            checked.size,
                        ),
                    ) == ConfirmResult.Confirmed
                    if (confirmed) viewModel.deleteChecked()
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
        onSelectAutoBackupOrigins = viewModel::setAutoBackupOrigins,
        onSetModuleRetention = viewModel::setModuleRetention,
        onSetPartitionRetention = viewModel::setPartitionRetention,
        onSetPartitionCompressed = viewModel::setPartitionCompressed,
        onToggleRetention = viewModel::toggleRetention,
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
    BackHandler(enabled = openGroup != null && state.restoreRun?.done != false) { viewModel.closeGroup() }

    // 多选是这一页里的另一层：返回键先退出多选，而不是把整页关掉——手上还勾着几份东西时
    // 一下退出整页，那几步勾选就白做了。
    BackHandler(enabled = state.selecting) { viewModel.clearSelection() }

    // 模块安装/boot 刷写不可安全中断的阶段只允许显式取消并等待当前源返回；恢复期间暂时拦截
    // 页面返回，避免 root 操作在详情消失后静默继续。
    BackHandler(enabled = state.restoreRun?.done == false) { }

    // 进/出详情走一次横向推入推出：硬切会让人以为刚才那下点空了。返回时列表滑回来的方向
    // 反过来，和系统返回手势的方向一致。
    //
    // contentKey 只看"是不是详情页"：刷新列表（恢复/删除之后）会让分组对象换一份新的，
    // 按对象比较会把它当成一次页面切换，白白重放一遍动画。
    //
    // 这一层自己铺上页面底色：进出详情是两边同时淡入淡出，中途整屏都是半透明的，而这一页
    // 本身压在设置那一级导航里——底下不铺东西的话，淡到一半透出来的是设置页，看着像切详情
    // 时底下还露着另一页。铺上和两页 Scaffold 一样的底色，淡入淡出就只在这块底色上发生。
    AnimatedContent(
        targetState = openGroup,
        modifier = Modifier.background(
            when (uiMode) {
                UiMode.Miuix -> MiuixTheme.colorScheme.surface
                UiMode.Material -> MaterialTheme.colorScheme.surfaceContainer
            }
        ),
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

/** 展开/收起动画的时长：太长显得迟钝，太短又成了硬切。两个主题的展开块都用同一个值。 */
internal const val EXPAND_MS = 220

/**
 * 分组卡片/详情页那一行说明：内容 · 项数。
 *
 * 写到哪几侧不在这里重复：卡片上是来源标签，详情页是下面的"备份存在哪"那几行。
 * 两个主题共用这一处措辞，改的时候不会只改一边。
 */
@Composable
fun groupSummary(group: BackupGroup): String {
    // 一次备份可能有好几类内容：用分隔符拼出来，而不是写成"3 类内容"——用户要看的就是备了啥。
    // map 是 inline 的，所以这里能在 lambda 里调 stringResource。
    val separator = stringResource(R.string.backup_list_separator)
    val kindLabel = BackupLabels.groupKinds(group).map { stringResource(it) }.joinToString(separator)
    return BackupLabels.groupSummary(
        kindLabel = kindLabel,
        countText = pluralStringResource(R.plurals.backup_group_items, group.rows.size, group.rows.size),
    )
}

/**
 * 位置下拉框里那几档的顺序：0 = 本地，1 = 云端，2 = 本地+云端。
 *
 * 「备份位置」和「自动备份位置」共用这一处映射：同一份下标含义被两个下拉框读，各写一份迟早
 * 会出现"Material 指着第 2 档、Miuix 指着第 0 档"。云端没配好时只有第 0 档，下标一律回落到 0。
 */
internal fun originOptionIndex(origins: Set<BackupOrigin>, cloudReady: Boolean): Int = when {
    !cloudReady -> 0
    BackupOrigin.LOCAL in origins && BackupOrigin.CLOUD in origins -> 2
    BackupOrigin.CLOUD in origins -> 1
    else -> 0
}

/** 第 [index] 档对应的写入位置；云端没配好时无论点了哪一档都只能是本地。 */
internal fun originsForOption(cloudReady: Boolean, index: Int): Set<BackupOrigin> = when {
    !cloudReady || index == 0 -> setOf(BackupOrigin.LOCAL)
    index == 1 -> setOf(BackupOrigin.CLOUD)
    else -> setOf(BackupOrigin.LOCAL, BackupOrigin.CLOUD)
}

/**
 * 「备份存在哪」那几行：这一组写到哪几侧就写几行（本机一个目录、云端一个地址）。
 * 地址还没填的云端不写——写一行空地址等于什么都没说。
 */
@Composable
fun groupLocationLines(group: BackupGroup, cloudSavedUrl: String): List<String> =
    group.origins.mapNotNull { origin ->
        BackupLocation.full(origin, cloudSavedUrl).takeIf { it.isNotBlank() }?.let { location ->
            stringResource(
                if (origin == BackupOrigin.CLOUD) {
                    R.string.backup_location_cloud
                } else {
                    R.string.backup_location_local
                },
                location,
            )
        }
    }

/**
 * 详情页头部那几行小字：摘要一行，每个来源各起一行。
 *
 * 地址接在摘要后面会连成"… · 本机备份在 A"一整句，而用户是按行找"这一份在哪、那一份在哪"的，
 * 所以摘要自己也占一行、每个来源再各占一行——三行各自说一件事，扫一眼就能对上号。
 */
@Composable
fun groupHeaderText(group: BackupGroup, cloudSavedUrl: String): String {
    val summary = groupSummary(group)
    val locations = groupLocationLines(group, cloudSavedUrl)
    if (locations.isEmpty()) return summary
    return (listOf(summary) + locations).joinToString("\n")
}

/**
 * 保留额度收起时的那一行：三个额度现在各是多少。
 *
 * 两个主题共用：这是措辞而不是布局，摆两份迟早只改一处。
 *
 * `stringResource` 只能在 @Composable 的上下文里调，所以数量在循环外先取好——
 * `joinToString` 的 transform 是个普通 lambda，在它里面取资源编不过。
 */
@Composable
fun retentionSummary(state: BackupUiState): String {
    val counts = listOf(state.moduleRetention, state.partitionRetention)
        .map { value -> stringResource(R.string.backup_retention_count, value) }
    val labels = listOf(
        stringResource(R.string.backup_retention_module),
        // 与上面那项对齐：模块用的是复数形式的类别名，分区这边也用 backup_retention_partition
        // （"Partition snapshots"），而不是单数形式的 backup_kind_partition。
        stringResource(R.string.backup_retention_partition),
    )
    return labels.zip(counts) { label, count -> "$label $count" }
        .joinToString(BackupListFormatter.SEPARATOR)
}

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
