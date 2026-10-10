package com.sukisu.ultra.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class BackupLimitsTest {

    @Test
    fun `text within the limit is read whole`() {
        val body = "backup manifest".toByteArray()

        assertEquals(
            "backup manifest",
            ByteArrayInputStream(body).readBoundedText(BackupLimits.MAX_TEXT_BYTES, "index.json"),
        )
    }

    @Test
    fun `text over the limit is refused as an unreadable index`() {
        // 报 IndexUnreadable 而不是 ReadFailed：文件在、只是不可能是我们要的清单——引擎据此
        // 拒绝把索引整体写回去，而不是拿半份内容覆盖掉它。
        val error = assertThrows(BackupReasonException::class.java) {
            ByteArrayInputStream(ByteArray(4097)).readBoundedText(4096, "index.json")
        }

        assertTrue("expected IndexUnreadable but got ${error.reason}", error.reason is BackupReason.IndexUnreadable)
    }

    @Test
    fun `the cap is eight mebibytes`() {
        // 上限本身也是行为的一部分：调大它等于允许更大的内存尖峰，调小它等于把正常索引挡在门外。
        assertEquals(8L * 1024 * 1024, BackupLimits.MAX_TEXT_BYTES)
    }
}
