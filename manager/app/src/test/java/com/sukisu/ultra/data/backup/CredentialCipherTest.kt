package com.sukisu.ultra.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

/**
 * 信封加密的往返与拒收。
 *
 * 这里**验不了** AndroidKeyStore 那条"加密时不许调用方自带 IV"的规矩：JDK 的 `Cipher` 在 provider
 * 抛 `InvalidAlgorithmParameterException` 之后会继续试下一个 provider（实测：把一个专门拒绝的
 * provider 插在最前面，`getInstance` 确实选到它、`init` 也真的调到了它，但异常被吞掉，落到 SunJCE
 * 上照样成功）。所以那个坑只能靠真机验，代码里在 [CredentialCipher] 上留了说明。
 */
class CredentialCipherTest {

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `round trip recovers the plaintext`() {
        val envelope = CredentialCipher.encrypt(key, "app-password-123")
        assertEquals("app-password-123", CredentialCipher.decrypt(key, envelope))
    }

    @Test
    fun `the same plaintext encrypts differently every time`() {
        assertNotEquals(CredentialCipher.encrypt(key, "x"), CredentialCipher.encrypt(key, "x"))
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val envelope = CredentialCipher.encrypt(key, "secret")
        val parts = envelope.split(":")
        val tampered = parts[0] + ":" + parts[1] + ":" + parts[2].dropLast(4) + "AAAA"
        assertTrue(runCatching { CredentialCipher.decrypt(key, tampered) }.isFailure)
    }

    @Test
    fun `unknown version is rejected`() {
        assertTrue(runCatching { CredentialCipher.decrypt(key, "v9:a:b") }.isFailure)
    }
}
