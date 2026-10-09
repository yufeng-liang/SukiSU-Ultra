package com.sukisu.ultra.data.backup

import java.io.FilterInputStream
import java.io.InputStream

/**
 * 一次传输的进度：这次目标已经推出去多少字节、总共多少。
 *
 * 云端上传 boot 时要几十秒到几分钟，没有进度用户只能盯着一个不动的按钮猜是不是卡住了。
 */
data class TransferProgress(
    val fileName: String,
    val sentBytes: Long,
    val totalBytes: Long,
)

/**
 * 数着读过去的字节，顺带按时间节流上报。
 *
 * 包在**源流**上而不是改后端：后端把流读出去多少，就是这次传输推出去多少——本地是拷、云端是
 * 上传，两边都成立，而 [BackupStorage] 的签名不用为进度动一次（它有六个实现和一堆测试）。
 *
 * 节流是必要的：一个 96MB 的镜像按 8KB 一块读就是上万次回调，每次都往 StateFlow 里写一次
 * 会让界面比上传本身还忙。
 */
internal class CountingInputStream(
    delegate: InputStream,
    private val onProgress: (Long) -> Unit,
    private val throttleMs: Long = 100,
    private val clock: () -> Long = System::currentTimeMillis,
) : FilterInputStream(delegate) {

    private var read = 0L
    private var lastReportAt = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) {
            read += 1
            report()
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val count = super.read(b, off, len)
        if (count > 0) {
            read += count
            report()
        }
        return count
    }

    private fun report() {
        val now = clock()
        if (now - lastReportAt < throttleMs) return
        lastReportAt = now
        onProgress(read)
    }
}
