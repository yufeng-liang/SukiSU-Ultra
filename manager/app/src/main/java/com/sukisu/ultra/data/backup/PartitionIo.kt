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
 * 所有方法不抛异常：返回 `Result` 的失败给 `Result.failure`，返回裸值的失败给空串 / 空集合 /
 * `null`——**失败和"没有"共用一个形状**（比如 [kernelRelease] 取不到 uname 和这台机器没有
 * uname 是一回事），调用方一律按「拿不到就降级」处理，不区分原因。
 */
interface PartitionIo {
    /** 当前槽位后缀，非 A/B 设备返回空串。 */
    suspend fun slotSuffix(): String

    /**
     * 当前的内核版本号（`uname -r`），如 `5.15.104-android13-8-g…`；取不到返回空串。
     *
     * 拍快照时记进 meta，恢复确认前和当时再取一次比对（[KernelKmi]）：KMI 变了说明
     * 系统升级过，旧快照刷回去大概率开不了机。
     */
    suspend fun kernelRelease(): String

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

    override suspend fun kernelRelease(): String = withContext(Dispatchers.IO) {
        exec("uname -r").out.firstOrNull()?.trim().orEmpty()
    }

    override suspend fun snapshots(): List<PartitionInfo> = withContext(Dispatchers.IO) {
        val suffix = slotSuffix()
        BASE_NAMES.mapNotNull { base ->
            val name = "$base$suffix"
            // 槽位后缀来自 getprop，厂商自定义后缀会让拼出来的名字不合白名单。
            // 这种名字一旦进 entryId / meta.partition，就会变成"备份成功、恢复被拒"的自伤，
            // 所以在源头就丢掉，而不是等恢复时才发现。
            if (!isValidName(name)) return@mapNotNull null
            val path = "$BY_NAME_DIR/$name"
            // 分区不存在时 blockdev 会失败，sizeOf 返回 null，这一项就跳过。
            val size = sizeOf(path) ?: return@mapNotNull null
            if (size <= 0) return@mapNotNull null
            PartitionInfo(name = name, path = path, sizeBytes = size)
        }
    }

    override suspend fun dump(info: PartitionInfo, target: File, compress: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            // 一律先 dd 成未压缩的镜像再压：`{ dd; } | gzip` 这条管道里，dd 读失败会被 gzip 的
            // 成功盖掉（真机实测：dd 读不存在的分区，管道退出码 0，还生成了一个 20 字节的
            // 非空 .gz）。多写一份临时文件的代价是 96MB，换来"长度必须严格等于分区大小"这条
            // 能真正验证的判据——快照宁可慢，也不能存下一份假的备份。
            val raw = File(target.absolutePath + ".raw")
            try {
                // 不吞 stderr：dd 的真实原因（I/O error、No space left）是分区写失败时唯一
                // 的线索，丢掉它用户只看到一句通用文案，日志里也查不到。
                val read = exec("dd if=${quote(info.path)} of=${quote(raw.absolutePath)} bs=1M")
                if (!read.isSuccess || raw.length() != info.sizeBytes) {
                    return@withContext Result.failure(
                        BackupReasonException(
                            BackupReason.PartitionDumpFailed(
                                info.name,
                                stderrOf(read).takeIf { it.isNotBlank() }
                                    ?: "read ${raw.length()} of ${info.sizeBytes} bytes",
                            ),
                        ),
                    )
                }
                if (!compress) {
                    // target 是 staging.newFile 建的空文件，先删掉再改名：File.renameTo 在目标
                    // 已存在时的行为依赖平台，不赌。
                    target.delete()
                    if (!raw.renameTo(target)) {
                        return@withContext Result.failure(
                            BackupReasonException(
                                BackupReason.PartitionDumpFailed(info.name, "rename to ${target.name} failed"),
                            ),
                        )
                    }
                    return@withContext Result.success(Unit)
                }
                // gzip -1 是速度优先：96MB 的 boot 全量压一遍要几秒，-9 要多花好几倍时间换几个百分点。
                val gz = exec("gzip -1 -c ${quote(raw.absolutePath)} > ${quote(target.absolutePath)}")
                if (!gz.isSuccess || target.length() <= 0L) {
                    target.delete()
                    return@withContext Result.failure(
                        BackupReasonException(
                            BackupReason.PartitionDumpFailed(info.name, stderrOf(gz).takeIf { it.isNotBlank() }),
                        ),
                    )
                }
                Result.success(Unit)
            } finally {
                raw.delete()
            }
        }

    override suspend fun flash(info: PartitionInfo, source: File, compressed: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            // 分区默认只读；ksud 用 BLKROSET ioctl 解，shell 这一侧用 blockdev 解，效果一样。
            val rw = exec("blockdev --setrw ${quote(info.path)}")
            if (!rw.isSuccess) {
                return@withContext Result.failure(
                    BackupReasonException(
                        BackupReason.PartitionFlashFailed(info.name, stderrOf(rw).takeIf { it.isNotBlank() }),
                    ),
                )
            }
            // 压缩归档先解到 staging 再写分区，绝不 `gzip -dc | dd` 直连。
            //
            // 真机实测（shennong / toybox gzip）：喂垃圾数据或截断的 .gz 给 `gzip -dc`，它**照样
            // 退出 0**，只写出 0 字节或半截数据；直连 dd 时 dd 读到 EOF 也退出 0 —— 于是整条
            // 管道"成功"，分区只被写了一半。退出码在这里不可信，唯一能拦住的是**落盘后数长度**。
            var payload = source
            var unpacked: File? = null
            if (compressed) {
                val target = File(source.absolutePath + ".unpacked")
                unpacked = target
                val unzip = exec("gzip -dc ${quote(source.absolutePath)} > ${quote(target.absolutePath)}")
                if (!unzip.isSuccess) {
                    target.delete()
                    return@withContext Result.failure(
                        BackupReasonException(
                            BackupReason.PartitionFlashFailed(
                                info.name,
                                stderrOf(unzip).takeIf { it.isNotBlank() } ?: "gzip failed",
                            ),
                        ),
                    )
                }
                payload = target
            }
            try {
                // 最后一道关：真写下去的东西必须和分区一样长。短了是刷坏，长了会越过分区边界。
                val actual = payload.length()
                if (actual != info.sizeBytes) {
                    return@withContext Result.failure(
                        BackupReasonException(
                            BackupReason.PartitionSizeMismatch(info.name, info.sizeBytes, actual),
                        ),
                    )
                }
                val result = exec(
                    "dd if=${quote(payload.absolutePath)} of=${quote(info.path)} bs=1M conv=fsync && sync",
                )
                if (result.isSuccess) {
                    Result.success(Unit)
                } else {
                    Result.failure(
                        BackupReasonException(
                            BackupReason.PartitionFlashFailed(info.name, stderrOf(result).takeIf { it.isNotBlank() }),
                        ),
                    )
                }
            } finally {
                unpacked?.delete()
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
