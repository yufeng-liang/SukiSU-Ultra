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
     * 三种安装器在 zip 里的位置，精确匹配（不带尾随斜杠的意义在于：`customize.sh/` 这种
     * 同名目录不算）。
     */
    private val INSTALLER_ENTRIES = listOf(
        "customize.sh",
        "install.sh",
        "META-INF/com/google/android/update-binary",
    )

    /**
     * 解析 [zipFile] 根目录下的 `module.prop`，返回 null 表示"读不出来"：
     * 不是 zip、zip 里没有根目录的 module.prop、或 id / name 为空。
     */
    fun parseModuleProp(zipFile: File): ModulePropInfo? =
        runCatching { readModuleProp(zipFile) }.getOrNull()

    /** zip 整体的 SHA-256，小写十六进制；读不到文件时返回 null。 */
    fun zipSha256(zipFile: File): String? =
        runCatching { zipFile.inputStream().buffered().use { it.sha256Hex() } }.getOrNull()

    /**
     * 这个包是不是「作者打包出来准备分发的」——判据是它自带安装器。
     *
     * 三种都算，因为三种都能被某个管理器执行：
     * - `customize.sh`：KernelSU / SukiSU 的安装脚本（新版模板）；
     * - `install.sh`：Magisk 老模板的安装脚本，ksud 也认（`is_legacy_script`）；
     * - `META-INF/com/google/android/update-binary`：Magisk 与 recovery 的安装器。
     *
     * 是用来挡「从已安装的模块目录现打的包」的：ksud 装完会删掉 `customize.sh`、也从不解压
     * `META-INF`，所以设备上那棵目录树里这三样一个都不会有。反过来，一个**没有**脚本但只有
     * `module.prop` + 文件的原包也能装（ksud 走默认解压），所以这道检查只证明「这不是设备上
     * 打出来的包」，不证明「安装脚本一定会跑」——后者只能靠作者原包。
     */
    fun hasInstallerEntry(zipFile: File): Boolean = runCatching {
        ZipFile(zipFile).use { zip ->
            INSTALLER_ENTRIES.any { name -> zip.getEntry(name) != null }
        }
    }.getOrDefault(false)

    private fun readModuleProp(zipFile: File): ModulePropInfo? {
        val text = ZipFile(zipFile).use { zip ->
            // 精确匹配：getEntry("module.prop") 不会命中 "some_dir/module.prop"，
            // 而那份文件 ksud 也不会读，解析它等于报出一个装不上的模块。
            val entry = zip.getEntry(PROP_ENTRY) ?: return null
            // 先看 zip 头里声明的大小：deflate 的压缩比可以到 1000:1，一个 8MB 的包
            // 完全能声明出一条解压后几个 GB 的 module.prop。只靠「读完再比大小」的话，
            // 那道 64KB 的闸要在内存已经被撑爆之后才生效。
            if (entry.size > MAX_PROP_BYTES) return null
            zip.getInputStream(entry).use { input ->
                // entry.size 是 -1（未知）时也不能整份读：按上限多读一个字节，
                // 读满了就说明超限。不用 InputStream.readNBytes(long)：那是 API 33 才有的，
                // 本项目的 minSdk 是 26。
                val bytes = readAtMost(input, MAX_PROP_BYTES + 1)
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

    /**
     * 最多读 [limit] 字节；流还没结束时返回值一定等于 [limit]。
     *
     * 存在的理由：`module.prop` 的大小在没读之前不可信，而 `readBytes()` 会一直读到 EOF。
     */
    private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream(minOf(limit, 8 * 1024))
        val chunk = ByteArray(8 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val read = input.read(chunk, 0, minOf(chunk.size, remaining))
            if (read <= 0) break
            buffer.write(chunk, 0, read)
            remaining -= read
        }
        return buffer.toByteArray()
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
