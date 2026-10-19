package com.sukisu.ultra.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sukisu.ultra.ui.LocalUiMode
import com.sukisu.ultra.ui.UiMode
import com.sukisu.ultra.ui.component.material.ModuleContributionDialogMaterial
import com.sukisu.ultra.ui.component.miuix.ModuleContributionDialogMiuix
import com.sukisu.ultra.ui.util.CoolapkUidLookup
import com.sukisu.ultra.ui.util.ModuleContribution
import com.sukisu.ultra.ui.util.ModuleProp
import com.sukisu.ultra.ui.util.ModulePropInfo
import com.sukisu.ultra.ui.util.SubmitResult
import com.sukisu.ultra.ui.util.makeNonce
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 投稿对话框的宿主：一套状态 + 两套界面。
 *
 * Material 与 Miuix 各画自己的控件，但表单状态、确认勾选、提交流程只此一份——
 * 两边各写一遍的话，"未勾选能不能提交"迟早会在其中一套里跑偏，而那条正是用户知情的关键。
 *
 * 用法：调用方持有 [ModuleContributionDialogState]，备好 zip 后 [open]，
 * [ModuleContributionHost] 负责按当前 UI 模式画出来。
 */
class ModuleContributionDialogState internal constructor(
    private val scope: CoroutineScope,
    private val submit: suspend (File, ModulePropInfo?, String, String, String, String, String) -> SubmitResult,
    private val lookupUid: suspend (String) -> CoolapkUidLookup,
    private val submittingText: () -> String,
    /**
     * 算整包哈希用的调度器。生产上就是 [Dispatchers.IO]；测试注入手动挡，见 [open]。
     */
    private val hashDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** 当前投稿的文件。null 表示对话框不显示。 */
    var file: File? by mutableStateOf(null)
        private set

    /** 当前表单快照。 */
    var form: ContributionForm by mutableStateOf(ContributionForm())
        private set

    private var currentMeta: ModulePropInfo? = null

    /** 正在跑的那次 UID 核实。新输入进来时取消它，避免旧结果覆盖新输入的状态。 */
    private var lookupJob: Job? = null

    /**
     * 打开投稿对话框。
     *
     * [meta] 由 [com.sukisu.ultra.ui.util.ModuleProp.parseModuleProp] 从 [zip] 读出来，
     * 用于预填展示信息；读不出来（null）也可以开，但提交会在网络层被挡住并给出原因。
     *
     * 整包 sha256 在这之后**异步**补进表单：口令前缀绑它（设计文档 §14.1），而 zip 最大 8MB，
     * 在 [scope]（主线程）上同步读一遍就是掉帧。算出来之前口令行不显示
     * （[ModuleContributionRules.showProofRow]），所以不存在"显示了一串随后会变"的窗口。
     * 提交时网络层算的是同一个文件的同一个哈希（[com.sukisu.ultra.ui.util.ModuleContribution.submitModule]
     * 里的 `ModuleProp.zipSha256(zip)`），并把这一份展示值传下去**对比**：
     * 对话框开着期间文件被改动过的话，屏幕上那串口令已经作废，本地直接停下并说明原因，
     * 而不是上传一个注定被服务端拒掉的包、再让用户对着一句他复制过的口令收到 403。
     */
    fun open(zip: File, meta: ModulePropInfo?) {
        currentMeta = meta
        // 每次打开都重置：上一次的成功/失败提示不该残留到下一次投稿，
        // 勾选更是必须重来——它是"这一次上传"的知情确认，不是一次性的永久授权。
        // UID 也一起清掉：那是上一个模块的投稿人身份，留着会让下一条投稿署上别人的名。
        lookupJob?.cancel()
        form = ContributionForm(meta = meta)
        file = zip
        val openedZip = zip
        scope.launch {
            // 读盘在 IO，写状态回到 scope（主线程）：snapshot 状态从后台线程写是 race 的来源。
            val zipSha = withContext(hashDispatcher) { ModuleProp.zipSha256(openedZip) }
            // 算完时对话框可能已经关掉、或换成了另一个包：只把哈希写回它属于的那一次。
            if (zipSha == null || file !== openedZip) return@launch
            form = form.copy(zipSha256 = zipSha)
        }
    }

    fun close() {
        if (form.isSubmitting) return
        lookupJob?.cancel()
        file = null
    }

    fun updateSource(value: String) {
        form = form.copy(source = value)
    }

    /**
     * 改了 UID 输入框。
     *
     * 形状不合法（不是 4~12 位数字）时**不发请求**，状态回到 [CoolapkUidState.Empty]：
     * 边打字边打服务端只会白耗配额，而且每个中间态都必然查不到，界面会一路闪"查无此人"。
     *
     * 形状合法则等 [UID_DEBOUNCE_MS] 再查一次（用户还在继续打字就取消上一次）。
     * 这与网页端 `app.js` 的 500ms 防抖是同一条规则。
     */
    fun updateUid(value: String) {
        val cleaned = value.trim()
        lookupJob?.cancel()
        if (!ModuleContribution.isValidUid(cleaned)) {
            form = form.copy(uid = cleaned, uidState = CoolapkUidState.Empty)
            return
        }
        form = form.copy(uid = cleaned, uidState = CoolapkUidState.Checking)
        lookupJob = scope.launch {
            delay(UID_DEBOUNCE_MS)
            runLookup(cleaned)
        }
    }

    /**
     * 「暂时无法核实」时用户点重试：立刻再查一次，不再等防抖。
     *
     * 没有这个入口，一次风控/网络抖动就只能靠用户重新敲一遍 UID —— 而那是同一个请求。
     */
    fun retryUidLookup() {
        val uid = form.uid.trim()
        if (!ModuleContribution.isValidUid(uid) || form.isSubmitting) return
        lookupJob?.cancel()
        form = form.copy(uidState = CoolapkUidState.Checking)
        lookupJob = scope.launch { runLookup(uid) }
    }

    private suspend fun runLookup(uid: String) {
        val result = runCatching { lookupUid(uid) }
            .getOrElse { CoolapkUidLookup.Unavailable }
        // 结果回来时用户可能已经改了输入：只认"当前输入仍然是这次查的那个 UID"的结果，
        // 否则会把 A 的核实结论挂到 B 的输入框上（署名与实际 UID 对不上，正是要防的事）。
        if (form.uid.trim() != uid) return
        form = form.copy(uidState = result.toState())
    }

    fun updateConsent(checked: Boolean) {
        form = form.copy(consentChecked = checked)
    }

    /**
     * 提交。
     *
     * [ContributionForm.canSubmit] 为假时直接返回：按钮 disabled 挡得住点击，
     * 但挡不住键盘回车之类路径，而"没勾确认就把 zip 传出去"是这套功能唯一会造成
     * 实际伤害的失败模式。
     */
    fun submit() {
        val zip = file ?: return
        val snapshot = form
        if (!snapshot.canSubmit) return
        scope.launch {
            form = snapshot.copy(
                submitState = ContributionSubmitState.Submitting(submittingText()),
            )
            val result = runCatching {
                // 署名用核实出来的昵称，不用输入框里的原文：那是酷安反查的结果，
                // 也是服务端唯一能证明"昵称属于这个 UID"的来源。
                // nonce 一并传下去：与「模块 id + 整包 sha256」拼成所有权口令，服务端拿它比对酷安签名。
                // form.zipSha256（屏幕上那串口令绑定的哈希）也传下去：网络层对磁盘文件实算后与它对比，
                // 文件在打开对话框之后变过就本地拒绝并说清原因（见 submitModule 的说明）。
                submit(
                    zip, currentMeta, snapshot.source, snapshot.uid, snapshot.nickname,
                    snapshot.nonce, snapshot.zipSha256,
                )
            }.getOrElse { error ->
                SubmitResult(false, null, error.message?.takeIf { it.isNotBlank() } ?: "投稿失败")
            }
            form = form.copy(
                submitState = if (result.ok) {
                    ContributionSubmitState.Success(result.message, result.prUrl)
                } else {
                    ContributionSubmitState.Failed(result.message)
                },
                // 只有服务端明确说「这枚口令已经用过」才重掷 nonce——那一串真的作废了。
                // 判定码优先（body.code == "proof_used"）、文案兜底，规则与网页端 app.js 同一条；
                // 其余失败（超时、签名里没这串口令、KV 读不出记录）必须保持同一串：
                // 用户是照**当前显示的口令**去改签名的，随手换一串就等于让他的签名永远追不上口令，
                // 而换出来的新串他还没填进公开字段，这一次投稿直接死循环。判定见
                // [ModuleContributionRules.proofWasAlreadyUsed]。
                nonce = if (!result.ok && ModuleContributionRules.proofWasAlreadyUsed(result.message, result.code)) {
                    makeNonce()
                } else {
                    snapshot.nonce
                },
            )
        }
    }

    /** 保留给调用方：对话框此时是否开着。 */
    val isOpen: Boolean get() = file != null

    private companion object {
        /** 输入停止多久后去核实。与网页端 `app.js` 的 500ms 一致。 */
        const val UID_DEBOUNCE_MS = 500L
    }
}

@Composable
fun rememberModuleContributionDialog(
    scope: CoroutineScope,
    submittingText: () -> String,
    submit: suspend (File, ModulePropInfo?, String, String, String, String, String) -> SubmitResult,
    lookupUid: suspend (String) -> CoolapkUidLookup,
): ModuleContributionDialogState {
    return remember(scope) {
        ModuleContributionDialogState(scope, submit, lookupUid, submittingText)
    }
}

/** 按当前 UI 模式画出对应的投稿对话框。 */
@Composable
fun ModuleContributionHost(state: ModuleContributionDialogState) {
    val show = state.file != null
    when (LocalUiMode.current) {
        UiMode.Miuix -> ModuleContributionDialogMiuix(
            show = show,
            form = state.form,
            onSourceChange = state::updateSource,
            onUidChange = state::updateUid,
            onRetryLookup = state::retryUidLookup,
            onConsentChange = state::updateConsent,
            onSubmit = state::submit,
            onDismissRequest = state::close,
        )

        UiMode.Material -> ModuleContributionDialogMaterial(
            show = show,
            form = state.form,
            onSourceChange = state::updateSource,
            onUidChange = state::updateUid,
            onRetryLookup = state::retryUidLookup,
            onConsentChange = state::updateConsent,
            onSubmit = state::submit,
            onDismissRequest = state::close,
        )
    }
}
