package com.sukisu.ultra.ui.component

import com.sukisu.ultra.ui.util.CheckStatus
import com.sukisu.ultra.ui.util.ModulePropInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 投稿入口的展示规则与表单校验。
 *
 * 这里锁的是"用户会不会被打扰"和"用户有没有被知情"两件事的边界：
 * 规则写反，不是少一次投稿，而是每个用户每次安装都收到一次分享提示，
 * 或者文件在他不知情的情况下被传到公开仓库。
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

    // ── 表单的两项必填 ──────────────────────────────────────────────────────

    @Test
    fun `both source and uploader are required`() {
        val meta = meta()
        assertFalse(ContributionForm(meta = meta).requiredFilled)
        assertFalse(ContributionForm(meta = meta, source = "https://a.example").requiredFilled)
        assertFalse(ContributionForm(meta = meta, uploader = "me").requiredFilled)
        assertTrue(
            ContributionForm(meta = meta, source = "https://a.example", uploader = "me").requiredFilled
        )
    }

    @Test
    fun `whitespace alone does not count as filled`() {
        val filled = ContributionForm(source = "   ", uploader = "\t\n", consentChecked = true)
        assertFalse("纯空白不算填了：否则一条空出处就能进审核队列", filled.requiredFilled)
        assertFalse(filled.canSubmit)
    }

    // ── 确认勾选：未勾选不能提交 ────────────────────────────────────────────

    @Test
    fun `submitting is impossible without the consent checkbox`() {
        val filled = ContributionForm(source = "https://a.example", uploader = "me")
        assertFalse("未勾选确认时绝不能提交——这是用户知情的关键", filled.canSubmit)

        val checked = filled.copy(consentChecked = true)
        assertTrue(checked.canSubmit)
    }

    @Test
    fun `the consent checkbox alone never enables submitting`() {
        // 只勾确认、两项必填空着，也不该能提交。
        assertFalse(ContributionForm(consentChecked = true).canSubmit)
    }

    // ── 提交中不可重复提交 ──────────────────────────────────────────────────

    @Test
    fun `a second submit is blocked while one is in flight`() {
        val ready = ContributionForm(
            source = "https://a.example",
            uploader = "me",
            consentChecked = true,
        )
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
        val ready = ContributionForm(
            source = "https://a.example",
            uploader = "me",
            consentChecked = true,
        )
        val failed = ready.copy(
            submitState = ContributionSubmitState.Failed("网络不可用，投稿未完成"),
        )
        assertTrue("失败之后必须能重试", failed.canSubmit)
        assertTrue(failed.isFailed)
        assertFalse(failed.isSubmitting)
        // 成功那一次不能重试：它已经进队列了。
        assertFalse(ready.copy(submitState = ContributionSubmitState.Success("ok", null)).isFailed)
    }

    @Test
    fun `a retry is still blocked while the new attempt is in flight`() {
        // 重试不等于放过连点：重新提交之后照样锁住。
        val failed = ContributionForm(
            source = "https://a.example",
            uploader = "me",
            consentChecked = true,
            submitState = ContributionSubmitState.Failed("投稿超时（8 秒），请稍后再试"),
        )
        val retrying = failed.copy(
            submitState = ContributionSubmitState.Submitting("正在提交…"),
        )
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

        val filled = ContributionForm(source = "https://a.example", uploader = "me")
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
        assertFalse(
            ContributionForm(submitState = ContributionSubmitState.Idle).isFailed
        )
        assertFalse(
            ContributionForm(
                submitState = ContributionSubmitState.Submitting("…")
            ).isFailed
        )
        assertFalse(
            ContributionForm(
                submitState = ContributionSubmitState.Success("ok", null)
            ).isFailed
        )
        assertTrue(
            ContributionForm(
                submitState = ContributionSubmitState.Failed("网络不可用，投稿未完成")
            ).isFailed
        )
    }

    @Test
    fun `a failure carries the reason and never a pr url`() {
        val failed = ContributionForm(
            source = "https://a.example",
            uploader = "me",
            consentChecked = true,
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
        assertEqualsId("test_module", form.meta?.id)
        // 预填只填展示信息，出处与署名必须由用户自己给（那两项是必填的）。
        assertFalse(form.requiredFilled)
    }

    @Test
    fun `an unparsable zip opens an empty form`() {
        val form = contributionFormOf(null)
        assertTrue(form.meta == null)
        assertFalse(form.canSubmit)
    }

    private fun assertEqualsId(expected: String, actual: String?) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
