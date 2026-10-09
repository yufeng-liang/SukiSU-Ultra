package com.sukisu.ultra.data.backup

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256").apply { update(this@sha256Hex) }.hex()

/** 流式计算，调用方负责关闭 [this]。 */
fun InputStream.sha256Hex(): String = digestHex("SHA-256")

/** ksud 用原厂镜像内容的 sha1 命名 `/data/adb/ksu/ksu_backup_<sha1>`，导入 boot 归档时靠它还原身份。 */
fun InputStream.sha1Hex(): String = digestHex("SHA-1")

private fun InputStream.digestHex(algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = read(buffer)
        if (read <= 0) break
        digest.update(buffer, 0, read)
    }
    return digest.hex()
}

/**
 * 边读边算 sha256，读到末尾时与 [expectedSha256] 比对，不一致就抛 [IOException]。
 *
 * 归档可能被截断（传输中断、网盘侧改写），而恢复是不可逆的：模块会被 ksud 装上去、
 * boot 会被刷进分区。让消费者在"读完"这一步就失败，比装完才发现内容不对安全得多。
 * 消费者必须读到流末尾（`copyTo` 就是），否则不会触发比对。
 */
class VerifyingInputStream(
    delegate: InputStream,
    private val expectedSha256: String,
    private val label: String,
) : FilterInputStream(delegate) {

    private val digest = MessageDigest.getInstance("SHA-256")
    private var verified = false

    override fun read(): Int {
        val value = super.read()
        if (value == -1) verify() else digest.update(value.toByte())
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = super.read(b, off, len)
        if (read == -1) verify() else digest.update(b, off, read)
        return read
    }

    /** 跳过就是"不校验地放过"，宁可让调用方失败。 */
    override fun skip(n: Long): Long = throw IOException("$label: cannot skip while verifying")

    private fun verify() {
        if (verified) return
        verified = true
        val actual = digest.hex()
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            throw IOException("$label is corrupted: sha256 $actual, expected $expectedSha256")
        }
    }
}
