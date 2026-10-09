package com.sukisu.ultra.data.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * 备份/恢复过程中的临时文件区。
 *
 * 之前每次操作都在 app cache 里随手建临时文件，进程被杀就留下孤儿——boot 镜像一个 96MB，
 * 攒几次就吃掉几百 MB。这里统一收口，并在每次操作开始时清掉超过 [staleAfterMs] 的陈旧文件
 * （阈值取 6 小时：远大于任何一次正常操作的耗时，又不会让孤儿活过一天）。
 *
 * 每个用途各自持有一个 [root]（本地后端、云端后端、打包/恢复各一个），避免同名临时文件互踩。
 * index.json 的读-改-写互斥不在这一层——它需要跨 [BackupStorage] 实例生效，放在 [BackupEngine] 里。
 */
class StagingArea(
    val root: File,
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
) {

    /** 返回 staging 内的一个文件。名字被压平成单层，相对路径无法逃出 [root]。 */
    fun file(name: String): File {
        root.mkdirs()
        return File(root, name.substringAfterLast('/'))
    }

    /**
     * 建一个本次操作独占的临时文件（[prefix] 至少 3 个字符）。
     *
     * 打包/导出的名字如果只由内容决定（`module-archive-<id>.zip`），两个
     * [com.sukisu.ultra.data.backup.BackupRepository] 实例——备份页一个、安装后自动备份一个——
     * 并发时就会互相覆盖、甚至互相删除对方正在读的归档。
     */
    fun newFile(prefix: String, suffix: String): File {
        root.mkdirs()
        return File.createTempFile(prefix, suffix, root)
    }

    /** 删掉陈旧孤儿。单个文件删不掉不影响其他文件。 */
    fun cleanupStale() {
        val deadline = System.currentTimeMillis() - staleAfterMs
        root.listFiles().orEmpty().forEach { candidate ->
            if (candidate.isFile && candidate.lastModified() < deadline) candidate.delete()
        }
    }

    /**
     * 把 [file] 包成"关闭即删"的流。
     *
     * 临时副本的生命周期只能跟读取它的流一样长：把删除留给调用方，只读路径
     * （列表 → 恢复/导出/读 meta）就会在 cache 里叠出一份份完整归档。
     */
    fun openAndDelete(file: File): InputStream =
        object : FilterInputStream(file.inputStream()) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    file.delete()
                }
            }
        }

    /** 逐块拷贝，每块检查一次取消；返回写入字节数。 */
    suspend fun copyCancellable(input: InputStream, output: OutputStream): Long = withContext(Dispatchers.IO) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            total += read
        }
        output.flush()
        total
    }

    private companion object {
        const val DEFAULT_STALE_AFTER_MS = 6 * 60 * 60 * 1000L
    }
}
