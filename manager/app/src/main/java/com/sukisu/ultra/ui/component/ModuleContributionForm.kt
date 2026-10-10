package com.sukisu.ultra.ui.component

import com.sukisu.ultra.ui.util.CheckStatus
import com.sukisu.ultra.ui.util.ModulePropInfo
import com.sukisu.ultra.ui.util.SubmitResult

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
     * （契约：它会进人工审核队列，不该拿弹窗打断一次普通升级），后者是"不知道"，
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
}

/**
 * 投稿对话框的提交状态。
 *
 * [Idle] → [Submitting] → [Success] / [Failed]，[Failed] 可以再回到 [Submitting]（重试）：
 * 网络一抖就把提交入口永久关死，只能让用户重新打包 zip、重填必填项，
 * 而失败的这一次服务端很可能根本没收到。
 *
 * [Submitting] 期间禁止二次提交（连点会把同一个 zip 传两遍，审核队列里出现两条重复条目）；
 * [Success] 之后同样禁止——那一次已经进队列了，再点是重复条目。
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
 * 投稿表单的一份快照，两套界面共用。
 *
 * [meta] 是 [com.sukisu.ultra.ui.util.ModuleProp.parseModuleProp] 从 zip 里读出来的，
 * 用来预填展示信息；为 null 表示"读不出模块信息"，此时仍允许填写但提交必然失败
 * （[com.sukisu.ultra.ui.util.ModuleContribution.submitModule] 那层会挡住）。
 */
data class ContributionForm(
    val meta: ModulePropInfo? = null,
    val source: String = "",
    val uploader: String = "",
    val consentChecked: Boolean = false,
    val submitState: ContributionSubmitState = ContributionSubmitState.Idle,
) {

    /** 出处链接与投稿者署名两项必填（前后空白不算填了）。 */
    val requiredFilled: Boolean
        get() = source.trim().isNotEmpty() && uploader.trim().isNotEmpty()

    /**
     * 能不能提交。
     *
     * 两道门缺一不可：必填项填了、确认勾选了。
     *
     * 另外要求当前**没在提交中**（连点会把同一个 zip 传两遍，审核队列里出现两条重复条目），
     * 但**只在** [ContributionSubmitState.Submitting] 时挡：失败之后必须还能再点一次。
     * 网络抖一下就永久禁用提交按钮，等于让用户关掉对话框从头走一遍（重新打包 zip、重新填必填项）。
     *
     * 成功（[ContributionSubmitState.Success]）之后仍不可再提交：
     * 这一次已经进了审核队列，再点一次就是一条重复条目——要投另一个版本请重新打开对话框。
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

/** 把 [ModulePropInfo] 变成预填好的表单。 */
fun contributionFormOf(meta: ModulePropInfo?): ContributionForm = ContributionForm(meta = meta)
