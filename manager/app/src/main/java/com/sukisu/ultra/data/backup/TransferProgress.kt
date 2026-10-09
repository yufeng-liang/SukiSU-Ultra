package com.sukisu.ultra.data.backup

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/**
 * 一次传输的进度：这次目标已经推出去多少字节、总共多少。
 *
 * 云端上传 boot 时要几十秒到几分钟，没有进度用户只能盯着一个不动的按钮猜是不是卡住了。
 *
 * [fileIndex] / [fileCount] 是"这个目标里的第几个归档、一共几个"：11 个模块的一次备份里，
 * 只报字节数看不出还剩几个文件，而用户看到的列表就是 11 项——进度上不写这一层，
 * 他会以为"1/1"是模块数（见 `backup_progress_target` 的措辞）。
 */
data class TransferProgress(
    val fileName: String,
    val sentBytes: Long,
    val totalBytes: Long,
    /** 第几个归档（1 起）；0 表示还不知道。 */
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
)

/**
 * 数着读过去的字节，顺带按时间节流上报。
 *
 * 包在**源流**上，给"把源流读掉就等于推出去"的后端用（本地：拷到目标）。WebDAV 不能这么数——
 * 它先把源流整份落到 staging 再上传，包在源流上只能数到那段本地拷贝，见 [CountingRequestBody]。
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
        } else {
            report(force = true)
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val count = super.read(b, off, len)
        if (count > 0) {
            read += count
            report()
        } else if (count < 0) {
            report(force = true)
        }
        return count
    }

    /** 读到末尾时补一次：节流会把最后一块吞掉，进度条就停在 99% 不动了。 */
    private fun report(force: Boolean = false) {
        val now = clock()
        if (!force && now - lastReportAt < throttleMs) return
        lastReportAt = now
        onProgress(read)
    }
}

/**
 * 边发边数：把文件写进请求体时上报已写字节。
 *
 * 进度必须在这一侧数。WebDAV 的上传是"先把源流整份落到本地 staging、再把 staging 文件发出去"，
 * 引擎包在源流上的那层计数数的是那段本地拷贝（96MB 也就几十毫秒），真正的上传期间一个字节都不
 * 会动。而这里写进 socket 的字节数就是用户想看的那个数——OkHttp 的 sink 带缓冲，网络慢下来时
 * 写操作就阻塞在 socket 上，节流后的上报自然跟着网络节奏走。
 *
 * 每次 [writeTo] 自己重开文件流，所以不是 one-shot：服务端要求重发（401 挑战、重定向）时
 * OkHttp 能再读一遍。
 */
internal class CountingRequestBody(
    private val file: File,
    private val contentType: MediaType?,
    private val onProgress: (Long) -> Unit,
    private val throttleMs: Long = 100,
    private val clock: () -> Long = System::currentTimeMillis,
) : RequestBody() {

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        var written = 0L
        var lastReportAt = 0L
        file.source().use { source ->
            val buffer = Buffer()
            while (true) {
                val count = source.read(buffer, CHUNK_BYTES)
                if (count == -1L) break
                sink.write(buffer, count)
                written += count
                val now = clock()
                if (now - lastReportAt >= throttleMs) {
                    lastReportAt = now
                    onProgress(written)
                }
            }
        }
        // 同上：最后一块不能被节流吞掉，否则进度条停在 99%。
        onProgress(written)
    }

    private companion object {
        /** 8KB，和 OkHttp 自己的分段大小一致；更小只是把回调次数变多。 */
        const val CHUNK_BYTES = 8L * 1024L
    }
}
