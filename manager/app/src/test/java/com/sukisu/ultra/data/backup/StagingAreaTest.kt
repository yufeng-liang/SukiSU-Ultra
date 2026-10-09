package com.sukisu.ultra.data.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class StagingAreaTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val sixHours = 6 * 60 * 60 * 1000L

    @Test
    fun `stale files are removed and fresh files are kept`() {
        val area = StagingArea(temp.root, staleAfterMs = sixHours)
        val stale = area.file("old.bin").apply { writeText("x") }
        val fresh = area.file("new.bin").apply { writeText("x") }
        stale.setLastModified(System.currentTimeMillis() - 7 * 60 * 60 * 1000L)

        area.cleanupStale()

        assertFalse(stale.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `a relative path cannot escape the staging directory`() {
        val area = StagingArea(temp.root)
        assertEquals(area.root, area.file("../../evil.bin").parentFile)
    }

    @Test
    fun `copy returns the byte count`() = runBlocking {
        val payload = ByteArray(4096) { 7 }
        val written = StagingArea(temp.root)
            .copyCancellable(ByteArrayInputStream(payload), ByteArrayOutputStream())
        assertEquals(4096L, written)
    }

    @Test
    fun `closing the stream deletes the staged copy`() {
        val area = StagingArea(temp.root)
        val staged = area.file("copy.bin").apply { writeText("payload") }

        val read = area.openAndDelete(staged).use { it.readBytes().decodeToString() }

        assertEquals("payload", read)
        assertFalse("a staged copy must not outlive its stream", staged.exists())
    }

    @Test
    fun `copy aborts when the coroutine is cancelled`() = runBlocking {
        val area = StagingArea(temp.root)
        val chunks = AtomicInteger()
        val cancelled = AtomicBoolean(false)
        // 有限但很长的输入：如果 copyCancellable 不看取消，它会正常跑完，
        // 下面的 cancelled 断言就会失败——而不是只能靠"测试挂住"才发现问题。
        val total = 200_000
        val source = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (chunks.getAndIncrement() >= total) return -1
                b.fill(0)
                return len
            }
        }
        val sink = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit
        }

        val job = launch(Dispatchers.IO) {
            try {
                area.copyCancellable(source, sink)
            } catch (e: CancellationException) {
                cancelled.set(true)
                throw e
            }
        }
        while (chunks.get() < 3) delay(1)
        job.cancelAndJoin()
        val atCancel = chunks.get()
        delay(50)

        assertTrue("copyCancellable must propagate cancellation", cancelled.get())
        assertEquals(atCancel.toLong(), chunks.get().toLong())
    }
}
