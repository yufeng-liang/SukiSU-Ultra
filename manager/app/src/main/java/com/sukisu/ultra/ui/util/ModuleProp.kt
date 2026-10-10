package com.sukisu.ultra.ui.util

import com.sukisu.ultra.data.backup.sha256Hex
import java.io.File
import java.util.zip.ZipFile

/**
 * 模块 zip 里 `module.prop` 的关键字段。
 *
 * 用于「用户投稿时自动解析模块信息，免去手填」：zip 由 ksud 当黑盒装上去，
 * 但投稿条目要写进索引（id / name / author / description / version / versionCode），
 * 这些只能在 App 侧从 zip 里读出来。
 *
 * 缺失的字段给空串（[versionCode] 给 0）——索引校验要求 id/name/author/description 都非空，
 * 空串能把"哪一项没填"报出来，而不是让整条解析失败。
 */
data class ModulePropInfo(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Long,
    val author: String,
    val description: String,
)

/**
 * 从本地模块 zip 读 `module.prop`。
 *
 * 索引里 id 是归并键、name 是显示名，两者任一为空就无从登记，所以这里返回 null 由调用方
 * 退回手填，而不是把一条残缺条目塞进投稿流程。
 */
object ModuleProp {

    /** ksud 只认 zip 根下的这一份，子目录里的同名文件不算。 */
    private const val PROP_ENTRY = "module.prop"

    /** module.prop 是几行文本；读再多也不会多出字段，只可能是恶意构造。 */
    private const val MAX_PROP_BYTES = 64 * 1024

    private val KEYS = setOf("id", "name", "version", "versionCode", "author", "description")

    /**
     * 解析 [zipFile] 根目录下的 `module.prop`，返回 null 表示"读不出来"：
     * 不是 zip、zip 里没有根目录的 module.prop、或 id / name 为空。
     */
    fun parseModuleProp(zipFile: File): ModulePropInfo? =
        runCatching { readModuleProp(zipFile) }.getOrNull()

    /** zip 整体的 SHA-256，小写十六进制；读不到文件时返回 null。 */
    fun zipSha256(zipFile: File): String? =
        runCatching { zipFile.inputStream().buffered().use { it.sha256Hex() } }.getOrNull()

    private fun readModuleProp(zipFile: File): ModulePropInfo? {
        val text = ZipFile(zipFile).use { zip ->
            // 精确匹配：getEntry("module.prop") 不会命中 "some_dir/module.prop"，
            // 而那份文件 ksud 也不会读，解析它等于报出一个装不上的模块。
            val entry = zip.getEntry(PROP_ENTRY) ?: return null
            zip.getInputStream(entry).use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_PROP_BYTES) return null
                bytes.decodeToString()
            }
        }
        val values = parse(text)
        val id = values["id"].orEmpty()
        val name = values["name"].orEmpty()
        if (id.isBlank() || name.isBlank()) return null
        return ModulePropInfo(
            id = id,
            name = name,
            version = values["version"].orEmpty(),
            versionCode = values["versionCode"]?.toLongOrNull() ?: 0L,
            author = values["author"].orEmpty(),
            description = values["description"].orEmpty(),
        )
    }

    /** 逐行 `key=value`：跳过空行与 `#` 注释，只按第一个 `=` 切，值里的 `=` 保留。 */
    private fun parse(text: String): Map<String, String> {
        val values = HashMap<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.removePrefix("\uFEFF").trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val split = line.indexOf('=')
            if (split <= 0) return@forEach
            val key = line.substring(0, split).trim()
            if (key !in KEYS) return@forEach
            values[key] = line.substring(split + 1).trim()
        }
        return values
    }
}
