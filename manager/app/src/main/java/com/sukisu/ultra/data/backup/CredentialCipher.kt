package com.sukisu.ultra.data.backup

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * WebDAV 凭据的信封加密。只依赖 javax.crypto，便于在 JVM 单测里直接验证。
 * 信封格式：`v1:<base64(iv)>:<base64(ciphertext+tag)>`。
 *
 * **加密时不要把 IV 交给 cipher**（别写 `init(ENCRYPT_MODE, key, GCMParameterSpec(...))`）：
 * AndroidKeyStore 的 AES/GCM 密钥默认要求随机 IV（`setRandomizedEncryptionRequired` 的默认值），
 * 调用方自带 IV 会当场抛 `InvalidAlgorithmParameterException: Caller-provided IV not permitted`，
 * 表现就是"保存 WebDAV 密码 → 管理器直接崩"。让 provider 生成、再从 `cipher.iv` 读回来存进信封；
 * 解密方向（[decrypt]）传 IV 是允许的，那条限制只管加密。
 */
object CredentialCipher {

    private const val VERSION = "v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun encrypt(key: SecretKey, plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val iv = requireNotNull(cipher.iv) { "the provider generated no IV" }
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
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv)) }
        return cipher.doFinal(decoder.decode(parts[2])).decodeToString()
    }
}
