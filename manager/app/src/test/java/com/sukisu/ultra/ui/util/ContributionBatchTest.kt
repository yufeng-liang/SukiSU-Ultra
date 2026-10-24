package com.sukisu.ultra.ui.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 批量投稿的纯 JVM 钉子。
 *
 * 这里的三条 v2 黄金值是用 Node 的 `crypto` 按 Worker `proof.ts` 同一算法独立算出来的，
 * 不是从 Kotlin 反推的——否则测试只是在证明「Kotlin 等于 Kotlin」。
 */
class ContributionBatchTest {

    private val shaA = "a".repeat(64)
    private val shaB = "b".repeat(64)
    private val shaC = "c".repeat(64)

    private fun item(id: String, sha: String) = ProofItem(id, sha)

    @Test
    fun `v2 golden - single item`() {
        val items = listOf(item("demo", shaA))
        assertEquals("04b95a88fc09", ContributionBatch.proofPrefixV2(items))
        assertEquals("04b95a88fc09-abc123", ContributionBatch.proofTextV2(items, "abc123"))
    }

    @Test
    fun `v2 golden - two items ignores input order`() {
        val items = listOf(item("demo", shaA), item("alpha", shaB))
        assertEquals("8b33dae199af", ContributionBatch.proofPrefixV2(items))
        assertEquals("8b33dae199af-abc123", ContributionBatch.proofTextV2(items, "abc123"))
        // 反过来喂同一套，前缀必须一样。
        assertEquals(
            "8b33dae199af",
            ContributionBatch.proofPrefixV2(items.reversed()),
        )
    }

    @Test
    fun `v2 golden - three items with a Chinese id`() {
        val items = listOf(item("demo", shaA), item("alpha", shaB), item("中文模块", shaC))
        val canon = ContributionBatch.canonicalProofSet(items)
        assertEquals(
            "alpha:$shaB,demo:$shaA,中文模块:$shaC",
            canon,
        )
        assertEquals("eaa48e138662", ContributionBatch.proofPrefixV2(items))
        assertEquals("eaa48e138662-abc123", ContributionBatch.proofTextV2(items, "abc123"))
        // 输入顺序不能改变结果（排序是这套的一部分）。
        assertEquals(
            "eaa48e138662",
            ContributionBatch.proofPrefixV2(items.reversed()),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `canonical set rejects empty`() {
        ContributionBatch.canonicalProofSet(emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `canonical set rejects bad sha`() {
        ContributionBatch.canonicalProofSet(listOf(item("demo", "A".repeat(64))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `canonical set rejects duplicate id`() {
        ContributionBatch.canonicalProofSet(listOf(item("demo", shaA), item("demo", shaB)))
    }

    @Test
    fun `manifest carries version and per-module fields`() {
        val modules = listOf(
            batchZip("demo", "a.zip", shaA.uppercase(), "https://www.coolapk.com/feed/1"),
            batchZip("other", "b.zip", shaB, "https://www.coolapk.com/feed/2"),
        )
        val json = JSONObject(ContributionBatch.buildManifestJson(modules))
        assertEquals("batch-v1", json.getString("version"))
        val arr = json.getJSONArray("modules")
        assertEquals(2, arr.length())
        val first = arr.getJSONObject(0)
        assertEquals("demo", first.getString("id"))
        assertEquals(shaA, first.getString("sha256")) // 声明值统一小写
        assertEquals("https://www.coolapk.com/feed/1", first.getString("source"))
        assertEquals(7L, first.getLong("versionCode"))
        assertEquals("Demo", first.getString("name"))
        assertEquals("1.0", first.getString("version"))
        assertEquals("me", first.getString("author"))
        assertEquals("desc", first.getString("description"))
    }

    @Test
    fun `multipart puts manifest first and zips in manifest order`() {
        val modules = listOf(
            batchZip("demo", "a.zip", shaA, "https://www.coolapk.com/feed/1"),
            batchZip("other", "b.zip", shaB, "https://www.coolapk.com/feed/2"),
        )
        val body = ModuleContribution.buildBatchBody(modules, "nick", "1000", "abc123")
        val dispositions = body.parts.map { part ->
            part.headers?.get("Content-Disposition").orEmpty()
        }
        // 共享字段恒在顶层，形状不随登录状态变：manifest 必须第一，session 恒发
        // （空串也发），zip 按 manifest 顺序排在最后。
        assertEquals(8, dispositions.size)
        assertTrue(dispositions[0].contains("name=\"manifest\""))
        assertTrue(dispositions[1].contains("name=\"uploader\""))
        assertTrue(dispositions[2].contains("name=\"coolapkUid\""))
        assertTrue(dispositions[3].contains("name=\"pledge\""))
        assertTrue(dispositions[4].contains("name=\"nonce\""))
        assertTrue(dispositions[5].contains("name=\"session\""))
        assertTrue(dispositions[6].contains("name=\"zip\""))
        assertTrue(dispositions[6].contains("filename=\"a.zip\""))
        assertTrue(dispositions[7].contains("name=\"zip\""))
        assertTrue(dispositions[7].contains("filename=\"b.zip\""))
    }

    @Test
    fun `multipart carries the session token when one is given`() {
        val modules = listOf(
            batchZip("demo", "a.zip", shaA, "https://www.coolapk.com/feed/1"),
        )
        val token = "a".repeat(64)
        val body = ModuleContribution.buildBatchBody(modules, "nick", "1000", "abc123", token)
        val session = body.parts[5].body?.let { okio.Buffer().also { buf -> it.writeTo(buf) }.readUtf8() }.orEmpty()
        assertEquals(token, session)
        // manifest 仍是第一个 part —— 令牌在场也不能把它挤到后面。
        assertTrue(body.parts[0].headers?.get("Content-Disposition").orEmpty().contains("name=\"manifest\""))
    }

    @Test
    fun `local gate blocks when limits are unknown`() {
        assertEquals(
            "暂时拿不到服务端上限，先不能提交",
            ContributionBatch.localRejection(listOf(batchZip("demo", "a.zip", shaA, "https://www.coolapk.com/feed/1")), null),
        )
    }

    @Test
    fun `local gate treats zero limits as unknown`() {
        // 0 不是「不限」：三个上限里任何一个没读到，大小闸就等于被拆掉，必须先拦住。
        val modules = listOf(batchZip("demo", "a.zip", shaA, "https://www.coolapk.com/feed/1"))
        for (limits in listOf(
            BatchLimits(maxModules = 8, maxBytes = 0, maxTotalBytes = 1000),
            BatchLimits(maxModules = 8, maxBytes = 100, maxTotalBytes = 0),
            BatchLimits(maxModules = 0, maxBytes = 100, maxTotalBytes = 1000),
        )) {
            assertEquals(
                "暂时拿不到服务端上限，先不能提交",
                ContributionBatch.localRejection(modules, limits),
            )
        }
    }

    @Test
    fun `local gate blocks too many modules`() {
        val modules = (0..2).map { i ->
            batchZip("m$i", "m$i.zip", shaA, "https://www.coolapk.com/feed/$i")
        }
        val limits = BatchLimits(maxModules = 2, maxBytes = 100, maxTotalBytes = 1000)
        assertEquals(
            "一次最多选 2 枚；请拆成两批",
            ContributionBatch.localRejection(modules, limits),
        )
    }

    @Test
    fun `local gate blocks a single oversized zip`() {
        val big = batchZip("demo", "big.zip", shaA, "https://www.coolapk.com/feed/1", size = 20)
        val limits = BatchLimits(maxModules = 8, maxBytes = 10, maxTotalBytes = 1000)
        assertTrue(ContributionBatch.localRejection(listOf(big), limits)!!.contains("单枚上限"))
    }

    @Test
    fun `local gate blocks total over limit`() {
        val a = batchZip("a", "a.zip", shaA, "https://www.coolapk.com/feed/1", size = 10)
        val b = batchZip("b", "b.zip", shaB, "https://www.coolapk.com/feed/2", size = 10)
        val limits = BatchLimits(maxModules = 8, maxBytes = 100, maxTotalBytes = 15)
        assertTrue(ContributionBatch.localRejection(listOf(a, b), limits)!!.contains("合计超过"))
    }

    @Test
    fun `local gate blocks duplicate ids and blank source`() {
        val limits = BatchLimits(maxModules = 8, maxBytes = 100, maxTotalBytes = 1000)
        val dup = listOf(
            batchZip("same", "a.zip", shaA, "https://www.coolapk.com/feed/1"),
            batchZip("same", "b.zip", shaB, "https://www.coolapk.com/feed/2"),
        )
        assertTrue(ContributionBatch.localRejection(dup, limits)!!.contains("同一个模块 id"))
        val blank = listOf(batchZip("demo", "a.zip", shaA, " "))
        assertEquals("请填写每一枚的出处链接", ContributionBatch.localRejection(blank, limits))
    }

    @Test
    fun `local gate passes a clean batch`() {
        val modules = listOf(batchZip("demo", "a.zip", shaA, "https://www.coolapk.com/feed/1"))
        val limits = BatchLimits(maxModules = 8, maxBytes = 100, maxTotalBytes = 1000)
        assertNull(ContributionBatch.localRejection(modules, limits))
    }

    @Test
    fun `error copy maps stable codes and falls back to status`() {
        // 用户可见文案里不再有「口令」这个词：它对用户没有意义（用户改的是酷安签名），
        // 而同一屏里其它文案（module_upload_proof_*）一律说「签名」，两种叫法并存会让
        // 用户以为要改的是两个地方。断言跟着改，语义（哪个 code 映射到哪句）不变。
        assertEquals(
            "这串酷安签名已经被用过；已换了一串新的，请把新的写进酷安签名后提交。",
            ContributionBatch.errorCopy(403, "proof_used", null),
        )
        assertTrue(ContributionBatch.errorCopy(413, "zip_too_large", null).contains("单枚上限"))
        assertTrue(ContributionBatch.errorCopy(429, null, null).contains("太频繁"))
        assertTrue(ContributionBatch.errorCopy(502, null, null).contains("服务端没能处理"))
        assertEquals(
            "服务端拒绝了这次上传：签名里没有这次口令",
            ContributionBatch.errorCopy(403, null, "签名里没有这次口令"),
        )
    }

    @Test
    fun `error copy carries the server limits when known`() {
        val limits = BatchLimits(
            maxModules = 8,
            maxBytes = 8L * 1024 * 1024,
            maxTotalBytes = 64L * 1024 * 1024,
        )
        assertTrue(
            ContributionBatch.errorCopy(413, "zip_too_large", null, limits).contains("8 MiB"),
        )
        assertTrue(
            ContributionBatch.errorCopy(413, "batch_too_large", null, limits).contains("64 MiB"),
        )
        assertTrue(
            ContributionBatch.errorCopy(400, "too_many_modules", null, limits).contains("8 枚"),
        )
        // 拿不到上限时不编数字：只说超过上限。
        assertTrue(
            ContributionBatch.errorCopy(413, "zip_too_large", null, null)
                .let { it.contains("上限") && !it.contains("MiB") },
        )
    }

    @Test
    fun `a rejected 2xx without a reason is not reported as HTTP 200`() {
        val copy = ContributionBatch.errorCopy(200, null, null)
        assertTrue(copy.contains("服务端拒绝了这次上传"))
        assertTrue(!copy.contains("HTTP 200"))
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun batchZip(
        id: String,
        name: String,
        sha: String,
        source: String,
        size: Int = 4,
    ): BatchZip {
        val dir = Files.createTempDirectory("batch-test").toFile()
        val file = File(dir, name)
        file.writeBytes(ByteArray(size))
        return BatchZip(
            file = file,
            meta = ModulePropInfo(
                id = id,
                name = "Demo",
                version = "1.0",
                versionCode = 7L,
                author = "me",
                description = "desc",
            ),
            sha256 = sha,
            source = source,
        )
    }
}
