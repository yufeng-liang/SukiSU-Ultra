package com.sukisu.ultra.ui.component

import com.sukisu.ultra.ui.util.CheckStatus
import com.sukisu.ultra.ui.util.CoolapkUidLookup
import com.sukisu.ultra.ui.util.ModulePropInfo
import com.sukisu.ultra.ui.util.SubmitResult
import com.sukisu.ultra.ui.util.makeNonce
import com.sukisu.ultra.ui.util.proofPrefixFor

/**
 * 投稿对话框背后那点纯逻辑。
 *
 * 抽出来是因为它同时被 Material 与 Miuix 两套界面用，而且**可以被 JVM 单测直接跑**：
 * 表里每一条都是产品定案的规则（什么状态显示什么、没勾确认能不能提交），
 * 任何一条写错，用户那边不是"提示错了"就是"没看通知就把文件传出去了"。
 *
 * 另外 UI 里放不下这么长的推导：对话框要同时握着"解析出来的模块信息、两个必填项、
 * 确认勾选、提交进度、提交结果"五份状态，散在 Composable 里必然出现两套实现跑偏。
 */
object ModuleContributionRules {

    /**
     * 列表里要不要显示投稿按钮。
     *
     * [CheckStatus.RECORDED] 已经是索引里收录过的那一版，再投稿只会制造重复条目；
     * 其余三种（[CheckStatus.NEW]、[CheckStatus.VERSION_UPDATE]、[CheckStatus.UNKNOWN]）
     * 都显示——[CheckStatus.UNKNOWN] 只是"没问出来"，用户主动想投稿不该被拦。
     */
    fun showContributeButton(status: CheckStatus?): Boolean = status != CheckStatus.RECORDED

    /**
     * 只在 [CheckStatus.NEW] 时弹「分享这个模块吗？」。
     *
     * [CheckStatus.VERSION_UPDATE] 与 [CheckStatus.UNKNOWN] 都不弹：前者靠列表里的按钮
     * （发了新版本就走投稿流程，不该拿弹窗打断一次普通升级），后者是"不知道"，
     * 弹出来等于拿猜的结果打扰用户。
     */
    fun showShareSnackbar(status: CheckStatus): Boolean = status == CheckStatus.NEW

    /**
     * 投稿按钮旁要不要挂「无法确认是否已收录」。
     *
     * 与弹 snackbar 的条件是同一件事的两面：查不出来的时候不主动提示，
     * 但用户自己点开投稿按钮时要如实告诉他"这一版是不是已收录，我们没查出来"。
     */
    fun showUnknownHint(status: CheckStatus?): Boolean = status == CheckStatus.UNKNOWN

    /**
     * 口令行要不要显示。
     *
     * 口令绑的是「模块 id + 整包 sha256」，两个输入缺任何一个都拼不出**这次投稿真正要用的**那一串。
     * 整包哈希是在后台线程算完才落进表单的（zip 可达 8MB，主线程读一遍就是掉帧），
     * 所以中间会有一个短暂窗口它还不存在——那时宁可没有口令行，也不能显示一串
     * 等会儿一定会变的口令：用户复制走、填进酷安签名的就是一串废口令。
     *
     * 判据写在这里而不是两套界面各写一遍：两套渲染器迟早会在其中一套里跑偏。
     */
    fun showProofRow(id: String?, zipSha: String): Boolean =
        !id.isNullOrBlank() && zipSha.isNotBlank()

    /**
     * 服务端的失败是不是「这枚口令已经用过了」。
     *
     * 只有这一种失败值得重掷 nonce（见 [ModuleContributionDialogState.submit]）：那串口令确实作废了。
     *
     * **首选机器可读的失败码**：Worker 的 used-nonce 分支在 403 的 body 里回
     * `code: 'proof_used'`（contrib/worker/src/index.ts），与网页端 app.js 的
     * `proofWasSpent` 同一条规则 —— 文案匹配「改一个字就会悄悄把旋转逻辑关掉」，
     * 码不会。中文标记只作兜底：线上可能有旧版 Worker / 中间网关吃掉 body 时只剩文案。
     * 刻意只认这一种：
     * - 「签名里没有本次口令」不能换——用户要的是去把**当前这串**填进签名；
     * - 「口令记录读不出来」「超时」「网络不可用」是平台故障，换了等于把用户已经在
     *   签名里的那串作废，他改完签名再点重试就永远对不上。
     *
     * 与网页端 app.js 的行为同一条（设计文档 §14.3：只有真拿到"可能已提交成功"的信号才换一串）。
     * 两个判据都认不出一律当作"不换"：漏重掷只是让用户再点一次重试，错重掷会把用户填进签名的那串作废。
     */
    fun proofWasAlreadyUsed(message: String, code: String? = null): Boolean =
        code == PROOF_USED_CODE || PROOF_USED_MARKERS.any(message::contains)

    /** Worker used-nonce 分支的机器可读码（index.ts 里 `code: 'proof_used'` 的字面量）。 */
    private const val PROOF_USED_CODE = "proof_used"

    /** 旧后端（没有 code）的文案兜底；与 Worker 那句 403 error 的开头对齐。 */
    private val PROOF_USED_MARKERS = listOf("口令已经用过", "口令已用过")
}

/**
 * 投稿对话框的提交状态。
 *
 * [Idle] → [Submitting] → [Success] / [Failed]，[Failed] 可以再回到 [Submitting]（重试）：
 * 网络一抖就把提交入口永久关死，只能让用户重新打包 zip、重填必填项，
 * 而失败的这一次服务端很可能根本没收到。
 *
 * [Submitting] 期间禁止二次提交（连点会把同一个 zip 传两遍，仓库里出现两条重复条目）；
 * [Success] 之后同样禁止——那一次已经提交出去了，再点就是重复条目。
 */
sealed interface ContributionSubmitState {
    object Idle : ContributionSubmitState

    /** 上传中。zip 可能几十 MB，[message] 用 `module_contribution_submitting` 的文案。 */
    data class Submitting(val message: String) : ContributionSubmitState

    /** 成功。[prUrl] 可能为 null（服务端成功但没回 PR 链接），此时只显示 [message]。 */
    data class Success(val message: String, val prUrl: String?) : ContributionSubmitState

    /** 失败。[message] 来自 [SubmitResult.message]，一定非空且是人类可读的原因。 */
    data class Failed(val message: String) : ContributionSubmitState
}

/**
 * 酷安 UID 输入框的界面状态。
 *
 * 比网络层的 [CoolapkUidLookup] 多两个**只属于界面**的取值：[Empty]（还没填或形状不对）
 * 与 [Checking]（防抖结束、请求在路上）。两者都不是"核实结论"，所以不能混进网络层的类型里
 * ——否则 [ContributionForm.requiredFilled] 那种判据迟早会把"正在查"当成"查到了"。
 */
sealed interface CoolapkUidState {
    /** 还没填，或填了但不是 4~12 位数字。此时不发请求。 */
    object Empty : CoolapkUidState

    /** 正在核实（输入停止后的防抖已结束，请求在路上）。 */
    object Checking : CoolapkUidState

    /**
     * 核实通过：酷安返回了这个 UID 的昵称。**唯一允许提交的状态**。
     *
     * [avatar] 只是界面装饰（酷安头像地址，可能为空），不参与任何判据。
     */
    data class Verified(val nickname: String, val avatar: String = "") : CoolapkUidState

    /** 酷安明确说没有这个用户。 */
    object NotFound : CoolapkUidState

    /** 后端没有核实能力（核实开关被关掉）。 */
    object SelfReported : CoolapkUidState

    /** 这次没核实成（酷安不可达 / 风控 / 超时）。 */
    object Unavailable : CoolapkUidState
}

/** 网络层结果 → 界面状态。 */
fun CoolapkUidLookup.toState(): CoolapkUidState = when (this) {
    is CoolapkUidLookup.Verified -> CoolapkUidState.Verified(nickname, avatar)
    CoolapkUidLookup.NotFound -> CoolapkUidState.NotFound
    CoolapkUidLookup.SelfReported -> CoolapkUidState.SelfReported
    CoolapkUidLookup.Unavailable -> CoolapkUidState.Unavailable
}

/**
 * 投稿表单的一份快照，两套界面共用。
 *
 * [meta] 是 [com.sukisu.ultra.ui.util.ModuleProp.parseModuleProp] 从 zip 里读出来的，
 * 用来预填展示信息；为 null 表示"读不出模块信息"，此时仍允许填写但提交必然失败
 * （[com.sukisu.ultra.ui.util.ModuleContribution.submitModule] 那层会挡住）。
 *
 * 投稿人身份是 [uid] + [uidState] 两个字段，不再是自由填写的署名：昵称由酷安反查得到
 * （见 [CoolapkUidState.Verified]），用户能控制的只有 UID 那一串数字。
 *
 * [zipSha256] 是待投稿 zip 的整包 SHA-256（小写十六进制，与提交时作为 multipart `sha256`
 * 发出去的那一个同源同值），口令前缀绑它；空串表示"还没算出来"，此时口令行不显示
 * （见 [ModuleContributionRules.showProofRow]）。提交时这一份会交给网络层与磁盘实算值对比
 * （文件在对话框开着期间被改过就本地说清并停止上传，见
 * [com.sukisu.ultra.ui.util.ModuleContribution.submitModule]）。
 */
data class ContributionForm(
    val meta: ModulePropInfo? = null,
    val source: String = "",
    val uid: String = "",
    val uidState: CoolapkUidState = CoolapkUidState.Empty,
    val consentChecked: Boolean = false,
    val submitState: ContributionSubmitState = ContributionSubmitState.Idle,
    val nonce: String = makeNonce(),
    val zipSha256: String = "",
) {

    /** 核实出来的昵称；没核实通过时为空串（此时也不允许提交）。 */
    val nickname: String
        get() = (uidState as? CoolapkUidState.Verified)?.nickname.orEmpty()

    /**
     * 所有权口令文本 = `<sha256("<id>:<整包 sha256>") 前 8 位>-<nonce>`（设计文档 §14.1）。
     *
     * 只存随机部分（[nonce]），前缀每次由「当前模块 id + 当前整包哈希」现算——
     * 换 id 或换包都跟着变，与 [com.sukisu.ultra.ui.util.proofPrefixFor] 及 Worker 侧同值。
     *
     * [zipSha] 由调用方给（两套渲染器都传 [zipSha256]），而不是在这里读 [zipSha256]：
     * 口令的两个输入必须摆在同一个调用点上，界面才知道"这串到底绑的是哪个包"。
     */
    fun proofText(id: String, zipSha: String): String = proofPrefixFor(id, zipSha) + "-" + nonce

    /**
     * 出处填了、且酷安 UID 核实通过。
     *
     * "核实通过"必须是**酷安反查到的**，不是"填了 4~12 位数字"——后者只是形状，
     * 谁都能填一串不存在的数字，那和原来的自由署名没有区别。
     */
    val requiredFilled: Boolean
        get() = source.trim().isNotEmpty() && uidState is CoolapkUidState.Verified

    /** UID 形状已经合法、正在或已经查过（界面据此决定要不要显示状态行）。 */
    val uidLooksValid: Boolean
        get() = com.sukisu.ultra.ui.util.ModuleContribution.isValidUid(uid)

    /**
     * 能不能提交。
     *
     * 两道门缺一不可：必填项填了、确认勾选了。
     *
     * 另外要求当前**没在提交中**（连点会把同一个 zip 传两遍，仓库里出现两条重复条目），
     * 但**只在** [ContributionSubmitState.Submitting] 时挡：失败之后必须还能再点一次。
     * 网络抖一下就永久禁用提交按钮，等于让用户关掉对话框从头走一遍（重新打包 zip、重新填必填项）。
     *
     * 成功（[ContributionSubmitState.Success]）之后仍不可再提交：
     * 这一次已经提交出去了，再点一次就是一条重复条目——要投另一个版本请重新打开对话框。
     */
    val canSubmit: Boolean
        get() = requiredFilled && consentChecked && submitState !is ContributionSubmitState.Submitting &&
            submitState !is ContributionSubmitState.Success

    /** 提交中：界面显示进度、禁用一切输入。 */
    val isSubmitting: Boolean
        get() = submitState is ContributionSubmitState.Submitting

    /**
     * 上一次提交失败了：界面据此把"提交"按钮换成"重试"。
     *
     * 与 [canSubmit] 分开是因为两者回答的不是同一个问题：canSubmit 说"现在能不能点"，
     * 这条说"这次是重来而不是首次"。
     */
    val isFailed: Boolean
        get() = submitState is ContributionSubmitState.Failed

    /**
     * 该不该提示"请先勾选确认"（`module_contribution_consent_required`）。
     *
     * 只在"必填项已经填了、只差这一个勾"时提示：一打开对话框就挂一句红字是噪音，
     * 而填完了才提示，正好接在用户准备点提交的那一步。
     * 提交中或已提交都不再提示。
     */
    val consentMissing: Boolean
        get() = requiredFilled && !consentChecked && !isSubmitting &&
            submitState !is ContributionSubmitState.Success
}

/**
 * 把 [ModulePropInfo] 变成预填好的表单。
 *
 * 它看着像个无害的预填工具，其实构造的是**一次新的投稿尝试**：[ContributionForm.nonce] 的默认值
 * 就是 `makeNonce()`，所以每调用一次都换一个新 nonce，用户先前复制的那串口令跟着作废。
 * 因此只能在对话框打开（= 新的一次尝试）时用它；会话中途拿来做预填会悄悄旋转口令，
 * 而酷安签名里留下的还是旧的那串，服务端只会回「没在签名里找到口令」。
 */
fun contributionFormOf(meta: ModulePropInfo?, zipSha256: String = ""): ContributionForm =
    ContributionForm(meta = meta, zipSha256 = zipSha256)
