package com.sukisu.ultra.data.backup

import com.sukisu.ultra.ui.util.getRootShell
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 一个可以整块读写的分区。
 *
 * [sizeBytes] 是分区本身的大小（`blockdev --getsize64`），不是归档大小：恢复时拿它和归档解压后
 * 的字节数比对，不等就拒绝写入——刷一个长度不对的镜像进去是真能变砖的。
 */
data class PartitionInfo(
    /** 带槽位的名字，如 `boot_a`、`init_boot_b`；非 A/B 设备就是 `boot`。 */
    val name: String,
    /** `/dev/block/by-name/<name>`。 */
    val path: String,
    val sizeBytes: Long,
)

/**
 * 块设备读写。
 *
 * 与 [RootFiles] 分开：那边是普通文件（cp/mv/list），这边是块设备（dd/blockdev），失败模式
 * 完全不同——分区默认只读、写错分区会变砖、写到一半掉电会留下半截镜像，所以单独一个窄接口，
 * 让 [PartitionSnapshotSource] 的校验逻辑能被单测盯着。
 *
 * 所有方法不抛异常，失败用 `Result` / `null` 返回。
 */
interface PartitionIo {
    /** 当前槽位后缀，非 A/B 设备返回空串。 */
    suspend fun slotSuffix(): String

    /**
     * 本机现在能拍快照的分区（boot / init_boot / vendor_boot 中存在的那些）。
     *
     * 顺序固定为 boot、init_boot、vendor_boot，方便界面稳定地展示。
     */
    suspend fun snapshots(): List<PartitionInfo>

    /** 把 [info] 整块读进 [target]；[compress] 为真时写成 gzip。 */
    suspend fun dump(info: PartitionInfo, target: File, compress: Boolean): Result<Unit>

    /** 把 [source] 原样写回 [info]；[compressed] 为真时先解压。 */
    suspend fun flash(info: PartitionInfo, source: File, compressed: Boolean): Result<Unit>
}

/**
 * 用 root shell 里的 `dd` / `blockdev` 实现。
 *
 * 为什么不走 libsu 的 `SuFile` 直接读写：`SuFile` 的 read/write 走的是 Java 流，块设备没有
 * "文件长度"语义，读到文件尾的行为依赖内核；`dd` 是按字节直通，且 `conv=fsync` 能把落盘时机
 * 说清楚——刷分区这一步宁可慢一点，也不要在 sync 之前返回"成功"。
 */
class ShellPartitionIo(private val shell: () -> Shell = { getRootShell() }) : PartitionIo {

    override suspend fun slotSuffix(): String = withContext(Dispatchers.IO) {
        exec("getprop ro.boot.slot_suffix").out.firstOrNull()?.trim().orEmpty()
    }

    override suspend fun snapshots(): List<PartitionInfo> = withContext(Dispatchers.IO) {
        val suffix = slotSuffix()
        BASE_NAMES.mapNotNull { base ->
            val name = "$base$suffix"
            val path = "$BY_NAME_DIR/$name"
            // 分区不存在时 blockdev 会失败，sizeOf 返回 null，这一项就跳过。
            val size = sizeOf(path) ?: return@mapNotNull null
            if (size <= 0) return@mapNotNull null
            PartitionInfo(name = name, path = path, sizeBytes = size)
        }
    }

    override suspend fun dump(info: PartitionInfo, target: File, compress: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            val out = quote(target.absolutePath)
            val command = if (compress) {
                // gzip -1 是速度优先：96MB 的 boot 全量压一遍要几秒，-9 要多花好几倍时间换几个百分点。
                "dd if=${quote(info.path)} bs=1M 2>/dev/null | gzip -1 -c > $out"
            } else {
                "dd if=${quote(info.path)} of=$out bs=1M 2>/dev/null"
            }
            val result = exec(command)
            // dd 成功但写出 0 字节（比如分区路径其实不存在）也要当失败，否则会存下一份空归档。
            if (result.isSuccess && target.length() > 0L) {
                Result.success(Unit)
            } else {
                Result.failure(
                    BackupReasonException(
                        BackupReason.PartitionDumpFailed(info.name, stderrOf(result).takeIf { it.isNotBlank() }),
                    ),
                )
            }
        }

    override suspend fun flash(info: PartitionInfo, source: File, compressed: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            val input = quote(source.absolutePath)
            val target = quote(info.path)
            // 分区默认只读；ksud 用 BLKROSET ioctl 解，shell 这一侧用 blockdev 解，效果一样。
            val rw = exec("blockdev --setrw $target")
            if (!rw.isSuccess) {
                return@withContext Result.failure(
                    BackupReasonException(
                        BackupReason.PartitionFlashFailed(info.name, stderrOf(rw).takeIf { it.isNotBlank() }),
                    ),
                )
            }
            val command = if (compressed) {
                "gzip -dc $input | dd of=$target bs=1M conv=fsync 2>/dev/null && sync"
            } else {
                "dd if=$input of=$target bs=1M conv=fsync 2>/dev/null && sync"
            }
            val result = exec(command)
            if (result.isSuccess) {
                Result.success(Unit)
            } else {
                Result.failure(
                    BackupReasonException(
                        BackupReason.PartitionFlashFailed(info.name, stderrOf(result).takeIf { it.isNotBlank() }),
                    ),
                )
            }
        }

    private fun sizeOf(path: String): Long? {
        val fromBlockdev = exec("blockdev --getsize64 ${quote(path)}")
            .out.firstOrNull()?.trim()?.toLongOrNull()
        if (fromBlockdev != null && fromBlockdev > 0) return fromBlockdev
        // 极少数设备没有 blockdev，退化成 SuFile 的长度（对 by-name 软链同样有效）。
        return runCatching { SuFile(path).length() }.getOrNull()?.takeIf { it > 0 }
    }

    /** shell 的执行结果，只留这个类真正要用的三样东西。 */
    private class Outcome(val isSuccess: Boolean, val out: List<String>, val err: List<String>)

    private fun exec(command: String): Outcome = runCatching {
        val result = shell().newJob().add(command).to(ArrayList(), null).exec()
        Outcome(result.isSuccess, result.out, result.err)
    }.getOrElse { error ->
        // shell 本身起不来时给一个必然失败的结果，让上层统一走"命令失败"分支。
        Outcome(false, emptyList(), listOf(error.message.orEmpty()))
    }

    private fun stderrOf(result: Outcome): String = result.err.joinToString("\n").trim()

    /** 单引号包裹并把 `'` 拆成 `'\''`：分区名和路径会拼进 root 命令，必须防拼接。 */
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private const val BY_NAME_DIR = "/dev/block/by-name"

        /** 只认这三个：A/B 设备的 root 在 init_boot，内核在 boot，vendor_boot 少数设备才需要。 */
        val BASE_NAMES = listOf("boot", "init_boot", "vendor_boot")

        /**
         * 允许出现在命令里的分区名。
         *
         * 名字来自索引边车（共享存储或远端 WebDAV，外部可写），会拼进以 root 执行的 dd 命令；
         * 形状不对就拒绝，而不是转义后照跑——同 [ArchiveNaming.isValidModuleId] 的道理。
         */
        private val NAME_PATTERN = Regex("^(boot|init_boot|vendor_boot)(_[ab])?$")

        fun isValidName(raw: String): Boolean = NAME_PATTERN.matches(raw)
    }
}
