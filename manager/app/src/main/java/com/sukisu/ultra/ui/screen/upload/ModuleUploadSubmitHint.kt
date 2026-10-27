package com.sukisu.ultra.ui.screen.upload

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sukisu.ultra.R

/**
 * 提交栏左边那一句「现在能不能传」。
 *
 * 三档，按优先级：
 *  1. 能传 → 「可以上传」
 *  2. 归属被服务端判成别人的 → **服务端原话**（[ModuleUploadRules.ownershipBlockReason]）。
 *     它压在「还差 N 项」前面，是因为那一条不是用户动手能解决的：显示「还差 1 项」会让他
 *     在这一屏里找一个永远不存在的待办；显示原话他才知道该去联系原作者或者换一个模块。
 *  3. 其余 → 「还差 N 项才能上传」
 *
 * 只在这里拼一次，Material 与 Miuix 两套界面共用：文案口径分叉过一次（两屏各写各的），
 * 这次不再给它分叉的机会。
 */
@Composable
fun submitHint(
    canSubmit: Boolean,
    blockers: Int,
    ownershipBlock: String,
): String = when {
    canSubmit -> stringResource(R.string.module_upload_ready)
    ownershipBlock.isNotEmpty() -> ownershipBlock
    else -> stringResource(R.string.module_upload_blockers, blockers)
}
