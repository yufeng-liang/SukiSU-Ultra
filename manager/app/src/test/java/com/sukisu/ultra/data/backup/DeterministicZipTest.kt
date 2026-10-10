package com.sukisu.ultra.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipOutputStream

/**
 * 归档字节的稳定性。
 *
 * 整套去重（`DuplicatePolicy`：内容没变就跳过、不重传）都建立在"同一份内容打出来的字节一样"
 * 这个前提上。打包本身要 root（`SuFile`）进不了单测，所以这里盯住的是它的那个前提：条目时间
 * 固定、顺序固定。
 */
class DeterministicZipTest {

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(DeterministicZip.newEntry(name))
                zip.write(body)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `the same content twice produces the same bytes`() {
        val entries = listOf(
            "module.prop" to "id=demo".toByteArray(),
            "system/" to ByteArray(0),
            "system/bin/demo" to "#!/system/bin/sh\n".toByteArray(),
        )

        // 时间那一项由下一条用例盯着（zip 的时间字段只有 2 秒精度，靠 sleep 是测不准的）。
        assertArrayEquals(zip(entries), zip(entries))
    }

    @Test
    fun `every entry carries the fixed time`() {
        // 这条才是真正的守卫：条目时间一旦回到"当前时刻"，sha256 每次都不同，去重就全废了。
        // 按时区补偿后，entry.time 折回本地时间恒为 1980-01-01T00:00——
        // 不能直接拿 EPOCH_MS 比：那是个 UTC 常量，而 entry.time 是本地 wall clock。
        val entry = DeterministicZip.newEntry("module.prop")
        assertEquals(
            DeterministicZip.expectedLocalDateTime,
            java.time.Instant.ofEpochMilli(entry.time)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime(),
        )
    }

    @Test
    fun `different content still produces different bytes`() {
        // 固定时间不等于"什么内容都算一样"：内容变了必须算得出来，否则去重会把新版本当成旧的。
        val first = zip(listOf("module.prop" to "id=demo\nversion=1".toByteArray()))
        val second = zip(listOf("module.prop" to "id=demo\nversion=2".toByteArray()))

        assertFalse(first.contentEquals(second))
    }

    // ── 时区无关 ────────────────────────────────────────────────────────────

    @Test
    fun `the entry time is the dos epoch no matter the default timezone`() {
        // 这条守的是"西半球也打得出来"：`setTime` 接的是本地 wall clock，
        // UTC 以西的时区折算回 DOS 纪元之前，条目会退化成另一种表示，连长度都可能变。
        val original = java.util.TimeZone.getDefault()
        try {
            for (id in listOf("UTC", "Asia/Shanghai", "America/Los_Angeles", "Pacific/Kiritimati")) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(id))
                val entry = DeterministicZip.newEntry("module.prop")
                assertEquals(
                    "时区 $id 下折算回本地不再是 1980-01-01T00:00",
                    DeterministicZip.expectedLocalDateTime,
                    java.time.Instant.ofEpochMilli(entry.time)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDateTime(),
                )
            }
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }

    @Test
    fun `the same content produces the same bytes in any timezone`() {
        // 与上面那条互补：直接盯字节。投稿用的 <sha256>.zip 判重也吃这个结果
        // （Worker 拿 sha256 做 asset 名），字节漂一点就是一次重复上传。
        val entries = listOf(
            "module.prop" to "id=demo".toByteArray(),
            "system/" to ByteArray(0),
            "system/bin/demo" to "#!/system/bin/sh\n".toByteArray(),
        )
        val original = java.util.TimeZone.getDefault()
        try {
            val inShanghai = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            java.util.TimeZone.setDefault(inShanghai)
            val east = zip(entries)
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
            val west = zip(entries)
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            val utc = zip(entries)

            assertArrayEquals("东八区与 UTC 打出的字节不同", utc, east)
            assertArrayEquals("西八区与 UTC 打出的字节不同", utc, west)
            assertEquals("西半球条目长度都变了", utc.size, west.size)
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }
}
