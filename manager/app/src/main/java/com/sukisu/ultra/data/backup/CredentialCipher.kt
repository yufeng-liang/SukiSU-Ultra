package com.sukisu.ultra.data.backup

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * WebDAV 凭据的信封加密。只依赖 javax.crypto，便于在 JVM 单测里直接验证。
 * 信封格式：`v1:<base64(iv)>:<base64(ciphertext+tag)>`。
 */
object CredentialCipher {

    private const val VERSION = "v1"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    fun encrypt(key: SecretKey, plaintext: String): String {
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv)) }
        val encrypted = cipher.doFinal(plaintext.toByteArray())
        val encoder = Base64.getEncoder()
        return "$VERSION:${encoder.encodeToString(iv)}:${encoder.encodeToString(encrypted)}"
    }

    fun decrypt(key: SecretKey, envelope: String): String {
        val parts = envelope.split(":")
        require(parts.size == 3) { "malformed credential envelope" }
        require(parts[0] == VERSION) { "unsupported credential envelope version: ${parts[0]}" }
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(parts[1])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv)) }
        return cipher.doFinal(decoder.decode(parts[2])).decodeToString()
    }
}
