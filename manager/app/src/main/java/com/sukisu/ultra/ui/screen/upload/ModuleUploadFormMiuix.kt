package com.sukisu.ultra.ui.screen.upload

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.component.CoolapkUidState
import com.sukisu.ultra.ui.theme.LocalEnableBlur
import com.sukisu.ultra.ui.util.BatchLimits
import com.sukisu.ultra.ui.util.BlurredBar
import com.sukisu.ultra.ui.util.rememberBlurBackdrop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close2
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Miuix 风格的上传表单：每个 zip 一张卡（只读信息 + 出处）、一个 UID、一句确认、
 * 一串整批共用的口令，最后才是提交。
 *
 * 所有「能不能提交」的判断都在 [ModuleUploadRules.canSubmit]，这里只把它的输入画出来。
 * 提交期间整页只读：上传可能持续几十秒，中途改字段会让屏幕上的表单与实际传上去的
 * 元数据对不上。
 */
@Composable
fun ModuleUploadFormMiuix(
    state: ModuleUploadFormState,
    limits: BatchLimits?,
    onBack: () -> Unit,
    onRemove: (String) -> Unit,
    onSourceChange: (String, String) -> Unit,
    onApplySourceToAll: (String) -> Unit,
    onUidChange: (String) -> Unit,
    onRetryUid: () -> Unit,
    onSelectItem: (String) -> Unit,
    onPickMore: () -> Unit,
    onSignOut: () -> Unit,
    onConsentChange: (Boolean) -> Unit,
    onSignatureConfirmedChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    val limitProblem = ModuleUploadRules.limitProblem(
        count = state.items.size,
        sizes = state.items.map { it.sizeBytes },
        limits = limits,
    )
    val canSubmit = state.canSubmit(limits)
    val blockers = remainingBlockersMiuix(state, limitProblem)
    val ownershipBlock = ModuleUploadRules.ownershipBlockReason(state.items)

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.module_upload_form_title),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            val layoutDirection = LocalLayoutDirection.current
                            Icon(
                                modifier = Modifier.graphicsLayer {
                                    if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                },
                                imageVector = MiuixIcons.Back,
                                contentDescription = null,
                            )
                        }
                    },
                )
            }
        },
        // 提交按钮钉在底部：这一屏有四张卡，滚到底才能提交的旧做法让人白滚一趟。
        bottomBar = {
            SubmitBarMiuix(
                canSubmit = canSubmit,
                submitting = state.submitting,
                blockers = blockers,
                ownershipBlock = ownershipBlock,
                onSubmit = onSubmit,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
            overscrollEffect = null,
        ) {
            item {
                BatchSummaryMiuix(
                    state = state,
                    canSubmit = canSubmit,
                )
            }

            item {
                // 墙：这一批每一枚一张 zip 图标卡，横向滑、点哪张编辑哪张。
                // 只占一行高度 —— 十枚包也不会把表单撑成十几屏。
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    // 墙自身宽度固定，靠外层 Row 摆到中间 —— 否则左侧贴边、右边空一大片。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        UploadZipWallMiuix(
                            items = state.items,
                            selectedPath = state.selectedPath,
                            enabled = !state.submitting,
                            onSelect = onSelectItem,
                            onPickMore = onPickMore,
                        )
                    }
                    val index = state.items.indexOfFirst { it.path == state.selectedPath }
                    if (state.items.size > 1) {
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            text = stringResource(
                                R.string.module_upload_wall_editing,
                                if (index >= 0) index + 1 else 1,
                                state.items.size,
                            ),
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }

            // 舞台上只有当前这一枚：改出处只可能改它，所以不给「应用到全部」以外的批处理动作。
            val current = state.items.firstOrNull { it.path == state.selectedPath }
                ?: state.items.firstOrNull()
            if (current != null) {
                item(key = "detail-" + current.path) {
                    UploadZipCardMiuix(
                        item = current,
                        enabled = !state.submitting,
                        onRemove = { onRemove(current.path) },
                        onSourceChange = { onSourceChange(current.path, it) },
                        onApplyToAll = { onApplySourceToAll(current.source) },
                    )
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .padding(top = 20.dp)
                        .fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionLabelMiuix(
                            icon = Icons.Outlined.Info,
                            text = stringResource(R.string.module_upload_section_identity),
                        )
                        TextField(
                            value = state.uid,
                            onValueChange = onUidChange,
                            enabled = !state.submitting,
                            label = stringResource(R.string.module_upload_uid),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        UidStatusRowMiuix(
                            state = state,
                            onRetry = onRetryUid,
                            onSignOut = onSignOut,
                        )
                        if (state.sessionMatchesUid) {
                            Text(
                                text = stringResource(R.string.module_upload_session_hint),
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Text(
                            text = stringResource(R.string.module_upload_uid_public),
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = stringResource(R.string.module_upload_uid_level_hint),
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            item {
                ProofSectionMiuix(
                    state = state,
                    enabled = !state.submitting,
                    onSignatureConfirmedChange = onSignatureConfirmedChange,
                )
            }

            item {
                Card(
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .fillMaxWidth(),
                ) {
                    // 整行可点：勾选框在左上、文字占三行时，只点那个小方块太窄。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.submitting) { onConsentChange(!state.consent) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Checkbox(
                            state = if (state.consent) {
                                androidx.compose.ui.state.ToggleableState.On
                            } else {
                                androidx.compose.ui.state.ToggleableState.Off
                            },
                            onClick = null,
                            enabled = !state.submitting,
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.module_upload_consent),
                                fontSize = 13.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                            // 只差这一个勾时才提示：一打开表单就挂红字是噪音。
                            if (state.uidVerified &&
                                (state.signatureValid || state.sessionMatchesUid) &&
                                !state.consent
                            ) {
                                Text(
                                    text = stringResource(R.string.module_upload_consent_required),
                                    fontSize = 12.sp,
                                    color = colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            if (limitProblem != ModuleUploadRules.LimitProblem.NONE) {
                item {
                    // 单枚 / 合计两档要把服务端自报的数字写进话术（§1.3），
                    // 不能只挂一句「超过上限」让用户自己猜差多少。
                    val limitText = when (limitProblem) {
                        ModuleUploadRules.LimitProblem.TOO_MANY -> stringResource(
                            R.string.module_upload_limit_too_many,
                            ModuleUploadRules.maxModules(limits),
                        )

                        ModuleUploadRules.LimitProblem.SINGLE_TOO_BIG -> stringResource(
                            R.string.module_upload_limit_single_too_big,
                            ModuleUploadRules.formatBytes(limits?.maxBytes ?: 0L),
                        )

                        ModuleUploadRules.LimitProblem.TOTAL_TOO_BIG -> stringResource(
                            R.string.module_upload_limit_total_too_big,
                            ModuleUploadRules.formatBytes(limits?.maxTotalBytes ?: 0L),
                        )

                        else -> stringResource(R.string.module_upload_limit_unknown)
                    }
                    Text(
                        modifier = Modifier.padding(top = 10.dp),
                        text = limitText,
                        fontSize = 12.sp,
                        color = colorScheme.error,
                    )
                }
            }

            if (state.submitting || state.outcome != null) {
                item { OutcomeMiuix(state) }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * 还差几项才能提交。只数「用户还得动手」的项，判据与 [ModuleUploadRules.canSubmit] 一一对应，
 * 不新增规则 —— 这里只负责把「差什么」说清楚。
 *
 * 归属闸【不】计入这里：那一条用户动手也解决不了（模块已经记在别人名下），把它算进
 * 「还差 N 项」等于派给他一件做不完的事。那一类由 [ModuleUploadRules.ownershipBlockReason]
 * 单独出一句原话，界面上优先显示它。
 */
private fun remainingBlockersMiuix(
    state: ModuleUploadFormState,
    limitProblem: ModuleUploadRules.LimitProblem,
): Int {
    var count = 0
    if (!state.uidVerified) count++
    if (!state.sessionMatchesUid && !state.signatureValid) count++
    if (!state.consent) count++
    if (limitProblem != ModuleUploadRules.LimitProblem.NONE) count++
    if (state.items.any { ModuleUploadRules.sourceProblem(it.source) != ModuleUploadRules.SourceProblem.OK }) {
        count++
    }
    return count
}

/** 顶部汇总：几枚、多大 —— 一进屏就知道这一批装了什么，不用滚到底。能不能传由底部提交栏说。 */
@Composable
private fun BatchSummaryMiuix(
    state: ModuleUploadFormState,
    canSubmit: Boolean,
) {
    Card(
        modifier = Modifier
            .padding(top = 16.dp)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 图标块延用列表行卡片那套语言（圆角块 + 淡底色）：两屏之间不换视觉习惯。
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    modifier = Modifier.size(18.dp),
                    imageVector = MiuixIcons.File,
                    contentDescription = null,
                    tint = colorScheme.onSecondaryContainer,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // 数量与总量是同一条带格式的文案（不许新造字符串），整行按 16sp SemiBold 立起来。
                Text(
                    text = stringResource(
                        R.string.module_upload_summary_count,
                        state.items.size,
                        ModuleUploadRules.formatBytes(state.totalBytes),
                    ),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                // 这里原有一行 submitHint(...)，和底部常驻提交栏那行是同一句。提交栏钉在屏底、
                // 从不离开视野，顶部再复读一遍只是噪音 —— 「为什么还不能传」只在按钮旁边说一次。
            }
        }
    }
}

/** 底部常驻提交栏：按钮永远在手边，左边一句说清能不能传。 */
@Composable
private fun SubmitBarMiuix(
    canSubmit: Boolean,
    submitting: Boolean,
    blockers: Int,
    ownershipBlock: String,
    onSubmit: () -> Unit,
) {
    Surface(
        color = colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = submitHint(canSubmit, blockers, ownershipBlock),
                fontSize = 12.sp,
                // 两道闸都算「现在不能传」，都用 error 色把原因顶出来：差 N 项与归属判决
                // 对用户是同一件事——按钮是灰的。但不给底色，这两句本来就够长。
                color = when {
                    canSubmit -> colorScheme.primary
                    ownershipBlock.isNotEmpty() || blockers > 0 -> colorScheme.error
                    else -> colorScheme.onSurfaceVariantSummary
                },
                // 长文案最多两行：宁可截断，也不能把提交按钮挤出屏幕。
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Button(
                modifier = Modifier.height(48.dp),
                onClick = onSubmit,
                enabled = canSubmit,
                cornerRadius = 12.dp,
            ) {
                if (submitting) {
                    CircularProgressIndicator(progress = null, size = 18.dp, strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = MiuixIcons.UploadCloud,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                Text(text = stringResource(R.string.module_upload_submit))
            }
        }
    }
}

/** 提交中 / 出结果后的那一段。契约没有字节回调：只报总数，不编百分比。 */
@Composable
private fun OutcomeMiuix(state: ModuleUploadFormState) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier.padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.submitting) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(progress = null, size = 20.dp, strokeWidth = 2.dp)
                Column {
                    Text(
                        text = stringResource(R.string.module_upload_submitting),
                        fontSize = 14.sp,
                    )
                    Text(
                        text = stringResource(
                            R.string.module_upload_submitting_bytes,
                            state.items.size,
                            ModuleUploadRules.formatBytes(state.totalBytes),
                        ),
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        when (val outcome = state.outcome) {
            // 失败：整条铺 errorContainer 底，✗ 图标与原因左对齐。原来只是一行红字，
            // 掉在表单最底部跟校验提示长得一模一样，分不出「哪里填错了」和「传完了」。
            is UploadOutcome.Failure -> Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(color = colorScheme.errorContainer),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(18.dp),
                        imageVector = MiuixIcons.Close2,
                        contentDescription = null,
                        tint = colorScheme.onErrorContainer,
                    )
                    Text(
                        modifier = Modifier.weight(1f),
                        text = outcome.message,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onErrorContainer,
                    )
                }
            }

            // 成功：primaryContainer 底 + 勾，PR 链接自成一行（不再是跟在标题屁股后面的小字）。
            is UploadOutcome.Success -> Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(color = colorScheme.primaryContainer),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(18.dp),
                        imageVector = MiuixIcons.Ok,
                        contentDescription = null,
                        tint = colorScheme.onPrimaryContainer,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = outcome.message,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.onPrimaryContainer,
                        )
                        outcome.prUrl?.let { url ->
                            // 能点就直接开浏览器：URL 本来就在手里，没有为此新增回调参数。
                            Text(
                                modifier = Modifier.clickable {
                                    runCatching { uriHandler.openUri(url) }
                                },
                                text = stringResource(R.string.module_upload_result_pr, url),
                                fontSize = 12.sp,
                                color = colorScheme.primary,
                                textDecoration = TextDecoration.Underline,
                            )
                        }
                    }
                }
            }

            null -> Unit
        }
    }
}

@Composable
private fun UploadZipCardMiuix(
    item: UploadZip,
    enabled: Boolean,
    onRemove: () -> Unit,
    onSourceChange: (String) -> Unit,
    onApplyToAll: () -> Unit,
) {
    val problem = ModuleUploadRules.sourceProblem(item.source)
    Card(
        modifier = Modifier
            .padding(top = 20.dp)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 包名一行作主（titleSmall），版本与大小降成它下面那行弱信息：
            // 这三件事原来各占一行、字重相同，读起来像三件并列的事，其实只有一件。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = item.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${item.version} (${item.versionCode}) · " +
                            ModuleUploadRules.formatBytes(item.sizeBytes),
                        fontSize = 11.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    text = stringResource(R.string.module_upload_remove),
                    onClick = onRemove,
                    enabled = enabled,
                )
            }

            // 只读信息：索引里的字段必须与 zip 里的 module.prop 一致，不给编辑。
            SectionLabelMiuix(
                icon = Icons.Outlined.Description,
                text = stringResource(R.string.module_upload_section_info),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 标签列固定 76dp：七个字段的取值从同一条竖线起，不再参差。
                InfoLineMiuix(stringResource(R.string.module_contribution_id), item.id)
                InfoLineMiuix(stringResource(R.string.module_contribution_name), item.name)
                if (item.author.isNotBlank()) {
                    InfoLineMiuix(stringResource(R.string.module_contribution_author), item.author)
                }
                if (item.description.isNotBlank()) {
                    InfoLineMiuix(
                        stringResource(R.string.module_contribution_description),
                        item.description,
                    )
                }
                InfoLineMiuix(stringResource(R.string.module_upload_sha), item.sha256, mono = true)
                if (item.checking) {
                    Text(
                        modifier = Modifier.padding(top = 4.dp),
                        text = stringResource(R.string.module_upload_checking),
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                } else if (item.status != null) {
                    val copy = ModuleUploadRules.statusCopy(item.status)
                    // 状态是叙述不是报错：「已收录」只说这一版仓库里已经有了，列表屏同一个
                    // 状态也是中立色。真正拦住提交的原因由提交栏那行错误色去说，这里再红
                    // 一次就成了「两处都在喊」，反而分不出哪句才是不能提交的理由。
                    Text(
                        modifier = Modifier.padding(top = 4.dp),
                        text = stringResource(copy.title),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(copy.summary),
                        fontSize = 11.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            SectionLabelMiuix(
                icon = Icons.Outlined.Link,
                text = stringResource(R.string.module_upload_section_source),
            )
            TextField(
                value = item.source,
                onValueChange = onSourceChange,
                enabled = enabled,
                label = stringResource(R.string.module_upload_source),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(
                        when (problem) {
                            ModuleUploadRules.SourceProblem.EMPTY -> R.string.module_upload_source_empty
                            ModuleUploadRules.SourceProblem.NOT_COOLAPK -> R.string.module_upload_source_invalid
                            ModuleUploadRules.SourceProblem.OK -> R.string.module_upload_source_hint
                        }
                    ),
                    fontSize = 12.sp,
                    color = if (problem == ModuleUploadRules.SourceProblem.OK) {
                        colorScheme.onSurfaceVariantSummary
                    } else {
                        colorScheme.error
                    },
                )
                TextButton(
                    text = stringResource(R.string.module_upload_apply_all),
                    onClick = onApplyToAll,
                    enabled = enabled && item.source.isNotBlank(),
                )
            }
        }
    }
}

@Composable
private fun ProofSectionMiuix(
    state: ModuleUploadFormState,
    enabled: Boolean,
    onSignatureConfirmedChange: (Boolean) -> Unit,
) {
    // 走长期令牌的这一次上传不需要改签名 —— 这一整段收起（连同「去酷安改签名」那句）。
    // 判据与提交那一步同一份：令牌绑的就是输入框里这个 UID，就别让人白抄一次签名。
    if (state.sessionMatchesUid) return
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val proof = state.proof
    Card(
        modifier = Modifier
            .padding(top = 20.dp)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabelMiuix(
                icon = Icons.Filled.VerifiedUser,
                text = stringResource(R.string.module_upload_proof_title),
            )

            if (proof == null) {
                // 只要还有一个包的 sha 没算完，就一行口令都不显示：
                // 半截哈希算出来的口令用户抄走也是废的。
                Text(
                    text = stringResource(R.string.module_upload_proof_pending),
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            } else {
                // 这一串是要拿去抄进酷安签名的东西，给它一个「可以复制的东西」的容器，
                // 而不是一行裸文本旁边挂个远处的按钮。
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.defaultColors(color = colorScheme.surfaceContainer),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            modifier = Modifier.weight(1f),
                            text = proof.text,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            text = stringResource(R.string.module_upload_proof_copy),
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(ClipData.newPlainText("proof", proof.text)),
                                    )
                                }
                            },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.module_upload_proof_hint),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { onSignatureConfirmedChange(!state.signatureValid) },
                    verticalAlignment = Alignment.Top,
                ) {
                    Checkbox(
                        state = if (state.signatureValid) {
                            androidx.compose.ui.state.ToggleableState.On
                        } else {
                            androidx.compose.ui.state.ToggleableState.Off
                        },
                        onClick = null,
                        enabled = enabled,
                    )
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = stringResource(R.string.module_upload_proof_confirm),
                        fontSize = 13.sp,
                    )
                }
                // 勾过之后集合又变了：明确告诉用户要重抄，而不是只把勾去掉。
                if (state.confirmedSetKey != null && !state.signatureValid) {
                    Text(
                        text = stringResource(R.string.module_upload_proof_changed),
                        fontSize = 12.sp,
                        color = colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun UidStatusRowMiuix(
    state: ModuleUploadFormState,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    when (val uidState = state.uidState) {
        // 已验证：头像（有就画、没有就一个底圈）+ 勾选描边动画 + 昵称，右侧给「退出验证」。
        // 这里不用「一行小字」是因为「这个人是谁」比「这个字段填对了」更重要 ——
        // 用户要一眼认出「现在是我」，而不是读一行灰字。
        is CoolapkUidState.Verified -> Row(verticalAlignment = Alignment.CenterVertically) {
            VerifiedAvatar(
                avatarUrl = uidState.avatar.ifBlank { state.session?.avatar.orEmpty() },
                tint = colorScheme.primary,
                placeholder = colorScheme.surfaceContainer,
            )
            Text(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
                text = stringResource(R.string.module_upload_uid_verified, uidState.nickname),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.primary,
            )
            // 只有手里真有一张长期令牌时才给「退出验证」：没有令牌的话这个按钮点了
            // 只是把 UID 清空，用户会以为「验证被取消了」，而其实什么都没发生。
            if (state.sessionActive) {
                TextButton(
                    text = stringResource(R.string.module_upload_session_signout),
                    onClick = onSignOut,
                    enabled = !state.submitting,
                )
            }
        }

        CoolapkUidState.Checking -> Text(
            text = stringResource(
                if (state.sessionChecking) {
                    R.string.module_upload_session_checking
                } else {
                    R.string.module_upload_uid_checking
                },
            ),
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )

        CoolapkUidState.NotFound -> Text(
            text = stringResource(R.string.module_upload_uid_not_found),
            fontSize = 13.sp,
            color = colorScheme.error,
        )

        CoolapkUidState.Unavailable -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.module_upload_uid_unavailable),
                fontSize = 13.sp,
                color = colorScheme.error,
            )
            TextButton(
                text = stringResource(R.string.network_retry),
                onClick = onRetry,
                enabled = !state.submitting,
            )
        }

        CoolapkUidState.SelfReported -> Text(
            text = stringResource(R.string.module_upload_uid_self_reported),
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )

        CoolapkUidState.Empty -> Text(
            text = if (state.uid.isNotBlank()) {
                stringResource(R.string.module_upload_uid_invalid)
            } else {
                stringResource(R.string.module_upload_uid_hint)
            },
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun SectionLabelMiuix(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            modifier = Modifier.size(14.dp),
            imageVector = icon,
            contentDescription = null,
            tint = colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 一行 `标签 / 取值`：标签列定宽，取值从同一条竖线起。 */
@Composable
private fun InfoLineMiuix(label: String, value: String, mono: Boolean = false) {
    // 同 Material 侧：76dp 标签列在英文下会被占满，没有间距时标签与取值会糊成一串。
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            modifier = Modifier.width(76.dp),
            text = label,
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            modifier = Modifier.weight(1f),
            text = value,
            fontSize = 12.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            maxLines = if (mono) 1 else 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
