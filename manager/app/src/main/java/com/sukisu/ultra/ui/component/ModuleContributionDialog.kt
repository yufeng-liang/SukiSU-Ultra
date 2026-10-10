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
import com.sukisu.ultra.ui.util.ModulePropInfo
import com.sukisu.ultra.ui.util.SubmitResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    private val submit: suspend (File, ModulePropInfo?, String, String) -> SubmitResult,
    private val submittingText: () -> String,
) {

    /** 当前投稿的文件。null 表示对话框不显示。 */
    var file: File? by mutableStateOf(null)
        private set

    /** 当前表单快照。 */
    var form: ContributionForm by mutableStateOf(ContributionForm())
        private set

    private var currentMeta: ModulePropInfo? = null

    /**
     * 打开投稿对话框。
     *
     * [meta] 由 [com.sukisu.ultra.ui.util.ModuleProp.parseModuleProp] 从 [zip] 读出来，
     * 用于预填展示信息；读不出来（null）也可以开，但提交会在网络层被挡住并给出原因。
     */
    fun open(zip: File, meta: ModulePropInfo?) {
        currentMeta = meta
        // 每次打开都重置：上一次的成功/失败提示不该残留到下一次投稿，
        // 勾选更是必须重来——它是"这一次上传"的知情确认，不是一次性的永久授权。
        form = ContributionForm(meta = meta)
        file = zip
    }

    fun close() {
        if (form.isSubmitting) return
        file = null
    }

    fun updateSource(value: String) {
        form = form.copy(source = value)
    }

    fun updateUploader(value: String) {
        form = form.copy(uploader = value)
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
                submit(zip, currentMeta, snapshot.source, snapshot.uploader)
            }.getOrElse { error ->
                SubmitResult(false, null, error.message?.takeIf { it.isNotBlank() } ?: "投稿失败")
            }
            form = form.copy(
                submitState = if (result.ok) {
                    ContributionSubmitState.Success(result.message, result.prUrl)
                } else {
                    ContributionSubmitState.Failed(result.message)
                },
            )
        }
    }

    /** 保留给调用方：对话框此时是否开着。 */
    val isOpen: Boolean get() = file != null
}

@Composable
fun rememberModuleContributionDialog(
    scope: CoroutineScope,
    submittingText: () -> String,
    submit: suspend (File, ModulePropInfo?, String, String) -> SubmitResult,
): ModuleContributionDialogState {
    return remember(scope) {
        ModuleContributionDialogState(scope, submit, submittingText)
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
            onUploaderChange = state::updateUploader,
            onConsentChange = state::updateConsent,
            onSubmit = state::submit,
            onDismissRequest = state::close,
        )

        UiMode.Material -> ModuleContributionDialogMaterial(
            show = show,
            form = state.form,
            onSourceChange = state::updateSource,
            onUploaderChange = state::updateUploader,
            onConsentChange = state::updateConsent,
            onSubmit = state::submit,
            onDismissRequest = state::close,
        )
    }
}
