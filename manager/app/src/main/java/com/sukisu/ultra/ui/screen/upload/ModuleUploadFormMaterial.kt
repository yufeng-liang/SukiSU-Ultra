package com.sukisu.ultra.ui.screen.upload

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.component.CoolapkUidState
import com.sukisu.ultra.ui.component.material.ExpressiveScaffold
import com.sukisu.ultra.ui.component.material.TonalCard
import com.sukisu.ultra.ui.component.material.TopBarBackButton
import com.sukisu.ultra.ui.component.material.expressiveTopAppBarColors
import com.sukisu.ultra.ui.util.BatchLimits
import kotlinx.coroutines.launch

/**
 * Material 风格的上传表单。与 [ModuleUploadFormMiuix] 同一份状态、同一套判据，
 * 只换控件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleUploadFormMaterial(
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
    // 这一屏是钉住的小标题，与列表页/记录页的「随滚动收起」刻意不同：
    // 底部已经钉了一条提交栏，可滚区域本来就窄；标题再收一次，填到一半的人会突然
    // 失去「我在哪、怎么返回」这两个参照，而这两个参照在填表时比多出的 40dp 更重要。
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val limitProblem = ModuleUploadRules.limitProblem(
        count = state.items.size,
        sizes = state.items.map { it.sizeBytes },
        limits = limits,
    )
    val canSubmit = state.canSubmit(limits)
    val blockers = state.blockers(limits)
    val ownershipBlock = ModuleUploadRules.ownershipBlockReason(state.items)

    ExpressiveScaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.module_upload_form_title)) },
                navigationIcon = {
                    TopBarBackButton(onClick = onBack)
                },
                scrollBehavior = scrollBehavior,
                colors = expressiveTopAppBarColors(),
            )
        },
        // 提交按钮钉在底部：这一屏有四张卡，滚到底才能提交的旧做法让人白滚一趟。
        bottomBar = {
            SubmitBarMaterial(
                canSubmit = canSubmit,
                submitting = state.submitting,
                blockers = blockers,
                ownershipBlock = ownershipBlock,
                onSubmit = onSubmit,
            )
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            // 与上游模块页同一方案：水平 16dp 走 contentPadding 而不是 modifier，
            // 内容可以滚动到屏幕边缘之外，视觉上不留「截断」的硬边。
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding(),
            ),
        ) {
            item {
                BatchSummaryMaterial(
                    state = state,
                    canSubmit = canSubmit,
                )
            }

            item {
                // 墙：这一批每一枚一张 zip 图标卡，横向滑、点哪张编辑哪张。
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    // 墙自身宽度固定，靠外层 Row 摆到中间 —— 否则左侧贴边、右边空一大片。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        UploadZipWallMaterial(
                            items = state.items,
                            selectedPath = state.selectedPath,
                            enabled = !state.submitting,
                            onSelect = onSelectItem,
                            onPickMore = onPickMore,
                        )
                    }
                    val index = state.items.indexOfFirst { it.path == state.selectedPath }
                    if (state.items.size > 1) {
                        // 「现在编辑的是第几枚」做成一行状态条：单独一条、压成小字，
                        // 不与墙里的包名挤在一起，也不抢标题的位置。
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.module_upload_wall_editing,
                                    if (index >= 0) index + 1 else 1,
                                    state.items.size,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            val current = state.items.firstOrNull { it.path == state.selectedPath }
                ?: state.items.firstOrNull()
            if (current != null) {
                item(key = "detail-" + current.path) {
                    UploadZipCardMaterial(
                        item = current,
                        enabled = !state.submitting,
                        onRemove = { onRemove(current.path) },
                        onSourceChange = { onSourceChange(current.path, it) },
                        onApplyToAll = { onApplySourceToAll(current.source) },
                    )
                }
            }

            item {
                TonalCard(
                    modifier = Modifier
                        .padding(top = 20.dp)
                        .fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionLabelMaterial(
                            icon = Icons.Outlined.Info,
                            text = stringResource(R.string.module_upload_section_identity),
                            required = true,
                        )
                        OutlinedTextField(
                            value = state.uid,
                            onValueChange = onUidChange,
                            enabled = !state.submitting,
                            label = { Text(stringResource(R.string.module_upload_uid)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            // 与出处字段一致：非法时输入框本身变红，而不是只在下面换一句话。
                            isError = state.uidState is CoolapkUidState.Empty && state.uid.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = {
                                // 字段自己的提示/校验文案归位 supportingText：还没填显示
                                // 「在哪找 UID」，填了但不合法升为错误文案；其余状态由
                                // 下面 UidStatusRowMaterial 讲述。
                                when (state.uidState) {
                                    CoolapkUidState.Empty -> if (state.uid.isNotBlank()) {
                                        Text(stringResource(R.string.module_upload_uid_invalid))
                                    } else {
                                        Text(stringResource(R.string.module_upload_uid_hint))
                                    }

                                    else -> Unit
                                }
                            },
                        )
                        UidStatusRowMaterial(
                            state = state,
                            onRetry = onRetryUid,
                            onSignOut = onSignOut,
                        )
                        if (state.sessionMatchesUid) {
                            Text(
                                text = stringResource(R.string.module_upload_session_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = stringResource(R.string.module_upload_uid_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                ProofSectionMaterial(
                    state = state,
                    enabled = !state.submitting,
                    onSignatureConfirmedChange = onSignatureConfirmedChange,
                )
            }

            item {
                TonalCard(
                    modifier = Modifier
                        .padding(top = 20.dp)
                        .fillMaxWidth(),
                ) {
                    // 整行可点：勾选框在左侧、文字占三行时，只点那个小方块太窄。
                    // 勾选框与文字垂直居中：Top 会让方框压在首行文字的上半截，看着像没对齐。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.submitting) { onConsentChange(!state.consent) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = state.consent,
                            onCheckedChange = null,
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
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // 只差这一个勾时才提示：一打开表单就挂红字是噪音。
                            // 提示是补出来的（淡入 + 纵向展开），不是常驻再消失，视觉上少一次跳变。
                            AnimatedVisibility(
                                visible = state.uidVerified &&
                                    (state.signatureValid || state.sessionMatchesUid) &&
                                    !state.consent,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically(),
                            ) {
                                Text(
                                    text = stringResource(R.string.module_upload_consent_required),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
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
                        modifier = Modifier.padding(top = 12.dp),
                        text = limitText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (state.submitting || state.outcome != null) {
                item { OutcomeMaterial(state) }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** 顶部汇总：几枚、多大 —— 一进屏就知道这一批装了什么，不用滚到底。能不能传由底部提交栏说。 */
@Composable
private fun BatchSummaryMaterial(
    state: ModuleUploadFormState,
    canSubmit: Boolean,
) {
    val accent = if (canSubmit) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    TonalCard(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                modifier = Modifier.size(20.dp),
                imageVector = Icons.Outlined.FolderZip,
                contentDescription = null,
                tint = accent,
            )
            Column(modifier = Modifier.weight(1f)) {
                // 数量与总量拆成两级字重：数量是「这一批有几枚」，一进屏就要看到；总量只是附带。
                // 资源串里两段用「 · 」连接（四套语言都是这个分隔符），拆不开就整句按一个重量
                // 输出——不为了排版去猜别的语言用什么分隔符。
                val summary = stringResource(
                    R.string.module_upload_summary_count,
                    state.items.size,
                    ModuleUploadRules.formatBytes(state.totalBytes),
                )
                val parts = summary.split(" · ", limit = 2)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = parts[0],
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (parts.size == 2) {
                        Text(
                            modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
                            text = parts[1],
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 这里原有一行 submitHint(...)，和底部常驻提交栏那行是同一句。提交栏钉在屏底、
                // 从不离开视野，顶部再复读一遍只是噪音 —— 「为什么还不能传」只在按钮旁边说一次。
            }
        }
    }
}

/** 底部常驻提交栏：按钮永远在手边，左边一句说清能不能传。 */
@Composable
private fun SubmitBarMaterial(
    canSubmit: Boolean,
    submitting: Boolean,
    blockers: List<ModuleUploadRules.SubmitBlocker>,
    ownershipBlock: String,
    onSubmit: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 底部导航栏：Scaffold 的 contentWindowInsets 只留了水平方向（内容要能
                // 顶到状态栏/导航栏下面去），所以这条钉底的栏得自己把导航栏高度补上，
                // 否则三键导航下按钮直接被压住、手势导航下下半截落在手势条里。
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 10.dp,
                    bottom = 10.dp + WindowInsets.navigationBars.asPaddingValues()
                        .calculateBottomPadding(),
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = submitHint(canSubmit, blockers, ownershipBlock),
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    ownershipBlock.isNotEmpty() -> MaterialTheme.colorScheme.error
                    // 「还差 N 项」也是「现在传不了」，与归属被拦同一个观感：
                    // 一律错误色，但不加红底块——这两句话本身已经够长，再垫一块红就成了警告横幅。
                    // 「已上传 / 正在查 / 正在传」不是错误，交给下面的中性色。
                    ModuleUploadRules.hasActionableBlocker(blockers) -> MaterialTheme.colorScheme.error
                    canSubmit -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                // 提示最多两行：长文案再往下长，就会把提交按钮挤出屏幕。
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Button(
                modifier = Modifier.height(48.dp),
                onClick = onSubmit,
                enabled = canSubmit,
            ) {
                if (submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.CloudUpload,
                        contentDescription = null,
                    )
                }
                Text(
                    modifier = Modifier.padding(start = 6.dp),
                    text = stringResource(R.string.module_upload_submit),
                )
            }
        }
    }
}

/** 提交中 / 出结果后的那一段。契约没有字节回调：只报总数，不编百分比。 */
@Composable
private fun OutcomeMaterial(state: ModuleUploadFormState) {
    Column(
        modifier = Modifier.padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.submitting) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Column {
                    Text(
                        text = stringResource(R.string.module_upload_submitting),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(
                            R.string.module_upload_submitting_bytes,
                            state.items.size,
                            ModuleUploadRules.formatBytes(state.totalBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        when (val outcome = state.outcome) {
            // 结果是一屏里唯一的「结论」，值得一块底色：只靠一行彩色小字说，
            // 滚动时很容易被当成又一条说明文字。
            is UploadOutcome.Failure -> OutcomeCardMaterial(
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
                icon = Icons.Rounded.Warning,
                title = outcome.message,
            )

            is UploadOutcome.Success -> OutcomeCardMaterial(
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
                icon = Icons.Rounded.CheckCircle,
                title = outcome.message,
                prUrl = outcome.prUrl,
            )

            null -> Unit
        }
    }
}

/**
 * 结果卡：图标与标题左对齐（不居中），PR 链接单独一行。
 *
 * 链接给的是「能点开」的样子：主色 + 下划线。点开走系统浏览器；没有能接的应用时
 * 静默忽略 —— 一条结果链接不该把整屏崩掉。
 */
@Composable
private fun OutcomeCardMaterial(
    container: Color,
    content: Color,
    icon: ImageVector,
    title: String,
    prUrl: String? = null,
) {
    val uriHandler = LocalUriHandler.current
    Surface(
        color = container,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                modifier = Modifier.size(20.dp),
                imageVector = icon,
                contentDescription = null,
                tint = content,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = content,
                )
                if (prUrl != null) {
                    Text(
                        modifier = Modifier.clickable {
                            runCatching { uriHandler.openUri(prUrl) }
                        },
                        text = stringResource(R.string.module_upload_result_pr, prUrl),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    )
                }
            }
        }
    }
}

@Composable
private fun UploadZipCardMaterial(
    item: UploadZip,
    enabled: Boolean,
    onRemove: () -> Unit,
    onSourceChange: (String) -> Unit,
    onApplyToAll: () -> Unit,
) {
    val problem = ModuleUploadRules.sourceProblem(item.source)
    TonalCard(
        modifier = Modifier
            .padding(top = 20.dp)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.weight(1f),
                    // 包名压回 titleSmall：这一屏的主标题是顶栏与上面那句「几枚」，
                    // 单个包名不该与它们同重；删除按钮跟在它同一行右侧。
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 「移除」是破坏性动作，用 error 色，别跟主题主色混在一起——
                // 与模块页的「卸载」同一个写法（ModuleMaterial）。
                TextButton(
                    onClick = onRemove,
                    enabled = enabled,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.module_upload_remove))
                }
            }

            SectionLabelMaterial(
                icon = Icons.Outlined.Description,
                text = stringResource(R.string.module_upload_section_info),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 标签列固定 76dp：七个字段的取值从同一条竖线起，不再参差。
                InfoLineMaterial(stringResource(R.string.module_contribution_id), item.id)
                InfoLineMaterial(
                    stringResource(R.string.module_contribution_version),
                    "${item.version} (${item.versionCode})",
                    compact = true,
                )
                InfoLineMaterial(stringResource(R.string.module_contribution_name), item.name)
                if (item.author.isNotBlank()) {
                    InfoLineMaterial(stringResource(R.string.module_contribution_author), item.author)
                }
                if (item.description.isNotBlank()) {
                    InfoLineMaterial(
                        stringResource(R.string.module_contribution_description),
                        item.description,
                    )
                }
                InfoLineMaterial(
                    stringResource(R.string.module_upload_size),
                    ModuleUploadRules.formatBytes(item.sizeBytes),
                    compact = true,
                )
                InfoLineMaterial(
                    stringResource(R.string.module_upload_sha),
                    item.sha256,
                    mono = true,
                    compact = true,
                )
                if (item.checking) {
                    Text(
                        modifier = Modifier.padding(top = 4.dp),
                        text = stringResource(R.string.module_upload_checking),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (item.status != null) {
                    val copy = ModuleUploadRules.statusCopy(item.status)
                    // 状态是叙述不是报错：「已收录」只说这一版仓库里已经有了，列表屏同一个
                    // 状态也是中立色。真正拦住提交的原因由提交栏那行错误色去说，这里再红
                    // 一次就成了「两处都在喊」，反而分不出哪句才是不能提交的理由。
                    Text(
                        modifier = Modifier.padding(top = 4.dp),
                        text = stringResource(copy.title),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(copy.summary),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionLabelMaterial(
                icon = Icons.Outlined.Link,
                text = stringResource(R.string.module_upload_section_source),
                required = true,
            )
            OutlinedTextField(
                value = item.source,
                onValueChange = onSourceChange,
                enabled = enabled,
                label = { Text(stringResource(R.string.module_upload_source)) },
                singleLine = true,
                // 这是一个 URL 输入框：给 URI 键盘（带 / 与 .com），并关掉自动纠错/首字母大写，
                // 否则手打链接会被输入法改写。
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                ),
                isError = problem == ModuleUploadRules.SourceProblem.NOT_COOLAPK,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    // 「必填」已上移到分组标题右边（见 SectionLabelMaterial），这里只留
                    // 填在哪、填成什么样；填错时换成 error 文案，错误观感由上面的
                    // isError 承担。「应用到全部」按钮留在原位。
                    Text(
                        stringResource(
                            if (problem == ModuleUploadRules.SourceProblem.NOT_COOLAPK) {
                                R.string.module_upload_source_invalid
                            } else {
                                R.string.module_upload_source_hint
                            }
                        )
                    )
                },
            )
            // 提示文案已归位 supportingText，这行只留「应用到全部」按钮，保持靠右。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onApplyToAll,
                    // 只判非空会把半截链接（`www.cool`）灌进全部条目，之后每一枚都成 blocker，
                    // 而红字只在当前这一枚上显示。
                    enabled = enabled &&
                        ModuleUploadRules.sourceProblem(item.source) == ModuleUploadRules.SourceProblem.OK,
                ) {
                    Text(stringResource(R.string.module_upload_apply_all))
                }
            }
        }
    }
}

@Composable
private fun ProofSectionMaterial(
    state: ModuleUploadFormState,
    enabled: Boolean,
    onSignatureConfirmedChange: (Boolean) -> Unit,
) {
    // 走长期令牌的这一次上传不需要改签名 —— 整段收起，别让人白抄一次。
    if (state.sessionMatchesUid) return
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val proof = state.proof
    TonalCard(
        modifier = Modifier
            .padding(top = 20.dp)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabelMaterial(
                icon = Icons.Filled.VerifiedUser,
                text = stringResource(R.string.module_upload_proof_title),
            )

            if (proof == null) {
                // 只要还有一个包的 sha 没算完，就一行口令都不显示。
                Text(
                    text = stringResource(R.string.module_upload_proof_pending),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // 这一串是要拿去抄进酷安签名的东西，给它一个「可以复制的东西」的容器，
                // 而不是一行裸文本旁边挂个远处的按钮。
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            modifier = Modifier.weight(1f),
                            text = proof.text,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(ClipData.newPlainText("proof", proof.text)),
                                    )
                                }
                            },
                        ) {
                            Text(stringResource(R.string.module_upload_proof_copy))
                        }
                    }
                }
                // 怎么做 / 为什么可信 / 之后怎么办，三段各占一行：挤成一段时谁都不显眼，
                // 而「之后可以改回来」恰恰是最需要被看见的一句。
                Text(
                    text = stringResource(R.string.module_upload_proof_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.module_upload_proof_why),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.module_upload_proof_restore),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { onSignatureConfirmedChange(!state.signatureValid) },
                    // 文字只有一行，勾选框居中与文字同一条水平线上；
                    // 原先的 Top 会让方框压在文字的上半截。
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = state.signatureValid,
                        onCheckedChange = null,
                        enabled = enabled,
                    )
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = stringResource(R.string.module_upload_proof_confirm),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (state.confirmedSetKey != null && !state.signatureValid) {
                    Text(
                        text = stringResource(R.string.module_upload_proof_changed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun UidStatusRowMaterial(
    state: ModuleUploadFormState,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    when (val uidState = state.uidState) {
        // 已验证：头像 + 勾选描边动画 + 昵称，右侧给「退出验证」（只有真握着令牌时才给）。
        is CoolapkUidState.Verified -> Row(verticalAlignment = Alignment.CenterVertically) {
            VerifiedAvatar(
                avatarUrl = uidState.avatar.ifBlank { state.session?.avatar.orEmpty() },
                tint = MaterialTheme.colorScheme.primary,
                placeholder = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Text(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
                text = stringResource(R.string.module_upload_uid_verified, uidState.nickname),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (state.sessionActive) {
                TextButton(onClick = onSignOut, enabled = !state.submitting) {
                    Text(stringResource(R.string.module_upload_session_signout))
                }
            }
        }

        // 进行中也有自己的形态（转圈 + 次级色），三态才不只是「同一行字换了颜色」。
        CoolapkUidState.Checking -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(
                    if (state.sessionChecking) {
                        R.string.module_upload_session_checking
                    } else {
                        R.string.module_upload_uid_checking
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        CoolapkUidState.NotFound -> Text(
            text = stringResource(R.string.module_upload_uid_not_found),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        CoolapkUidState.Unavailable -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.module_upload_uid_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRetry, enabled = !state.submitting) {
                Icon(
                    modifier = Modifier.height(16.dp),
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                )
                Text(
                    modifier = Modifier.padding(start = 4.dp),
                    text = stringResource(R.string.network_retry),
                )
            }
        }

        CoolapkUidState.SelfReported -> Text(
            text = stringResource(R.string.module_upload_uid_self_reported),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 空态那行「在哪找 UID / UID 不合法」已挪进上面输入框的 supportingText，这里不再复读。
        CoolapkUidState.Empty -> Unit
    }
}

@Composable
private fun SectionLabelMaterial(
    icon: ImageVector,
    text: String,
    /**
     * 必填分组：标题后面紧跟一枚红色星号，再右挂一个「必填」。
     *
     * 原来「必填」是输入框下方 supportingText 里的红字，只有把视线落到框下面才看得见，
     * 而「这个字段必填」是决定要不要填的前提，应该和标题一起被读到。
     */
    required: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            modifier = Modifier.size(14.dp),
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 标题与星号放在内层 Row 里、彼此不留间距：星号要贴着字，隔开一道缝
        // 看上去就像「出处」和「*」是两个词。外侧的 6dp 只用来和图标分开。
        Row(
            modifier = Modifier.padding(start = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (required) {
                Text(
                    text = "*",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    modifier = Modifier.padding(start = 6.dp),
                    text = stringResource(R.string.module_upload_required),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * 一行 `标签 / 取值`：标签列定宽，取值从同一条竖线起。
 *
 * [compact] 给「版本 / 大小 / SHA」这类附带信息用：压到 labelSmall 并转成次级色，
 * 与包名、来源这些要看的信息拉开一档 —— 七个字段同重时，谁都不是重点。
 */
@Composable
private fun InfoLineMaterial(
    label: String,
    value: String,
    mono: Boolean = false,
    compact: Boolean = false,
) {
    // 标签与取值之间必须留一道缝：76dp 的标签列在英文下会被 "Module name" 占满，
    // 没有间距时渲染成 "Module nameQA Demo Module"，两个字段糊成一串。
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            modifier = Modifier.width(76.dp),
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            modifier = Modifier.weight(1f),
            text = value,
            style = if (compact) {
                MaterialTheme.typography.labelSmall
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = if (compact) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            maxLines = if (mono) 1 else 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
