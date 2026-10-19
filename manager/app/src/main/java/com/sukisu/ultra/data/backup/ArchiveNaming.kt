package com.sukisu.ultra.data.backup

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class BackupKind(val wireName: String) {
    MODULE("module"),

    /**
     * 旧版「原厂 boot 镜像」备份，已停用。
     *
     * 保留这个值只为两件事：① 旧索引里还躺着 `boot` 条目，[BackupManifest] 认不出 kind 会把
     * 整份索引判成损坏（不是跳过那几条，是全部读不出来），所以 wireName 必须继续认得；
     * ② 导入旧归档时能认出它，好明确告诉用户"这个功能已经换成分区快照了"。
     *
     * 它不再有 [BackupSource]，因此既不会被备份，也不会出现在列表里。
     */
    BOOT("boot"),

    /** 分区快照：直接读块设备，把 boot / init_boot 原样存下来，恢复时按分区刷回。 */
    PARTITION("partition"),

    REPO_CONFIG("repo"),
}

object ArchiveNaming {
    const val INDEX_FILE = "index.json"
    const val MANIFEST_SCHEMA = "sukisu.backup.manifest"
    const val MODULE_SCHEMA = "sukisu.module.backup"
    const val PARTITION_SCHEMA = "sukisu.partition.backup"
    const val MANIFEST_VERSION = 1

    /** 外部来源的名字洗干净后什么都不剩时的兜底名字。 */
    const val IMPORTED_FALLBACK_NAME = "imported.bin"

    private const val MODULE_PREFIX = "module_"
    private const val BOOT_PREFIX = "boot_"
    private const val MODULE_SUFFIX = ".zip"
    private const val PARTITION_PREFIX = "partition_"
    private const val PARTITION_SUFFIX = ".img"
    private const val GZIP_SUFFIX = ".gz"
    private const val REPO_PREFIX = "repo_"
    private const val REPO_SUFFIX = ".json"
    private const val BOOT_SHA1_PREFIX_LENGTH = 12

    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC)

    fun timestamp(at: Instant): String = TIMESTAMP.format(at)

    private val SESSION = Regex("_(\\d{8}_\\d{6})")

    /**
     * 归档名里的会话号：一次备份写出的所有归档共用同一个时间戳。
     *
     * 列表分组（界面）和保留额度（数据层）都要按"哪一次备份"来算，判定必须是同一套，
     * 所以它定义在这里，而不是两边各写一份正则。
     */
    fun sessionOf(fileName: String): String? = SESSION.find(fileName)?.groupValues?.get(1)

    /** 文件名里只允许 [A-Za-z0-9._-]，其余字符（含路径分隔符）替换为下划线。 */
    fun safeId(raw: String): String =
        raw.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("")

    private val MODULE_ID = Regex("^[A-Za-z0-9._-]{1,128}$")

    /**
     * 模块 id 是不是"原样可用"的（KernelSU 的模块 id 就是 `[A-Za-z0-9._-]+`）。
     *
     * 与 [safeId] 的"把危险字符洗掉"不同，这里要的是"要么原样，要么拒绝"：这个字符串会被拼进
     * 以 root 身份执行的 `ksud module enable|disable <id>`，而它来自索引——索引躺在共享存储或
     * 远端 WebDAV 上，谁都能改。洗出一个"看着还行"的 id，等于把一份被改过的索引变成一条以
     * root 执行的命令；只有拒绝执行才是安全的。
     */
    fun isValidModuleId(raw: String): Boolean = MODULE_ID.matches(raw)

    fun moduleArchiveName(moduleId: String, versionCode: Long, timestamp: String): String =
        "$MODULE_PREFIX${safeId(moduleId)}_${versionCode}_$timestamp$MODULE_SUFFIX"

    fun bootArchiveName(sha1: String, timestamp: String): String =
        "$BOOT_PREFIX${sha1.take(BOOT_SHA1_PREFIX_LENGTH)}_$timestamp$PARTITION_SUFFIX"

    /**
     * 分区快照归档名，例如 `partition_boot_a_20261019_120000.img`。
     *
     * 名字里带分区名而不是校验和：恢复时"这份东西该刷回哪个分区"必须一眼可见——刷错分区是
     * 真能变砖的，让用户在列表里看到 `boot_a` 比看到一段哈希有用得多。同一分区内容的唯一性
     * 由索引里的 sha256 判（[DuplicatePolicy]），不靠文件名。
     *
     * @param partition 已带槽位后缀的分区名，如 `boot_a`、`init_boot_b`
     * @param compressed 是否 gzip 压缩；扩展名跟着变，恢复时按它决定要不要解压
     */
    fun partitionArchiveName(partition: String, timestamp: String, compressed: Boolean): String {
        val suffix = if (compressed) "$PARTITION_SUFFIX$GZIP_SUFFIX" else PARTITION_SUFFIX
        return "$PARTITION_PREFIX${safeId(partition)}_$timestamp$suffix"
    }

    fun repoConfigArchiveName(timestamp: String): String =
        "${REPO_PREFIX}config_$timestamp$REPO_SUFFIX"

    fun metaFileNameFor(archiveName: String): String = "$archiveName.meta.json"

    /**
     * 外部来源（SAF 导入、索引里的条目）给的文件名，先压成单层再交给后端。
     *
     * 这些字符串会一路走到以 root 身份执行的 shell 命令里，所以只保留最后一段、
     * 并清掉路径分隔符与引号一类的字符——既防目录穿越，也防命令拼接。
     */
    fun safeFileName(raw: String): String {
        val last = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = safeId(last)
        return cleaned.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: IMPORTED_FALLBACK_NAME
    }

    /**
     * 目标文件名已被占用时补一个序号。
     *
     * 时间戳只到秒：同一模块在同一秒内产出不同内容会撞名，直接覆盖会让索引里留下两条
     * 指向同一文件的条目，随后删"旧"条目就把新条目指向的文件一起删掉。
     */
    fun uniqueName(desired: String, taken: Set<String>): String {
        if (desired !in taken) return desired
        val dot = desired.lastIndexOf('.')
        val base = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""
        var n = 2
        while ("${base}_$n$ext" in taken) n++
        return "${base}_$n$ext"
    }

    fun kindOf(archiveName: String): BackupKind? = when {
        archiveName.startsWith(MODULE_PREFIX) && archiveName.endsWith(MODULE_SUFFIX) -> BackupKind.MODULE
        // 分区快照要先判：它和旧的 boot 归档同为 .img（还可能带 .gz），但前缀不同。
        archiveName.startsWith(PARTITION_PREFIX) &&
            (archiveName.endsWith(PARTITION_SUFFIX) || archiveName.endsWith("$PARTITION_SUFFIX$GZIP_SUFFIX")) ->
            BackupKind.PARTITION
        archiveName.startsWith(BOOT_PREFIX) && archiveName.endsWith(PARTITION_SUFFIX) -> BackupKind.BOOT
        archiveName.startsWith(REPO_PREFIX) && archiveName.endsWith(REPO_SUFFIX) -> BackupKind.REPO_CONFIG
        else -> null
    }

    /** 归档名里是否带 gzip 压缩（恢复时据此决定要不要先解压）。 */
    fun isCompressed(archiveName: String): Boolean = archiveName.endsWith(GZIP_SUFFIX)
}
