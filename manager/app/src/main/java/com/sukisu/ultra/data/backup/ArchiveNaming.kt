package com.sukisu.ultra.data.backup

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class BackupKind(val wireName: String) {
    MODULE("module"),
    BOOT("boot"),
}

object ArchiveNaming {
    const val INDEX_FILE = "index.json"
    const val MANIFEST_SCHEMA = "sukisu.backup.manifest"
    const val MODULE_SCHEMA = "sukisu.module.backup"
    const val BOOT_SCHEMA = "sukisu.boot.backup"
    const val MANIFEST_VERSION = 1

    /** 外部来源的名字洗干净后什么都不剩时的兜底名字。 */
    const val IMPORTED_FALLBACK_NAME = "imported.bin"

    private const val MODULE_PREFIX = "module_"
    private const val BOOT_PREFIX = "boot_"
    private const val MODULE_SUFFIX = ".zip"
    private const val BOOT_SUFFIX = ".img"
    private const val BOOT_SHA1_PREFIX_LENGTH = 12

    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC)

    fun timestamp(at: Instant): String = TIMESTAMP.format(at)

    /** 文件名里只允许 [A-Za-z0-9._-]，其余字符（含路径分隔符）替换为下划线。 */
    fun safeId(raw: String): String =
        raw.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("")

    fun moduleArchiveName(moduleId: String, versionCode: Long, timestamp: String): String =
        "$MODULE_PREFIX${safeId(moduleId)}_${versionCode}_$timestamp$MODULE_SUFFIX"

    fun bootArchiveName(sha1: String, timestamp: String): String =
        "$BOOT_PREFIX${sha1.take(BOOT_SHA1_PREFIX_LENGTH)}_$timestamp$BOOT_SUFFIX"

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
        archiveName.startsWith(BOOT_PREFIX) && archiveName.endsWith(BOOT_SUFFIX) -> BackupKind.BOOT
        else -> null
    }
}
