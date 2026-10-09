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
        assertEquals(DeterministicZip.EPOCH_MS, DeterministicZip.newEntry("module.prop").time)
    }

    @Test
    fun `different content still produces different bytes`() {
        // 固定时间不等于"什么内容都算一样"：内容变了必须算得出来，否则去重会把新版本当成旧的。
        val first = zip(listOf("module.prop" to "id=demo\nversion=1".toByteArray()))
        val second = zip(listOf("module.prop" to "id=demo\nversion=2".toByteArray()))

        assertFalse(first.contentEquals(second))
    }
}
