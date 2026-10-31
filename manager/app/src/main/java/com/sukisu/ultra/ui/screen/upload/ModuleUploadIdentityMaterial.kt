package com.sukisu.ultra.ui.screen.upload

import android.content.ClipData
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.component.CoolapkUidState
import com.sukisu.ultra.ui.component.material.ExpressiveScaffold
import com.sukisu.ultra.ui.component.material.TonalCard
import com.sukisu.ultra.ui.component.material.expressiveTopAppBarColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 身份页（Material）：与 [ModuleUploadIdentityMiuix] 同一份状态、同一套判据，只换控件。
 *
 * 这一屏只在**手上没有有效令牌**时出现；核验通过就写下九十天的令牌并回列表，
 * 之后每次进来都看不到它（用户只管填模块信息）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleUploadIdentityMaterial(
    state: ModuleUploadFormState,
    onExit: () -> Unit,
    onUidChange: (String) -> Unit,
    onRetryUid: () -> Unit,
    onConsentChange: (Boolean) -> Unit,
    onRenewCommit: () -> Unit,
    onClearLevelBlock: () -> Unit,
    onVerify: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val commit = state.identityCommit

    ExpressiveScaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.module_upload_identity_title)) },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = expressiveTopAppBarColors(),
            )
        },
        bottomBar = {
            IdentityBarMaterial(
                state = state,
                onVerify = onVerify,
                onClearLevelBlock = onClearLevelBlock,
            )
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp),
            contentPadding = innerPadding,
        ) {
            item {
                // 这是所有用户都要过的第一道门，进来就先说清楚为什么要这些信息。
                // 视觉借表单顶部汇总卡的语言（强调色图标 + 加粗标题），让它读起来
                // 是「这一屏的说明」，而不是又一张待填表。
                TonalCard(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    val accent = if (state.canVerify) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(
                        modifier = Modifier.padding(16.dp, 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            modifier = Modifier.size(20.dp),
                            // 问号而不是「已验证的人」：这一格回答的是「为什么要填这些」，
                            // 用人形图标会读成「这里要填身份」，而它上面那张卡才是填的地方。
                            imageVector = Icons.Outlined.HelpOutline,
                            contentDescription = null,
                            tint = accent,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.module_upload_identity_why_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.module_upload_identity_why_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                TonalCard(modifier = Modifier.padding(top = 20.dp).fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionLabelMaterial(
                            icon = Icons.Filled.VerifiedUser,
                            text = stringResource(R.string.module_upload_section_identity),
                            required = true,
                        )
                        // 头像贴在输入框右侧、勾和「已找到」压在头像下面：验证成功
                        // 不再往下多长一行，下面几张卡不会因为这一行冒出来而整体上跳。
                        val verified = state.uidState as? CoolapkUidState.Verified
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedTextField(
                                value = state.uid,
                                onValueChange = onUidChange,
                                enabled = !state.verifying,
                                label = { Text(stringResource(R.string.module_upload_uid)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                            if (verified != null) {
                                UidFoundBadgeMaterial(
                                    avatarUrl = verified.avatar
                                        .ifBlank { state.session?.avatar.orEmpty() },
                                )
                            }
                        }
                        // 只有「正在查 / 查不到 / 自称已提交」这些插曲才占一行：
                        // 验证成功的信息已经收进右边那一小块了。
                        if (verified == null) {
                            UidStatusRowMaterial(
                                state = state,
                                onRetry = onRetryUid,
                                // 身份页没有「退出验证」——这一屏本来就是因为没有令牌才来的。
                                onSignOut = null,
                            )
                        }
                        Text(
                            text = stringResource(R.string.module_upload_identity_level),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                // 「你的验证串」：抄进酷安签名的那一行。它由本机随机生成、只算它的哈希，
                // 所以签名里公开的只是承诺，别人反推不出验证串，也就领不走你的令牌。
                // 与「要传的包」无关 —— 旧版挑一枚包当锚点那一步正是为了做到这一点，
                // 哈希承诺本身就把这件事解决了。
                TonalCard(modifier = Modifier.padding(top = 20.dp).fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionLabelMaterial(
                            icon = Icons.Filled.VerifiedUser,
                            text = stringResource(R.string.module_upload_identity_commit_title),
                            required = true,
                            // 这一步不是「往框里填字」，是要用户去酷安改签名，所以说「必做」。
                            requiredLabel = stringResource(R.string.module_upload_must_do),
                        )
                        if (commit == null) {
                            Text(
                                text = stringResource(R.string.module_upload_identity_commit_pending),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                        // 长按这串字 = 换一串。界面上不留按钮（那一行本来就挤，
                                        // 而且换串是极少数时刻才需要的事），但也不能没有：这串是
                                        // 公开抄在酷安签名里的，万一被谁抄走抢先换了令牌，用户得有
                                        // 一条自救路径 —— 换一串，旧的承诺立刻作废。
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
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        modifier = Modifier.weight(1f),
                                        text = commit,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    TextButton(onClick = {
                                        scope.launch {
                                            clipboard.setClipEntry(
                                                ClipEntry(ClipData.newPlainText("identity", commit)),
                                            )
                                            copied = true
                                        }
                                    }) {
                                        Text(
                                            stringResource(
                                                if (copied) R.string.module_upload_identity_commit_copied
                                                else R.string.module_upload_identity_commit_copy,
                                            ),
                                        )
                                    }
                                }
                            }
                            Text(
                                text = stringResource(R.string.module_upload_identity_commit_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                TonalCard(modifier = Modifier.padding(top = 20.dp).fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp, 12.dp)) {
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = stringResource(R.string.module_upload_identity_pledge_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            modifier = Modifier.padding(top = 6.dp),
                            text = stringResource(R.string.module_upload_identity_pledge_lead),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PledgeLine(stringResource(R.string.module_upload_identity_pledge_1))
                        PledgeLine(stringResource(R.string.module_upload_identity_pledge_2))
                        PledgeLine(stringResource(R.string.module_upload_identity_pledge_3))
                        Spacer(modifier = Modifier.height(6.dp))
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
                            Checkbox(checked = state.consent, onCheckedChange = null)
                            Text(
                                modifier = Modifier.padding(start = 10.dp),
                                text = stringResource(R.string.module_upload_identity_pledge),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
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
 * 底部常驻栏：与表单页的 SubmitBarMaterial 同一副骨架——左侧一行状态 hint，右侧「验证身份」。
 *
 * hint 三态：验证中是中性句；还差步骤时用错误色数出差几步；条件都齐了换主色催一下。
 * 按钮不满足条件时仍然画着（只是不可点），否则用户看不出还差什么。
 */
@Composable
private fun IdentityBarMaterial(
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
    // 「等级不够」和「刚失败过」都不是用户的错，但都是「这一枪现在打不出去」的原因。
    // 按钮灰着而不说为什么，用户只会一直点；所以把它们做成 hint 的最高优先级，
    // 并且冷却要给秒数 —— 秒数在走，才看得懂「等一会儿」不是搪塞。
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.verifyCooldownUntil) {
        while (state.verifyCooldownUntil > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
        now = System.currentTimeMillis()
    }
    val cooldownLeft = ((state.verifyCooldownUntil - now).coerceAtLeast(0L) + 999L) / 1_000L
    val levelBlocked = state.levelBlockedUid.isNotEmpty() && state.levelBlockedUid == state.uid.trim()
    val blocked = levelBlocked || cooldownLeft > 0L
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column {
            if (error != null) {
                Text(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    // 不截断：失败原因里带着承诺串与「去哪改签名」，截掉尾巴等于把
                    // 用户唯一能照做的那半句藏起来。宁可让这条栏长高一点。
                )
            }
            // 等级不足是用户能自己解决的事（发帖、评论会涨等级），所以必须给一条手动解除的路，
            // 否则这个人就永远停在这一屏 —— 除了卸载重装没有别的出口。解除后不预支判断，
            // 点了还是走一次真实核验。
            if (levelBlocked) {
                TextButton(
                    modifier = Modifier.padding(start = 8.dp),
                    onClick = onClearLevelBlock,
                ) {
                    Text(stringResource(R.string.module_upload_identity_level_recheck))
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 键盘弹起时这条栏跟着抬起来，否则「验证身份」会被输入法压住。
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 10.dp,
                        bottom = 10.dp + WindowInsets.navigationBars.union(WindowInsets.ime)
                            .asPaddingValues().calculateBottomPadding(),
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = when {
                        levelBlocked ->
                            stringResource(R.string.module_upload_identity_level_blocked)
                        cooldownLeft > 0L ->
                            stringResource(R.string.module_upload_identity_cooldown, cooldownLeft)
                        verifying -> stringResource(R.string.module_upload_identity_verifying)
                        missing > 0 -> stringResource(R.string.module_upload_identity_missing, missing)
                        else -> stringResource(R.string.module_upload_identity_ready)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    // 等级不足那句偏长，2 行会被截在后半句；这里给到 3 行。
                    maxLines = 3,
                    color = when {
                        blocked -> MaterialTheme.colorScheme.error
                        verifying -> MaterialTheme.colorScheme.onSurfaceVariant
                        missing > 0 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
                Button(
                    onClick = onVerify,
                    enabled = state.canVerify && !blocked,
                    modifier = Modifier.height(48.dp),
                ) {
                    if (verifying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Text(
                        text = stringResource(
                            if (verifying) R.string.module_upload_identity_verifying
                            else R.string.module_upload_identity_verify,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
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
private fun PledgeLine(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            modifier = Modifier.padding(start = 8.dp),
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 验证通过后落在 UID 输入框右侧的那一小块：头像 + 「✓ 已找到」。
 *
 * 不再画成输入框下面的一整行 —— 那一行一出现就把下面几张卡整体往下推。
 */
@Composable
private fun UidFoundBadgeMaterial(avatarUrl: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        VerifiedAvatar(
            avatarUrl = avatarUrl,
            tint = MaterialTheme.colorScheme.primary,
            placeholder = MaterialTheme.colorScheme.surfaceContainerHighest,
            avatarSize = 36.dp,
            showMark = false,
        )
        Row(
            modifier = Modifier.padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VerifiedMark(tint = MaterialTheme.colorScheme.primary, size = 12.dp)
            Text(
                modifier = Modifier.padding(start = 3.dp),
                text = stringResource(R.string.module_upload_identity_uid_found),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
