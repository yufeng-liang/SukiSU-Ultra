package com.sukisu.ultra.ui.screen.upload

import com.sukisu.ultra.ui.util.CheckStatus
import com.sukisu.ultra.ui.util.UpdateEligibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收录查询的短期缓存。
 *
 * 这里锁三件事，每一件写错都不是"多问一次"，而是给人一个错的结论：
 * - **过期就不认**：半小时前那句"未收录"再拿出来用，会让用户把同一版重复投一遍；
 * - **换版本就作废**：键是 (id, versionCode)，模块升级后上一版的结论不能顺延；
 * - **坏数据只丢自己**：手改、降级、断电写坏，都不该让整份缓存失效。
 *
 * 时间一律显式传入（[ModuleUploadCheckCache.decode] 的 `now`），不依赖真实时钟。
 */
class ModuleUploadCheckCacheTest {

    private val ttl = ModuleUploadCheckCache.TTL_MS

    private fun entry(
        versionCode: Long = 100L,
        status: CheckStatus = CheckStatus.RECORDED,
        uid: String = "10086001",
        eligibility: UpdateEligibility = UpdateEligibility.MINE,
        reason: String = "",
        at: Long = 1_000L,
    ) = ModuleUploadCheckCache.Entry(versionCode, status, uid, eligibility, reason, at)

    @Test
    fun `a round trip keeps every field`() {
        val source = mapOf(
            "module_a" to entry(reason = "已被 coolapk:1 收录"),
            "module_b" to entry(
                versionCode = 7L,
                status = CheckStatus.NEW,
                uid = "",
                eligibility = UpdateEligibility.UNKNOWN,
            ),
        )
        val decoded = ModuleUploadCheckCache.decode(ModuleUploadCheckCache.encode(source), now = 2_000L)
        assertEquals(source, decoded)
    }

    @Test
    fun `entries older than the ttl are dropped`() {
        val blob = ModuleUploadCheckCache.encode(mapOf("module_a" to entry(at = 1_000L)))
        // 差一毫秒到期：还认。
        assertEquals(1, ModuleUploadCheckCache.decode(blob, now = 1_000L + ttl - 1).size)
        // 正好到期：不认。
        assertTrue(ModuleUploadCheckCache.decode(blob, now = 1_000L + ttl).isEmpty())
        assertTrue(ModuleUploadCheckCache.decode(blob, now = 1_000L + ttl * 10).isEmpty())
    }

    @Test
    fun `a clock that jumps backwards keeps the entry instead of wiping everything`() {
        // 校时/时区抖动会让 now 早于 at。让它继续有效，而不是把所有用户同时赶去重查一遍。
        val blob = ModuleUploadCheckCache.encode(mapOf("module_a" to entry(at = 10_000L)))
        assertEquals(1, ModuleUploadCheckCache.decode(blob, now = 9_000L).size)
    }

    @Test
    fun `the ttl itself is the documented half hour`() {
        assertEquals(30 * 60 * 1000L, ttl)
        assertTrue(ModuleUploadCheckCache.isFresh(1L, 1L))
        assertFalse(ModuleUploadCheckCache.isFresh(0L, 1L))
    }

    @Test
    fun `broken blobs degrade to an empty cache`() {
        assertTrue(ModuleUploadCheckCache.decode(null, now = 1L).isEmpty())
        assertTrue(ModuleUploadCheckCache.decode("", now = 1L).isEmpty())
        assertTrue(ModuleUploadCheckCache.decode("不是 json", now = 1L).isEmpty())
        assertTrue(ModuleUploadCheckCache.decode("[]", now = 1L).isEmpty())
        // schema 对不上（字段增删之后的老格式）：整份丢弃，不是读一半。
        assertTrue(ModuleUploadCheckCache.decode("""{"schema":0,"entries":[]}""", now = 1L).isEmpty())
    }

    @Test
    fun `one bad entry never takes the others down`() {
        val blob = """
            {"schema":1,"entries":[
              {"id":"good","vc":1,"st":"RECORDED","uid":"1","el":"MINE","rs":"","at":1000},
              {"id":"bad_status","vc":1,"st":"收录了","uid":"1","el":"MINE","rs":"","at":1000},
              {"id":"","vc":1,"st":"NEW","uid":"1","el":"UNKNOWN","rs":"","at":1000},
              {"id":"expired","vc":1,"st":"NEW","uid":"1","el":"UNKNOWN","rs":"","at":1}
            ]}
        """.trimIndent()
        val decoded = ModuleUploadCheckCache.decode(blob, now = 2_000L)
        assertEquals(setOf("good"), decoded.keys)
        // 枚举名不认识的归属退回 UNKNOWN，而不是丢掉整条（收录状态仍然是有效的）。
        val unknownEligibility = """
            {"schema":1,"entries":[{"id":"a","vc":1,"st":"NEW","uid":"1","el":"???","rs":"","at":1000}]}
        """.trimIndent()
        assertEquals(
            UpdateEligibility.UNKNOWN,
            ModuleUploadCheckCache.decode(unknownEligibility, now = 2_000L)["a"]?.eligibility,
        )
    }

    @Test
    fun `the cache is capped and keeps the newest entries`() {
        val many = (1..250).associate { i ->
            "module_$i" to entry(at = i.toLong())
        }
        val kept = ModuleUploadCheckCache.trim(many)
        assertEquals(200, kept.size)
        // 砍掉的是最老的（at 最小的那 50 条）。
        assertNull(kept["module_1"])
        assertNull(kept["module_50"])
        assertEquals(51L, kept["module_51"]?.at)
        assertEquals(250L, kept["module_250"]?.at)
        // 没超上限时原样返回，一条都不动。
        assertEquals(kept.size, ModuleUploadCheckCache.trim(kept).size)
    }
}
