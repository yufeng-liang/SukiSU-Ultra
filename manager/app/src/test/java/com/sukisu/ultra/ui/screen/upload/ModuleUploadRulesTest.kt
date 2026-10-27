package com.sukisu.ultra.ui.screen.upload

import com.sukisu.ultra.R
import com.sukisu.ultra.ui.util.BatchLimits
import com.sukisu.ultra.ui.util.CheckStatus
import com.sukisu.ultra.ui.util.UpdateEligibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 上传列表与表单的纯规则。
 *
 * 这里锁四件事，每一件写错都不是"提示不准"，而是让用户做了错事：
 * - 筛选：把「查不了」混进「未收录」会诱导重复上传；
 * - 状态文案：四种状态必须说四件不同的事；
 * - 本地限制：超量/超大在提交前就该拦下，而不是传完再被服务端拒；
 * - 口令失效：集合一变，勾选必须自动失效，否则用户会拿着一串作废的口令提交。
 *
 * 不碰 Android 运行时：只比较资源 id 与纯函数结果（与 BackupTextTest 同一种写法）。
 */
class ModuleUploadRulesTest {

    private fun zip(
        id: String = "module_a",
        sha: String = "a".repeat(64),
        size: Long = 1024L,
        source: String = "https://www.coolapk.com/feed/1",
        eligibility: UpdateEligibility = UpdateEligibility.UNKNOWN,
        eligibilityReason: String = "",
    ) = UploadZip(
        path = "/tmp/$id.zip",
        fileName = "$id.zip",
        id = id,
        name = id,
        version = "1.0",
        versionCode = 1L,
        author = "someone",
        description = "desc",
        sha256 = sha,
        sizeBytes = size,
        source = source,
        eligibility = eligibility,
        eligibilityReason = eligibilityReason,
    )

    private fun row(id: String, name: String = id, status: CheckStatus? = null) =
        ModuleUploadRules.RowView(id = id, name = name, status = status)

    // ── 筛选 ────────────────────────────────────────────────────────────────

    @Test
    fun `not-recorded holds exactly the two uploadable statuses`() {
        assertTrue(ModuleUploadRules.matchesFilter(CheckStatus.NEW, ModuleUploadRules.Filter.NOT_RECORDED))
        assertTrue(
            ModuleUploadRules.matchesFilter(
                CheckStatus.VERSION_UPDATE,
                ModuleUploadRules.Filter.NOT_RECORDED,
            )
        )
        assertFalse(
            ModuleUploadRules.matchesFilter(
                CheckStatus.RECORDED,
                ModuleUploadRules.Filter.NOT_RECORDED,
            )
        )
    }

    @Test
    fun `unknown and not-yet-checked never count as not recorded`() {
        // 「不知道」不是「没有」。混进未收录会让用户把已收录的模块再传一遍。
        assertFalse(
            ModuleUploadRules.matchesFilter(
                CheckStatus.UNKNOWN,
                ModuleUploadRules.Filter.NOT_RECORDED,
            )
        )
        assertFalse(ModuleUploadRules.matchesFilter(null, ModuleUploadRules.Filter.NOT_RECORDED))
        assertTrue(ModuleUploadRules.matchesFilter(null, ModuleUploadRules.Filter.UNKNOWN))
        assertTrue(ModuleUploadRules.matchesFilter(CheckStatus.UNKNOWN, ModuleUploadRules.Filter.UNKNOWN))
    }

    @Test
    fun `all passes everything and recorded is exclusive`() {
        CheckStatus.entries.forEach { status ->
            assertTrue(ModuleUploadRules.matchesFilter(status, ModuleUploadRules.Filter.ALL))
        }
        assertTrue(ModuleUploadRules.matchesFilter(null, ModuleUploadRules.Filter.ALL))
        assertTrue(ModuleUploadRules.matchesFilter(CheckStatus.RECORDED, ModuleUploadRules.Filter.RECORDED))
        assertFalse(ModuleUploadRules.matchesFilter(CheckStatus.NEW, ModuleUploadRules.Filter.RECORDED))
    }

    @Test
    fun `visible rows filter, search and sort by name`() {
        val rows = listOf(
            row("zeta", name = "Zeta", status = CheckStatus.NEW),
            row("alpha", name = "Alpha", status = CheckStatus.RECORDED),
            row("beta", name = "Beta", status = null),
        )

        val sorted = ModuleUploadRules.visibleRows(rows, ModuleUploadRules.Filter.ALL, "")
        assertEquals(listOf("alpha", "beta", "zeta"), sorted.map { it.id })

        val onlyNew = ModuleUploadRules.visibleRows(rows, ModuleUploadRules.Filter.NOT_RECORDED, "")
        assertEquals(listOf("zeta"), onlyNew.map { it.id })

        // 搜索比 id 与名称，且大小写不敏感。
        assertEquals(
            listOf("beta"),
            ModuleUploadRules.visibleRows(rows, ModuleUploadRules.Filter.ALL, "BET").map { it.id },
        )
        assertEquals(
            listOf("zeta"),
            ModuleUploadRules.visibleRows(rows, ModuleUploadRules.Filter.ALL, "zet").map { it.id },
        )
    }

    // ── 状态文案 ────────────────────────────────────────────────────────────

    @Test
    fun `every status has its own title and summary`() {
        val copies = listOf(
            ModuleUploadRules.statusCopy(null),
            ModuleUploadRules.statusCopy(CheckStatus.UNKNOWN),
            ModuleUploadRules.statusCopy(CheckStatus.NEW),
            ModuleUploadRules.statusCopy(CheckStatus.VERSION_UPDATE),
            ModuleUploadRules.statusCopy(CheckStatus.RECORDED),
        )
        assertEquals(5, copies.map { it.title }.toSet().size)
        assertEquals(5, copies.map { it.summary }.toSet().size)
    }

    @Test
    fun `not-checked and unknown are worded differently`() {
        // 一个是「点继续查」，一个是「等网络恢复」：两句不能是同一句。
        val notChecked = ModuleUploadRules.statusCopy(null)
        val unknown = ModuleUploadRules.statusCopy(CheckStatus.UNKNOWN)
        assertNotEquals(notChecked.title, unknown.title)
        assertNotEquals(notChecked.summary, unknown.summary)
        assertEquals(R.string.module_upload_status_not_checked, notChecked.title)
        assertEquals(R.string.module_upload_status_unknown, unknown.title)
    }

    // ── 本地限制 ────────────────────────────────────────────────────────────

    private val limits = BatchLimits(maxModules = 3, maxBytes = 1_000L, maxTotalBytes = 2_000L)

    @Test
    fun `limits pass when everything fits`() {
        assertEquals(
            ModuleUploadRules.LimitProblem.NONE,
            ModuleUploadRules.limitProblem(2, listOf(500L, 600L), limits),
        )
    }

    @Test
    fun `too many modules is caught before upload`() {
        assertEquals(
            ModuleUploadRules.LimitProblem.TOO_MANY,
            ModuleUploadRules.limitProblem(4, listOf(1L, 1L, 1L, 1L), limits),
        )
    }

    @Test
    fun `a single oversized zip is distinguished from a batch that is too large`() {
        assertEquals(
            ModuleUploadRules.LimitProblem.SINGLE_TOO_BIG,
            ModuleUploadRules.limitProblem(2, listOf(1_500L, 100L), limits),
        )
        // 每个都不超单个上限，但加起来超总量：这是另一句话。
        assertEquals(
            ModuleUploadRules.LimitProblem.TOTAL_TOO_BIG,
            ModuleUploadRules.limitProblem(3, listOf(900L, 900L, 900L), limits),
        )
    }

    @Test
    fun `missing limits blocks locally`() {
        // 没取到服务端限制 = 不知道边界：不能拿猜出来的 8 / 8 MiB 放行，
        // 也不能让用户白传一整批再被服务端拒。界面要显示「暂时拿不到上限」。
        assertEquals(
            ModuleUploadRules.LimitProblem.LIMITS_UNKNOWN,
            ModuleUploadRules.limitProblem(999, listOf(Long.MAX_VALUE), null),
        )
    }

    @Test
    fun `zero limits are unknown rather than unlimited`() {
        // 0 不是「不限」：放行等于把大小闸整个拆掉。
        assertEquals(
            ModuleUploadRules.LimitProblem.LIMITS_UNKNOWN,
            ModuleUploadRules.limitProblem(1, listOf(1L), BatchLimits(8, 0, 1_000)),
        )
        assertEquals(
            ModuleUploadRules.LimitProblem.LIMITS_UNKNOWN,
            ModuleUploadRules.limitProblem(1, listOf(1L), BatchLimits(8, 1_000, 0)),
        )
        assertEquals(
            ModuleUploadRules.LimitProblem.LIMITS_UNKNOWN,
            ModuleUploadRules.limitProblem(1, listOf(1L), BatchLimits(0, 1_000, 1_000)),
        )
    }

    @Test
    fun `check chunks follow the server limit with a fallback of eight`() {
        assertEquals(3, ModuleUploadRules.checkChunkSize(limits))
        assertEquals(8, ModuleUploadRules.checkChunkSize(null))
        // 服务端给 0 / 负数属于坏数据：退回兜底值，而不是把待查列表切成 0 条。
        assertEquals(8, ModuleUploadRules.checkChunkSize(BatchLimits(0, 0, 0)))
        assertEquals(200, ModuleUploadRules.CHECK_CAP)
    }

    // ── 出处 ────────────────────────────────────────────────────────────────

    @Test
    fun `source must be a coolapk link`() {
        assertEquals(
            ModuleUploadRules.SourceProblem.OK,
            ModuleUploadRules.sourceProblem("https://www.coolapk.com/feed/123"),
        )
        assertEquals(
            ModuleUploadRules.SourceProblem.OK,
            ModuleUploadRules.sourceProblem("https://coolapk.com/u/10086001"),
        )
        // 酷安复制出来的链接经常没写协议：网页端与 Worker 都会补 https://，App 不能自己拦掉。
        assertEquals(
            ModuleUploadRules.SourceProblem.OK,
            ModuleUploadRules.sourceProblem("www.coolapk.com/feed/123"),
        )
        assertEquals(
            ModuleUploadRules.SourceProblem.OK,
            ModuleUploadRules.sourceProblem("m.coolapk.com/feed/123"),
        )
        assertEquals(
            ModuleUploadRules.SourceProblem.EMPTY,
            ModuleUploadRules.sourceProblem("   "),
        )
        // 后缀相同的冒名域名必须拒掉。
        assertEquals(
            ModuleUploadRules.SourceProblem.NOT_COOLAPK,
            ModuleUploadRules.sourceProblem("https://evil-coolapk.com/feed/1"),
        )
        // 补协议不能把冒名域名也放进来。
        assertEquals(
            ModuleUploadRules.SourceProblem.NOT_COOLAPK,
            ModuleUploadRules.sourceProblem("evil-coolapk.com/feed/1"),
        )
        assertEquals(
            ModuleUploadRules.SourceProblem.NOT_COOLAPK,
            ModuleUploadRules.sourceProblem("https://example.com/coolapk.com"),
        )
    }

    // ── 口令 ────────────────────────────────────────────────────────────────

    @Test
    fun `proof needs every sha and id`() {
        assertTrue(ModuleUploadRules.proofReady(listOf(zip("a"), zip("b"))))
        assertFalse(ModuleUploadRules.proofReady(emptyList()))
        assertFalse(ModuleUploadRules.proofReady(listOf(zip("a"), zip("b", sha = ""))))
        assertFalse(ModuleUploadRules.proofReady(listOf(zip(""))))
    }

    @Test
    fun `a malformed sha or duplicate id never reaches the hasher`() {
        // proofReady 是组合里求值的前置判据：canonicalProofSet 对坏形状是 require 抛异常，
        // 放进去就是整屏崩掉，而不是「口令还没算完」。
        assertFalse(ModuleUploadRules.proofReady(listOf(zip("a", sha = "A".repeat(64)))))
        assertFalse(ModuleUploadRules.proofReady(listOf(zip("a", sha = "a".repeat(63)))))
        assertFalse(ModuleUploadRules.proofReady(listOf(zip("a"), zip("a", sha = "b".repeat(64)))))
    }

    @Test
    fun `one passphrase covers the whole batch`() {
        val items = listOf(zip("a"), zip("b"), zip("c"))
        val proof = ModuleUploadRules.proofSnapshot(items, "abc123")

        // 契约：整批只有一串 19 个字符（12 位前缀 + "-" + 6 位 nonce）。
        assertEquals(ModuleUploadRules.PROOF_LENGTH, proof.text.length)
        assertEquals(1, proof.text.count { it == '-' })
        assertTrue(proof.text.none { it.isWhitespace() })
    }

    @Test
    fun `proof is stable regardless of the order items were picked`() {
        val a = zip("a")
        val b = zip("b")
        assertEquals(
            ModuleUploadRules.proofSnapshot(listOf(a, b), "abc123"),
            ModuleUploadRules.proofSnapshot(listOf(b, a), "abc123"),
        )
    }

    @Test
    fun `changing an id, a sha or the nonce invalidates the passphrase`() {
        val base = ModuleUploadRules.proofSnapshot(listOf(zip("a"), zip("b")), "abc123")

        assertNotEquals(
            base.text,
            ModuleUploadRules.proofSnapshot(listOf(zip("a"), zip("c")), "abc123").text,
        )
        assertNotEquals(
            base.text,
            ModuleUploadRules.proofSnapshot(
                listOf(zip("a"), zip("b", sha = "b".repeat(64))),
                "abc123",
            ).text,
        )
        assertNotEquals(
            base.text,
            ModuleUploadRules.proofSnapshot(listOf(zip("a"), zip("b")), "zzz999").text,
        )
    }

    @Test
    fun `signature confirmation dies with the batch`() {
        val confirmed = ModuleUploadRules.proofSnapshot(listOf(zip("a")), "abc123").setKey
        val sameSet = ModuleUploadRules.proofSnapshot(listOf(zip("a")), "abc123").setKey
        val changedSet = ModuleUploadRules.proofSnapshot(listOf(zip("a"), zip("b")), "abc123").setKey

        assertTrue(ModuleUploadRules.signatureStillValid(confirmed, sameSet))
        // 集合变了（或换了 nonce 之后用户还没重抄）：勾选必须失效。
        assertFalse(ModuleUploadRules.signatureStillValid(confirmed, changedSet))
        assertFalse(ModuleUploadRules.signatureStillValid(null, sameSet))
        assertFalse(ModuleUploadRules.signatureStillValid("", sameSet))
    }

    // ── 提交闸门 ────────────────────────────────────────────────────────────

    @Test
    fun `submit needs every gate`() {
        val defaultItems = listOf(zip("a"))
        fun can(
            items: List<UploadZip> = defaultItems,
            uid: Boolean = true,
            consent: Boolean = true,
            signature: Boolean = true,
            submitting: Boolean = false,
            limit: ModuleUploadRules.LimitProblem = ModuleUploadRules.LimitProblem.NONE,
        ) = ModuleUploadRules.canSubmit(items, uid, consent, signature, submitting, limit)

        assertTrue(can())
        assertFalse(can(uid = false))
        assertFalse(can(consent = false))
        assertFalse(can(signature = false))
        assertFalse(can(submitting = true))
        assertFalse(can(limit = ModuleUploadRules.LimitProblem.TOO_MANY))
        assertFalse(can(items = emptyList()))
        // 出处没填 / 不是酷安链接：同样不放行。
        assertFalse(can(items = listOf(zip("a", source = ""))))
        assertFalse(can(items = listOf(zip("a", source = "https://example.com"))))
        // 一个包没算完 sha 也不行。
        assertFalse(can(items = listOf(zip("a"), zip("b", sha = ""))))
        // 手选包的收录状态还没回来时先别放行；已经收录的那一版不重复投。
        assertFalse(can(items = listOf(zip("a").copy(checking = true))))
        assertFalse(can(items = listOf(zip("a").copy(status = CheckStatus.RECORDED))))
        assertTrue(can(items = listOf(zip("a").copy(status = CheckStatus.NEW))))
    }

    @Test
    fun `an upload the server says belongs to someone else never reaches submit`() {
        fun can(items: List<UploadZip>) = ModuleUploadRules.canSubmit(
            items = items,
            uidVerified = true,
            consent = true,
            signatureValid = true,
            submitting = false,
            limitProblem = ModuleUploadRules.LimitProblem.NONE,
        )

        // 第七条门：服务端明确判给别人时，本地就该拦住 —— 否则用户传完几百 MB 才吃 403。
        assertFalse(can(listOf(zip("a").copy(eligibility = UpdateEligibility.NOT_MINE))))
        // 明确是自己的、或者根本没问出来（UNKNOWN），都照常放行。
        // UNKNOWN 不许拦：它是「不知道」不是「不是你的」，索引抖动时拦了会让谁都没法提交。
        assertTrue(can(listOf(zip("a").copy(eligibility = UpdateEligibility.MINE))))
        assertTrue(can(listOf(zip("a").copy(eligibility = UpdateEligibility.UNKNOWN))))
        // 一批里只要有一枚是别人的，整批不放行。
        assertFalse(
            can(
                listOf(
                    zip("a").copy(eligibility = UpdateEligibility.MINE),
                    zip("b").copy(eligibility = UpdateEligibility.NOT_MINE),
                )
            )
        )
    }

    @Test
    fun `ownership block reason quotes the server and never invents one`() {
        val serverSays = "这个模块由 coolapk:1 收录，只有原作者能发更新版本"
        // 服务端有原话就原样交给界面，本地不替它编原因。
        assertEquals(
            serverSays,
            ModuleUploadRules.ownershipBlockReason(
                listOf(
                    zip("a").copy(
                        eligibility = UpdateEligibility.NOT_MINE,
                        eligibilityReason = serverSays,
                    )
                )
            ),
        )
        // 服务端没给话（纯空白也算没给）：回落本地兜底句，绝不能返回空串让界面显示一片空白。
        val fallback = ModuleUploadRules.ownershipBlockReason(
            listOf(zip("a").copy(eligibility = UpdateEligibility.NOT_MINE, eligibilityReason = "   "))
        )
        assertTrue("兜底句不能说空话", fallback.isNotBlank())
        assertTrue("兜底句要讲清楚为什么被拦", fallback.contains("原作者"))
        // 没有归属问题时不该有理由 —— 界面据此决定这一行显示不显示。
        assertEquals("", ModuleUploadRules.ownershipBlockReason(listOf(zip("a"))))
        // MINE 即使带着一句残留的原话，也不该被界面当成「被拦」的理由。
        assertEquals(
            "",
            ModuleUploadRules.ownershipBlockReason(
                listOf(zip("a").copy(eligibility = UpdateEligibility.MINE, eligibilityReason = serverSays))
            ),
        )
    }

    // ── 杂项 ────────────────────────────────────────────────────────────────

    @Test
    fun `uid input keeps digits only and at most twelve`() {
        assertEquals("10086001", ModuleUploadPrefs.sanitizeUid(" 1008-6001 "))
        assertEquals("123456789012", ModuleUploadPrefs.sanitizeUid("123456789012345"))
        assertEquals("", ModuleUploadPrefs.sanitizeUid("abc"))
    }

    @Test
    fun `byte sizes are readable`() {
        // 与 formatFileSize 同一套单位（KB/MB，不是 KiB/MiB）：界面上「包大小」与
        // 「留存占用」并排显示，两套单位会让用户以为说的是不同东西。
        assertEquals("512 B", ModuleUploadRules.formatBytes(512))
        assertEquals("1 KB", ModuleUploadRules.formatBytes(1024))
        assertEquals("2.0 MB", ModuleUploadRules.formatBytes(2L * 1024 * 1024))
        assertEquals("1.0 GB", ModuleUploadRules.formatBytes(1024L * 1024 * 1024))
    }

    @Test
    fun `byte sizes keep a dot decimal separator in every locale`() {
        // 体积数字要跨语言可比：德语/法语默认 locale 会把小数点写成逗号。
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("2.0 MB", ModuleUploadRules.formatBytes(2L * 1024 * 1024))
            Locale.setDefault(Locale.FRANCE)
            assertEquals("2.0 MB", ModuleUploadRules.formatBytes(2L * 1024 * 1024))
        } finally {
            Locale.setDefault(original)
        }
    }
}
