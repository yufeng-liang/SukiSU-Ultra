package com.sukisu.ultra.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

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
