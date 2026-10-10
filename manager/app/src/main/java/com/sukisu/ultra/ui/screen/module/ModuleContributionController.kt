package com.sukisu.ultra.ui.screen.module

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.sukisu.ultra.data.model.Module
import com.sukisu.ultra.data.repository.SettingsRepository
import com.sukisu.ultra.data.repository.SettingsRepositoryImpl
import com.sukisu.ultra.ui.component.ModuleContributionDialogState
import com.sukisu.ultra.ui.component.ModuleContributionRules
import com.sukisu.ultra.ui.component.rememberModuleContributionDialog
import com.sukisu.ultra.ui.util.CheckStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 模块页这一侧的投稿控制器：持有"每个模块的收录状态"、"待弹的分享提示"与投稿对话框。
 *
 * 三件事分开是因为触发时机完全不同：
 * - [ensureChecked] 是**懒查**且带缓存：只在界面真的要渲染这个模块的按钮时才问服务端，
 *   列表划过去又划回来不会重复问。列表渲染本身就是唯一能自然限流的时机。
 * - [checkAfterLocalZipInstall] 是**本地 zip 安装成功后**的主动查，查完按规则决定是否提示。
 *   这是唯一会主动打扰用户的入口，所以只有 [CheckStatus.NEW] 会留下一句话。
 * - [openFor] 是用户自己点投稿按钮，不受"要不要提示"的规则约束。
 *
 * 整个对象受 [settings] 的投稿开关约束：开关关掉时既不查询也不显示按钮（[enabled] 为假）。
 */
class ModuleContributionController internal constructor(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    dialog: ModuleContributionDialogState,
) {

    private val statuses = mutableStateOf<Map<String, CheckStatus>>(emptyMap())
    private val inFlight = mutableSetOf<String>()

    /** 开关状态。为 false 时所有入口一起消失。 */
    var enabled by mutableStateOf(readEnabled(settings))
        private set

    /** 投稿对话框。 */
    val dialog: ModuleContributionDialogState = dialog

    /**
     * 待弹的分享提示。由 [checkAfterLocalZipInstall] 置上，
     * 由界面（Material / Miuix 各自的 snackbar host）消费后调 [consumeSharePrompt] 清掉。
     *
     * 之所以不直接在这里弹：snackbar host 在两套界面各自的 Scaffold 里，
     * 而"分享"这个动作的落地（替换掉默认的重启动作）也只有界面那边知道怎么接。
     */
    var sharePrompt by mutableStateOf<Module?>(null)
        private set

    /** 刷新开关：设置页改了之后回到模块页要能立刻生效。 */
    fun refreshEnabled() {
        enabled = readEnabled(settings)
    }

    /** 列表渲染时用：这个模块该不该显示投稿按钮。 */
    fun showButton(module: Module): Boolean {
        if (!enabled) return false
        return ModuleContributionRules.showContributeButton(statusOf(module))
    }

    /** 列表渲染时用：按钮旁要不要挂「无法确认是否已收录」。 */
    fun showUnknownHint(module: Module): Boolean {
        if (!enabled) return false
        return ModuleContributionRules.showUnknownHint(statusOf(module))
    }

    /**
     * 查一次这个模块的收录状态（带缓存与去重）。
     *
     * 由 [rememberChecked] 在 [androidx.compose.runtime.LaunchedEffect] 里调用，
     * 不在组合里直接调——组合里直接发起协程会让同一项在一次重组里被查多次，
     * 也不受 composition 生命周期约束。
     *
     * 只在 [enabled] 为真时发起；结果只落在内存里，状态刷新就重来——
     * 缓存持久化会让"这个模块已经被收录了"这件事在用户重启 App 之前一直生效，
     * 而索引是随时在变的。
     */
    internal fun ensureChecked(module: Module) {
        if (!enabled) return
        val key = statusKey(module)
        if (statuses.value.containsKey(key) || !inFlight.add(key)) return
        scope.launch {
            val status = ModuleContributionEntry.check(module)
            if (status != null) {
                statuses.value = statuses.value + (key to status)
            }
            inFlight.remove(key)
        }
    }

    /**
     * 本地 zip 安装**成功之后**查一次，按规则决定是否留一条分享提示。
     *
     * [installed] 为 null 表示"装上之后列表里没有它"，也就是安装失败了——
     * 没装上的模块不该问用户要不要分享，直接静默。
     */
    fun checkAfterLocalZipInstall(installed: Module?) {
        if (!enabled) return
        val module = installed ?: return
        scope.launch {
            val status = ModuleContributionEntry.check(module) ?: return@launch
            // 只有 NEW 留提示；VERSION_UPDATE / UNKNOWN / RECORDED 一律静默。
            if (ModuleContributionRules.showShareSnackbar(status)) {
                sharePrompt = module
            }
        }
    }

    /** 界面消费完分享提示后调用。 */
    fun consumeSharePrompt() {
        sharePrompt = null
    }

    /** 打开投稿对话框：先打包，再读 module.prop 预填。 */
    fun openFor(module: Module) {
        if (!enabled) return
        scope.launch {
            val zip = ModuleContributionEntry.stageZip(module) ?: return@launch
            val meta = ModuleContributionEntry.readProp(zip)
            dialog.open(zip, meta)
        }
    }

    private fun statusOf(module: Module): CheckStatus? = statuses.value[statusKey(module)]

    private fun statusKey(module: Module): String = "${module.id}:${module.versionCode}"

    private companion object {
        /** 读开关本身也可能炸（SharedPreferences 拿不到时）；炸了按"关"处理，宁可不打扰。 */
        fun readEnabled(settings: SettingsRepository): Boolean =
            runCatching { settings.isModuleContributionEnabled() }.getOrDefault(true)
    }
}

/**
 * @param settings 开关的读取源。**必须**由调用方用 `remember { SettingsRepositoryImpl() }` 持有
 *   （这里的默认实参就是这么写的）：[SettingsRepositoryImpl] 没有 `equals`，
 *   若在调用点裸写 `SettingsRepositoryImpl()`，每次重组都是一个新实例，
 *   [remember] 的键每次都变 → 控制器每次重组重建 → statuses / inFlight / sharePrompt 全丢，
 *   「已收录就不显示投稿按钮」永远不会生效，/check 也会对每个模块反复重发。
 *   写法与 ui/component/bottombar/NavigationRailMaterial.kt 里的 settingsRepo 一致。
 */
@Composable
fun rememberModuleContributionController(
    scope: CoroutineScope,
    submittingText: () -> String,
    settings: SettingsRepository = remember { SettingsRepositoryImpl() },
): ModuleContributionController {
    val dialog = rememberModuleContributionDialog(
        scope = scope,
        submittingText = submittingText,
        submit = { zip, meta, source, uploader ->
            ModuleContributionEntry.submit(zip, meta, source, uploader)
        },
    )
    return remember(scope, settings) {
        ModuleContributionController(scope, settings, dialog)
    }
}

/**
 * 在组合里为由 [module] 渲染的那一格查一次收录状态。
 *
 * 必须是 [LaunchedEffect] 而不是直接调用：组合里直接发起协程不受 composition
 * 生命周期约束，同一格在一次重组里可能被查多次，划出屏幕也不会取消。
 * key 里带上 [ModuleContributionController.enabled]，开关翻动时会自动重查。
 */
@Composable
fun ModuleContributionController.rememberChecked(module: Module) {
    LaunchedEffect(module.id, module.versionCode, enabled) {
        ensureChecked(module)
    }
}

/**
 * 把待弹的分享提示弹成一条 snackbar，点「分享」就打开投稿对话框。
 *
 * Material 与 Miuix 各调一次（各自的 snackbar host 类型不同），
 * 但"该不该弹"的判定只在 [ModuleContributionController] 里做一次。
 *
 * [onOpen] 为空表示调用方自己处理（例如先关掉当前的重启提示再打开对话框）。
 */
@Composable
fun ObserveSharePrompt(
    contribution: ModuleContributionController,
    snackbar: suspend (message: String, actionLabel: String) -> Boolean,
    message: String,
    actionLabel: String,
) {
    val scope = rememberCoroutineScope()
    val prompt = contribution.sharePrompt
    LaunchedEffect(prompt) {
        val module = prompt ?: return@LaunchedEffect
        // 先消费再弹：同一模块不会被重复提示（列表刷新会重新触发 composition）。
        contribution.consumeSharePrompt()
        scope.launch {
            val performed = snackbar(message, actionLabel)
            if (performed) contribution.openFor(module)
        }
    }
}
