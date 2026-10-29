package com.sukisu.ultra.ui.screen.upload

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sukisu.ultra.R

/**
 * 提交栏左边那一句「现在能不能传」。
 *
 * 按优先级：
 *  1. 能传 → 「可以上传」
 *  2. 归属被服务端判成别人的 → **服务端原话**（[ModuleUploadRules.ownershipBlockReason]），
 *     服务端没给话时用本地化兜底句。它压在「还差 N 项」前面，是因为那一条不是用户动手
 *     能解决的：显示「还差 1 项」会让他找一个永远不存在的待办。
 *  3. 已经传成功 / 正在传 / 正在查 / 已收录 → 各给一句专门的话。
 *     以前这几种一律落到「还差 N 项」，而 N 只数用户能动手的那几项，于是出现
 *     「还差 0 项才能上传」而按钮是灰的。
 *  4. 其余 → 「还差 N 项才能上传」。
 *
 * 只在这里拼一次，Material 与 Miuix 两套界面共用：文案口径分叉过一次（两屏各写各的），
 * 这次不再给它分叉的机会。
 */
@Composable
fun submitHint(
    canSubmit: Boolean,
    blockers: List<ModuleUploadRules.SubmitBlocker>,
    ownershipBlock: String,
): String = when {
    canSubmit -> stringResource(R.string.module_upload_ready)

    ModuleUploadRules.SubmitBlocker.OWNERSHIP in blockers ->
        ownershipBlock.ifEmpty { stringResource(R.string.module_upload_ownership_not_mine_summary) }

    ModuleUploadRules.SubmitBlocker.ALREADY_SUBMITTED in blockers ->
        stringResource(R.string.module_upload_submit_success)

    ModuleUploadRules.SubmitBlocker.SUBMITTING in blockers ->
        stringResource(R.string.module_upload_submitting)

    ModuleUploadRules.SubmitBlocker.CHECKING in blockers ->
        stringResource(R.string.module_upload_checking)

    ModuleUploadRules.SubmitBlocker.RECORDED in blockers ->
        stringResource(R.string.module_upload_status_recorded_summary)

    else -> stringResource(
        R.string.module_upload_blockers,
        ModuleUploadRules.actionableBlockerCount(blockers),
    )
}
