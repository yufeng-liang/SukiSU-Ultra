package com.sukisu.ultra.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Test

class Sha256Test {

    @Test
    fun `known vector for abc`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            "abc".toByteArray().sha256Hex()
        )
    }

    @Test
    fun `stream digest matches byte array digest`() {
        val bytes = "hello backup".toByteArray()
        assertEquals(bytes.sha256Hex(), ByteArrayInputStream(bytes).sha256Hex())
    }

    @Test
    fun `sha1 is available for boot archive identity`() {
        assertEquals(
            "a9993e364706816aba3e25717850c26c9cd0d89d",
            ByteArrayInputStream("abc".toByteArray()).sha1Hex(),
        )
    }

    @Test
    fun `verifying stream passes matching content through unchanged`() {
        val bytes = "payload".toByteArray()
        val stream = VerifyingInputStream(ByteArrayInputStream(bytes), bytes.sha256Hex(), "a.zip")

        assertEquals("payload", stream.use { it.readBytes().decodeToString() })
    }

    @Test
    fun `verifying stream rejects content that does not match the recorded digest`() {
        val bytes = "payload".toByteArray()
        val stream = VerifyingInputStream(ByteArrayInputStream(bytes), "deadbeef", "a.zip")

        val error = runCatching { stream.use { it.readBytes() } }.exceptionOrNull()

        // 截断或改写的归档必须在这里失败，而不是被装回设备。
        // 仍然是 IOException 子类，既有 catch 分支不会漏掉它。
        assertTrue(error is IOException)
        assertEquals(
            BackupReason.Corrupted("a.zip", "deadbeef", bytes.sha256Hex()),
            (error as BackupReasonException).reason,
        )
    }
}
