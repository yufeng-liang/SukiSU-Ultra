package com.sukisu.ultra.ui.screen.upload

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.theme.LocalEnableBlur
import com.sukisu.ultra.ui.util.BlurredBar
import com.sukisu.ultra.ui.util.rememberBlurBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
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
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 身份页（Miuix）：上传之前先把「你是谁」这件事一次性办完。
 *
 * 这一屏只在**手上没有有效令牌**时出现。它把原先散落在上传表单里的三件事——填 UID、
 * 抄签名口令、勾承诺——一次性收进来，办完就写下一张九十天的令牌，之后每次进来直接是
 * 列表，用户只管填模块信息。
 *
 * 为什么口令要绑一枚具体的包：[ModuleUploadFormState.verifyItem] 的注释 —— 酷安签名是
 * 公开的，口令只绑 UID 的话，任何看见签名的人都能替别人领令牌。
 */
@Composable
fun ModuleUploadIdentityMiuix(
    state: ModuleUploadFormState,
    onExit: () -> Unit,
    onUidChange: (String) -> Unit,
    onRetryUid: () -> Unit,
    onConsentChange: (Boolean) -> Unit,
    onRenewCommit: () -> Unit,
    onVerify: () -> Unit,
    /** 用户自称已经升级到能验证的酷安等级时，清掉「等级不足」的拦截再试一次。 */
    onClearLevelBlock: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val commit = state.identityCommit

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.module_upload_identity_title),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onExit) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            IdentityBarMiuix(
                state = state,
                onVerify = onVerify,
                onClearLevelBlock = onClearLevelBlock,
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
                // 一进来就把「为什么要这些信息」讲清楚：这一屏是所有用户都要过的第一道门，
                // 讲不清就会像在收表。视觉借表单顶部汇总卡的语言（圆角图标块 + 加粗标题），
                // 让它读起来是「这一屏的说明」，而不是又一张待填表。
                Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                // 问号而不是「已验证的人」：这一格回答的是「为什么要填这些」，
                                // 用人形图标会读成「这里要填身份」，而它下面那张卡才是填的地方。
                                imageVector = Icons.Outlined.HelpOutline,
                                contentDescription = null,
                                tint = colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.module_upload_identity_why_title),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.module_upload_identity_why_body),
                                fontSize = 13.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.padding(top = 20.dp).fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionLabelMiuix(
                            icon = Icons.Filled.VerifiedUser,
                            text = stringResource(R.string.module_upload_section_identity),
                            required = true,
                        )
                        TextField(
                            value = state.uid,
                            onValueChange = onUidChange,
                            enabled = !state.verifying,
                            label = stringResource(R.string.module_upload_uid),
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        UidStatusRowMiuix(
                            state = state,
                            onRetry = onRetryUid,
                            // 身份页没有「退出验证」——这一屏本来就是因为没有令牌才来的。
                            onSignOut = null,
                        )
                        Text(
                            text = stringResource(R.string.module_upload_identity_level),
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            item {
                // 「你的验证串」：抄进酷安签名的那一行。它由本机随机生成、只算它的哈希，
                // 所以签名里公开的只是承诺，别人反推不出验证串，也就领不走你的令牌。
                // 与「要传的包」无关 —— 旧版挑一枚包当锚点那一步正是为了做到这一点，
                // 哈希承诺本身就把这件事解决了。
                Card(modifier = Modifier.padding(top = 20.dp).fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionLabelMiuix(
                            icon = Icons.Filled.VerifiedUser,
                            text = stringResource(R.string.module_upload_identity_commit_title),
                            required = true,
                        )
                        if (commit == null) {
                            Text(
                                text = stringResource(R.string.module_upload_identity_commit_pending),
                                fontSize = 13.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        } else {
                            // 复制后把按钮字样换成「已复制」并自己复原：这片界面没有 Snackbar
                            // 宿主，按下去什么都不变的话，用户只能靠回酷安粘贴一次来验证。
                            var copied by remember(commit) { mutableStateOf(false) }
                            LaunchedEffect(copied) {
                                if (copied) {
                                    delay(1_600L)
                                    copied = false
                                }
                            }
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // 换一串的入口是隐藏的长按，不再画成按钮：这一行本身是
                                    // 「拿去贴签名」的承诺串，旁边挂个「换一串」会让人以为
                                    // 它是个可随时重掷的临时值。但承诺串是公开贴出去的，
                                    // 真泄露时必须留一条自救路径，所以把入口藏进长按里。
                                    // 真等满 3 秒才换。系统长按阈值只有约 0.5 秒，误触一次就把验证串
                                    // 换掉，用户得回酷安重新改签名 —— 这个代价不该由一次手滑决定。
                                    .pointerInput(commit) {
                                        awaitEachGesture {
                                            awaitFirstDown(requireUnconsumed = false)
                                            val heldLongEnough = withTimeoutOrNull(3_000L) {
                                                // 正常抬起（或手势被别处取消）就算「没按满」。
                                                waitForUpOrCancellation()
                                                false
                                            } == null
                                            if (heldLongEnough) {
                                                onRenewCommit()
                                                // 手指多半还按着：吃掉剩下的事件，免得再触发一次。
                                                waitForUpOrCancellation()
                                            }
                                        }
                                    },
                                color = colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        modifier = Modifier.weight(1f),
                                        text = commit,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    TextButton(
                                        text = stringResource(
                                            if (copied) R.string.module_upload_identity_commit_copied
                                            else R.string.module_upload_identity_commit_copy,
                                        ),
                                        onClick = {
                                            scope.launch {
                                                clipboard.setClipEntry(
                                                    ClipEntry(ClipData.newPlainText("identity", commit)),
                                                )
                                                copied = true
                                            }
                                        },
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.module_upload_identity_commit_hint),
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.padding(top = 20.dp).fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = stringResource(R.string.module_upload_identity_pledge_title),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.onSurface,
                        )
                        Text(
                            modifier = Modifier.padding(top = 6.dp),
                            text = stringResource(R.string.module_upload_identity_pledge_lead),
                            fontSize = 13.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                        PledgeLineMiuix(stringResource(R.string.module_upload_identity_pledge_1))
                        PledgeLineMiuix(stringResource(R.string.module_upload_identity_pledge_2))
                        PledgeLineMiuix(stringResource(R.string.module_upload_identity_pledge_3))
                        Spacer(modifier = Modifier.height(6.dp))
                        // 整行可点：勾选框在左侧、文字占两行时，只点那个小方块太窄。
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = state.consent,
                                    enabled = !state.verifying,
                                    role = Role.Checkbox,
                                    onValueChange = onConsentChange,
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                state = if (state.consent) {
                                    androidx.compose.ui.state.ToggleableState.On
                                } else {
                                    androidx.compose.ui.state.ToggleableState.Off
                                },
                                onClick = null,
                            )
                            Text(
                                modifier = Modifier.padding(start = 10.dp),
                                text = stringResource(R.string.module_upload_identity_pledge),
                                fontSize = 14.sp,
                                color = colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

/**
 * 底部常驻栏：与表单页的 SubmitBarMiuix 同一副骨架——左侧一行状态 hint，右侧「验证身份」。
 *
 * hint 三态：验证中是中性句；还差步骤时用错误色数出差几步；条件都齐了换主色催一下。
 * 按钮不满足条件时**仍然画着**（只是不可点）而不是藏起来：藏起来的话，用户看不出还差什么，
 * 只会觉得这一屏没有出口。差哪一项由正文里的必填标记与这条 hint 共同承担。
 */
@Composable
private fun IdentityBarMiuix(
    state: ModuleUploadFormState,
    onVerify: () -> Unit,
    onClearLevelBlock: () -> Unit,
) {
    val missing = listOf(
        state.uid.trim().length in 4..12,
        // 形状对不算数：还得酷安那边确实有这个号。查无此人时服务端必然也查不到，
        // 放行只是拿一次接口额度换一句已知的结论。
        state.uidVerified,
        state.identityCommit != null,
        state.consent,
    ).count { !it }
    val verifying = state.verifying
    val error = state.verifyError

    // 冷却剩余秒数随时间走：只在真的处于冷却时起一个每秒一次的 ticker，
    // now 越过截止时间就停（不常驻轮询）。key 用截止时间，换号或被清掉时自动重启。
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.verifyCooldownUntil) {
        val deadline = state.verifyCooldownUntil
        if (deadline <= System.currentTimeMillis()) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            if (now >= deadline) break
            delay(1_000L)
        }
    }

    // 等级不足只拦「当时被拒的那个号」：用户填了新号之后不该继续被上一号的拦截压着。
    val levelBlocked = state.levelBlockedUid.isNotEmpty() &&
        state.levelBlockedUid == state.uid.trim()
    val cooldownMillis = (state.verifyCooldownUntil - now).coerceAtLeast(0L)
    val coolingDown = !levelBlocked && cooldownMillis > 0L
    // 向上取整：剩 0.4 秒显示「请 1 秒后再试」，不出现「请 0 秒」。
    val cooldownSeconds = ((cooldownMillis + 999L) / 1_000L).toInt()
    // 优先级：等级不足（要用户去升级，是更根本的阻塞）> 冷却 > 既有三态。
    val hint = when {
        levelBlocked -> stringResource(R.string.module_upload_identity_level_blocked)
        coolingDown -> stringResource(R.string.module_upload_identity_cooldown, cooldownSeconds)
        verifying -> stringResource(R.string.module_upload_identity_verifying)
        missing > 0 -> stringResource(R.string.module_upload_identity_missing, missing)
        else -> stringResource(R.string.module_upload_identity_ready)
    }
    val hintColor = when {
        levelBlocked || coolingDown -> colorScheme.error
        verifying -> colorScheme.onSurfaceVariantSummary
        missing > 0 -> colorScheme.error
        else -> colorScheme.primary
    }
    Surface(color = colorScheme.surface) {
        Column {
            if (error != null) {
                Text(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp),
                    text = error,
                    fontSize = 12.sp,
                    color = colorScheme.error,
                    // 不截断：失败原因里带着承诺串与「去哪改签名」，截掉尾巴等于把
                    // 用户唯一能照做的那半句藏起来。宁可让这条栏长高一点。
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 键盘弹起时这条栏要跟着抬起来，否则「验证身份」会被输入法压住；
                    // 与 navigationBars 取并集，避免两者叠加出两份留白。
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = 10.dp,
                        bottom = 10.dp + WindowInsets.navigationBars.union(WindowInsets.ime)
                            .asPaddingValues().calculateBottomPadding(),
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = hint,
                    fontSize = 12.sp,
                    // 等级不足那句偏长，2 行会被截在后半句；这里给到 3 行。
                    maxLines = 3,
                    color = hintColor,
                )
                Button(
                    onClick = onVerify,
                    // 等级不足与冷却期间都点不动：前者要用户先去升等级，后者是服务端
                    // 明确要求「过一会儿再来」，让按钮可点只会再吃一次同样的失败。
                    enabled = state.canVerify && !levelBlocked && !coolingDown,
                    modifier = Modifier.height(48.dp),
                    cornerRadius = 12.dp,
                ) {
                    if (verifying) {
                        CircularProgressIndicator(
                            progress = null,
                            size = 18.dp,
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        text = stringResource(
                            if (verifying) R.string.module_upload_identity_verifying
                            else R.string.module_upload_identity_verify,
                        ),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            // 「我已升级，重新验证」只有在真被等级拦过时才出现：它是用户的自证入口，
            // 平时摆着会让人以为验证失败是因为等级。
            if (levelBlocked) {
                TextButton(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 12.dp, bottom = 6.dp),
                    text = stringResource(R.string.module_upload_identity_level_recheck),
                    onClick = onClearLevelBlock,
                )
            }
        }
    }
}

/**
 * 承诺卡里的一条：圆点 + 一行正文。
 *
 * 用行内圆点而不是 01/02/03 编号：这套文案要出四语言，编号没有语义；
 * 「三条并列」这件事由圆点和行距就能说清，不必借标签。
 */
@Composable
private fun PledgeLineMiuix(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "·",
            fontSize = 13.sp,
            color = colorScheme.primary,
        )
        Text(
            modifier = Modifier.padding(start = 8.dp),
            text = text,
            fontSize = 13.sp,
            color = colorScheme.onSurface,
        )
    }
}
