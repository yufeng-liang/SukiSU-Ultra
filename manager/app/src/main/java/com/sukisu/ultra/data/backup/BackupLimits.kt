package com.sukisu.ultra.data.backup

import java.io.ByteArrayOutputStream
import java.io.InputStream

object BackupLimits {
    /**
     * index.json / `*.meta.json` 这类小文本的读取上限。
     *
     * 索引是"读全量 → 改 → 整体回写"的，边车 meta 只有几百字节；正常情况下它们都远小于这个数。
     * 上限的意义在于：索引文件被换成了一个大文件（误操作、同步客户端塞进来一个大文件、远端被写坏）
     * 时直接报"索引读不出来"，而不是先把几十上百 MB 读进内存再 OOM——OOM 会连"拒绝回写"这一步
     * 都做不到，那才是真正的丢索引。
     */
    const val MAX_TEXT_BYTES: Long = 8L * 1024 * 1024
}

/**
 * 读一份小文本，超过 [limit] 就抛 [BackupReasonException]。
 *
 * 原因用 [BackupReason.IndexUnreadable]：文件在、但内容不可能是我们要的清单，与"读不到"
 * （[BackupReason.ReadFailed]）分开——引擎据此拒绝把索引整体写回去。超限时立即抛，
 * 不会把超出的内容读进内存。
 */
internal fun InputStream.readBoundedText(limit: Long, path: String): String {
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    while (true) {
        val read = read(chunk)
        if (read < 0) break
        out.write(chunk, 0, read)
        if (out.size() > limit) {
            throw BackupReasonException(
                BackupReason.IndexUnreadable("$path is larger than ${limit / 1024 / 1024} MiB"),
            )
        }
    }
    return out.toByteArray().decodeToString()
}
