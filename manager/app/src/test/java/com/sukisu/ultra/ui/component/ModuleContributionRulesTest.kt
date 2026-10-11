package com.sukisu.ultra.ui.component

import com.sukisu.ultra.ui.util.CheckStatus
import com.sukisu.ultra.ui.util.ModulePropInfo
import com.sukisu.ultra.ui.util.makeNonce
import com.sukisu.ultra.ui.util.proofPrefixForId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 投稿入口的展示规则与表单校验。
 *
 * 这里锁的是"用户会不会被打扰"和"用户有没有被知情"两件事的边界：
 * 规则写反，不是少一次投稿，而是每个用户每次安装都收到一次分享提示，
 * 或者文件在他不知情的情况下被传到公开仓库。
 *
 * 身份那一栏从"自由署名"改成"酷安 UID + 核实"之后，多了一条同样要紧的边界：
 * **没核实通过就不能提交**——否则这串数字和原来的自由署名没有区别，
 * 而服务端拿它当追责锚点用。
 */
class ModuleContributionRulesTest {

    private fun meta(
        id: String = "test_module",
        name: String = "Test Module",
        version: String = "1.0",
        versionCode: Long = 1L,
        author: String = "someone",
        description: String = "desc",
    ) = ModulePropInfo(id, name, version, versionCode, author, description)

    /** 一份"该填的都填了、UID 也核实通过了"的表单。 */
    private fun verifiedForm(
        source: String = "https://a.example",
        nickname: String = "酷友",
        consent: Boolean = true,
        submitState: ContributionSubmitState = ContributionSubmitState.Idle,
    ) = ContributionForm(
        meta = meta(),
        source = source,
        uid = "2781460",
        uidState = CoolapkUidState.Verified(nickname),
        consentChecked = consent,
        submitState = submitState,
    )

    // ── 列表按钮的显示 ──────────────────────────────────────────────────────

    @Test
    fun `the contribute button is hidden only for recorded modules`() {
        assertFalse(ModuleContributionRules.showContributeButton(CheckStatus.RECORDED))
        assertTrue(ModuleContributionRules.showContributeButton(CheckStatus.NEW))
        assertTrue(ModuleContributionRules.showContributeButton(CheckStatus.VERSION_UPDATE))
        assertTrue(ModuleContributionRules.showContributeButton(CheckStatus.UNKNOWN))
    }

    @Test
    fun `an unchecked module still shows the button`() {
        // 还没查过时（null）不该把按钮藏起来：那样用户想投稿也找不到入口。
        assertTrue(ModuleContributionRules.showContributeButton(null))
    }

    // ── 安装后弹不弹 snackbar ───────────────────────────────────────────────

    @Test
    fun `only a brand new module pops the share snackbar`() {
        assertTrue(ModuleContributionRules.showShareSnackbar(CheckStatus.NEW))
        assertFalse(ModuleContributionRules.showShareSnackbar(CheckStatus.VERSION_UPDATE))
        assertFalse(ModuleContributionRules.showShareSnackbar(CheckStatus.UNKNOWN))
        assertFalse(ModuleContributionRules.showShareSnackbar(CheckStatus.RECORDED))
    }

    // ── 「无法确认是否已收录」提示 ──────────────────────────────────────────

    @Test
    fun `the unknown hint appears only when the check failed`() {
        assertTrue(ModuleContributionRules.showUnknownHint(CheckStatus.UNKNOWN))
        assertFalse(ModuleContributionRules.showUnknownHint(CheckStatus.NEW))
        assertFalse(ModuleContributionRules.showUnknownHint(CheckStatus.RECORDED))
        assertFalse(ModuleContributionRules.showUnknownHint(null))
    }

    // ── 出处必填 + UID 必须核实通过 ─────────────────────────────────────────

    @Test
    fun `source and a verified uid are both required`() {
        assertFalse("什么都没填", ContributionForm(meta = meta()).requiredFilled)
        assertFalse(
            "只有出处",
            ContributionForm(meta = meta(), source = "https://a.example").requiredFilled,
        )
        assertFalse(
            "填了 UID 但还没核实",
            ContributionForm(meta = meta(), source = "https://a.example", uid = "2781460").requiredFilled,
        )
        assertTrue(verifiedForm().requiredFilled)
    }

    @Test
    fun `a uid that failed verification never counts as filled`() {
        // 四种"没核实通过"的取值，一个都不能放行。
        val notVerified = listOf(
            CoolapkUidState.Checking,
            CoolapkUidState.NotFound,
            CoolapkUidState.Unavailable,
            CoolapkUidState.SelfReported,
            CoolapkUidState.Empty,
        )
        for (state in notVerified) {
            val form = ContributionForm(
                meta = meta(),
                source = "https://a.example",
                uid = "2781460",
                uidState = state,
                consentChecked = true,
            )
            assertFalse("$state 不该被当成填好了", form.requiredFilled)
            assertFalse("$state 不该能提交", form.canSubmit)
        }
    }

    @Test
    fun `the nickname comes from the verified lookup, never from the input box`() {
        // 署名只能来自酷安反查的结果：输入框里的只有 UID。
        assertEquals("酷友", verifiedForm(nickname = "酷友").nickname)
        assertEquals("", ContributionForm(uid = "2781460").nickname)
        assertEquals(
            "",
            ContributionForm(uid = "2781460", uidState = CoolapkUidState.Unavailable).nickname,
        )
    }

    @Test
    fun `whitespace alone does not count as filled`() {
        val blankSource = verifiedForm(source = "   ")
        assertFalse("纯空白不算填了：否则一条空出处就能进审核队列", blankSource.requiredFilled)
        assertFalse(blankSource.canSubmit)
    }

    // ── 确认勾选：未勾选不能提交 ────────────────────────────────────────────

    @Test
    fun `submitting is impossible without the consent checkbox`() {
        val unchecked = verifiedForm(consent = false)
        assertFalse("未勾选确认时绝不能提交——这是用户知情的关键", unchecked.canSubmit)
        assertTrue(unchecked.copy(consentChecked = true).canSubmit)
    }

    @Test
    fun `the consent checkbox alone never enables submitting`() {
        // 只勾确认、出处与 UID 都空着，也不该能提交。
        assertFalse(ContributionForm(consentChecked = true).canSubmit)
    }

    // ── 提交中不可重复提交 ──────────────────────────────────────────────────

    @Test
    fun `a second submit is blocked while one is in flight`() {
        val ready = verifiedForm()
        assertTrue(ready.canSubmit)

        val submitting = ready.copy(submitState = ContributionSubmitState.Submitting("…"))
        assertFalse("连点会把同一个 zip 传两遍，审核队列里出现两条重复条目", submitting.canSubmit)
        assertTrue(submitting.isSubmitting)

        // 结束之后成功不该还能再点一次：那一次已经进审核队列了，再点是重复条目。
        val done = ready.copy(
            submitState = ContributionSubmitState.Success("ok", "https://example.invalid/pr/1")
        )
        assertFalse(done.canSubmit)
        assertFalse(done.isSubmitting)
    }

    @Test
    fun `a failed submit can be retried`() {
        // 失败后必须还能再点一次：网络抖一下就把提交入口永久关死，
        // 等于让用户重新打包 zip、重填必填项——而这次失败服务端很可能根本没收到。
        val failed = verifiedForm(
            submitState = ContributionSubmitState.Failed("网络不可用，投稿未完成"),
        )
        assertTrue("失败之后必须能重试", failed.canSubmit)
        assertTrue(failed.isFailed)
        assertFalse(failed.isSubmitting)
        // 成功那一次不能重试：它已经进队列了。
        assertFalse(
            verifiedForm(submitState = ContributionSubmitState.Success("ok", null)).isFailed
        )
    }

    @Test
    fun `a retry is still blocked while the new attempt is in flight`() {
        // 重试不等于放过连点：重新提交之后照样锁住。
        val retrying = verifiedForm(
            submitState = ContributionSubmitState.Failed("投稿超时（8 秒），请稍后再试"),
        ).copy(submitState = ContributionSubmitState.Submitting("正在提交…"))
        assertFalse(retrying.canSubmit)
        assertTrue(retrying.isSubmitting)
    }

    // ── 「请先勾选确认」的提示时机 ──────────────────────────────────────────

    @Test
    fun `the consent hint waits until the required fields are filled`() {
        // 一打开对话框就挂红字是噪音；填完了才提示，正好接在准备点提交的那一步。
        val empty = ContributionForm(meta = meta())
        assertFalse(empty.consentMissing)
        assertFalse(empty.copy(consentChecked = true).consentMissing)

        val filled = verifiedForm(consent = false)
        assertTrue(filled.consentMissing)
        assertFalse(filled.copy(consentChecked = true).consentMissing)

        // 提交中/已提交都不再提示。
        assertFalse(
            filled.copy(submitState = ContributionSubmitState.Submitting("…")).consentMissing
        )
        assertFalse(
            filled.copy(submitState = ContributionSubmitState.Success("ok", null)).consentMissing
        )
    }

    @Test
    fun `the failed flag is only set on a failure`() {
        assertFalse(ContributionForm().isFailed)
        assertFalse(ContributionForm(submitState = ContributionSubmitState.Idle).isFailed)
        assertFalse(ContributionForm(submitState = ContributionSubmitState.Submitting("…")).isFailed)
        assertFalse(ContributionForm(submitState = ContributionSubmitState.Success("ok", null)).isFailed)
        assertTrue(ContributionForm(submitState = ContributionSubmitState.Failed("网络不可用，投稿未完成")).isFailed)
    }

    @Test
    fun `a failure carries the reason and never a pr url`() {
        val failed = verifiedForm(
            submitState = ContributionSubmitState.Failed("网络不可用，投稿未完成"),
        )
        // 失败之后 canSubmit 重新为真：这一条锁的就是"失败不能把提交入口永久关死"。
        assertTrue(failed.canSubmit)
        val state = failed.submitState as ContributionSubmitState.Failed
        assertTrue(state.message.isNotBlank())
    }

    // ── 预填 ────────────────────────────────────────────────────────────────

    @Test
    fun `the form is prefilled from the parsed module info`() {
        val form = contributionFormOf(meta())
        assertTrue(form.meta != null)
        assertEquals("test_module", form.meta?.id)
        // 预填只填展示信息：出处与 UID 必须由用户自己给（那两项是必填的），
        // UID 更不能替用户预填——那是"这条投稿是谁的"唯一凭据。
        assertFalse(form.requiredFilled)
        assertEquals("", form.uid)
        assertEquals(CoolapkUidState.Empty, form.uidState)
    }

    @Test
    fun `an unparsable zip opens an empty form`() {
        val form = contributionFormOf(null)
        assertTrue(form.meta == null)
        assertFalse(form.canSubmit)
    }

    // ── UID 形状（与 Worker / 网页端同一约定）──────────────────────────────

    @Test
    fun `only 4 to 12 digits count as a well shaped uid`() {
        assertFalse(com.sukisu.ultra.ui.util.ModuleContribution.isValidUid(""))
        assertFalse(com.sukisu.ultra.ui.util.ModuleContribution.isValidUid("123"))
        assertFalse(com.sukisu.ultra.ui.util.ModuleContribution.isValidUid("abc"))
        assertFalse(com.sukisu.ultra.ui.util.ModuleContribution.isValidUid("2781460x"))
        assertFalse(com.sukisu.ultra.ui.util.ModuleContribution.isValidUid("1234567890123"))
        assertTrue(com.sukisu.ultra.ui.util.ModuleContribution.isValidUid("1234"))
        assertTrue(com.sukisu.ultra.ui.util.ModuleContribution.isValidUid("2781460"))
        assertTrue(com.sukisu.ultra.ui.util.ModuleContribution.isValidUid(" 2781460 "))
    }

    // ── 所有权口令：这套算法第三份实现的钉子 ────────────────────────────────
    //
    // proofPrefixForId / makeNonce 与 contrib/worker/src/proof.ts、
    // modules-web/public/scripts/proof.js 是同一算法的三份实现，而跨仓库一致性自检是 JS 的，
    // 结构上够不到 Kotlin —— 这一份今天没人管，漂了要到 App 投稿整批 403 才看得见。
    // 下面几个十六进制值是用 node:crypto 对着 id 的 UTF-8 字节**独立**算出来的，
    // 换成从 Kotlin 实现反推就等于自我证明，这条测试也就不再是钉子了。
    // 它们同时证明 Kotlin 的 `id.toByteArray()` 和 `new TextEncoder().encode(id)` 得到同一串字节。

    @Test
    fun `the proof prefix matches the hashes computed outside this repo`() {
        assertEquals("979315af", proofPrefixForId("test_module"))
        assertEquals("2a97516c", proofPrefixForId("demo"))
    }

    @Test
    fun `a nonce is six lowercase alphanumerics and not a constant`() {
        // 形状必须落在 Worker 的 PROOF_NONCE_RE（/^[a-z0-9]{6}$/）里：
        // 形状不对的话口令在服务端直接被拒，用户那边只看到一句"没在签名里找到口令"。
        val nonceRegex = Regex("^[a-z0-9]{6}$")
        val draws = List(300) { makeNonce() }
        draws.forEach { nonce ->
            assertTrue("nonce 形状不合法：$nonce", nonceRegex.matches(nonce))
        }
        // 抽样里出现同一个值不算异常（36^6 的空间），但"全是同一个"就是它压根没取随机数。
        assertTrue("makeNonce 抽 300 次只有一个值，说明它不是随机的", draws.distinct().size > 1)
    }

    @Test
    fun `a non ascii module id still yields a stable eight hex prefix`() {
        // 仓库里真有中文 id 的模块：非 ASCII 也得正常出前缀，且同一个 id 每次出同一串。
        val id = "系统状态监控"
        val prefix = proofPrefixForId(id)
        assertTrue(prefix, Regex("^[0-9a-f]{8}$").matches(prefix))
        assertEquals(prefix, proofPrefixForId(id))
        // 外部独立算出的黄金值。只有非 ASCII 才验得出两边编码是不是同一套（ASCII 下 UTF-8 与Latin-1 同字节）。
        assertEquals("67e75643", prefix)
    }
}
