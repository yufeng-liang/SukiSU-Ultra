package com.sukisu.ultra.data.modulezip

import com.sukisu.ultra.ksuApp
import com.sukisu.ultra.ui.util.ModuleProp
import com.sukisu.ultra.ui.util.ModulePropInfo
import java.io.File

/**
 * 安装模块时留一份原始 zip，供上传页复用。
 *
 * 为什么留**字节**而不是记路径：安装入口走 SAF（`ACTION_OPEN_DOCUMENT`），全仓没有
 * `takePersistableUriPermission`，那次读权限只活到进程结束；把 uri 记下来，用户过一会儿
 * 回来点「上传」只会拿到一个打不开的地址。而 `flashModule` 本来就要把包复制到 cacheDir
 * 再交给 ksud，装完顺手把那份副本搬进这里，等于「不删临时文件」的成本。
 *
 * 布局：`{root}/{sanitize(id)}.zip`，一个 id 只留最新一次安装的那份（重复安装即覆盖）。
 * 不建索引文件：目录本身就是索引，省掉「索引说有条目、文件却没了」这类漂移；
 * 版本号等信息按需从 zip 里的 module.prop 读（只在用户真的要用时才读一次）。
 *
 * 代价是占空间，所以有双上限（[Limits]）：条目数 + 总字节，超了按最后修改时间淘汰最旧的。
 * 这里不依赖任何 Android API 的具体行为，[root] 由调用方给，单测可以直接用临时目录。
 */
class ModuleZipArchive(
    val root: File,
    private val limits: Limits = Limits(),
) {

    /** 容量上限：条目数与总字节双限，任一超出即按最旧淘汰。 */
    data class Limits(
        val maxEntries: Int = 16,
        val maxTotalBytes: Long = 256L * 1024 * 1024,
    )

    /** 一条已留存的原始包。[meta] 来自 zip 里的 module.prop。 */
    data class Entry(
        val id: String,
        val file: File,
        val meta: ModulePropInfo,
        val sizeBytes: Long,
        val storedAt: Long,
    )

    /**
     * 当前占用情况，给「清理留存的包」那个入口显示用。
     *
     * 只 stat、不解压：用户只是想知道「占了多少」，没理由为此读一遍每个 zip 的 module.prop。
     */
    data class Usage(val entries: Int, val totalBytes: Long) {
        val isEmpty: Boolean get() = entries == 0
    }

    /** 算一遍当前占用。目录不存在时是 (0, 0)，不是异常。 */
    fun usage(): Usage {
        val files = runCatching {
            root.listFiles { file -> file.isFile && file.name.endsWith(".zip") }
        }.getOrNull() ?: return Usage(0, 0L)
        return Usage(entries = files.size, totalBytes = files.sumOf { it.length() })
    }

    /**
     * 这个 id 有没有留存的包。
     *
     * 只 stat 一次文件在不在、不解压：列表里逐行问的时候必须便宜——几十行都去读
     * module.prop 会让「进页面」变成一次卡顿。真正要用时走 [entry] 才解析。
     */
    fun has(id: String): Boolean = fileFor(id).isFile

    /**
     * 当前留存的全部 id。列表页一进来问一次，用来决定每行显示「用留存的包」还是「手动挑」。
     *
     * 只读目录名、不打开 zip：一屏几十行都去解压 module.prop 是不可接受的。
     */
    fun ids(): Set<String> = runCatching {
        root.listFiles { file -> file.isFile && file.name.endsWith(".zip") }
            ?.mapTo(mutableSetOf()) { it.name.removeSuffix(".zip") }
            ?: emptySet()
    }.getOrDefault(emptySet())

    /**
     * 取这个 id 留存的包；没有、或包已损坏（读不出 module.prop / id 对不上）都返回 null。
     *
     * 损坏的那份顺手删掉：留着它每次点都会失败一次，而且它已经不可能被用上了。
     */
    fun entry(id: String): Entry? {
        val file = fileFor(id)
        if (!file.isFile) return null
        val meta = ModuleProp.parseModuleProp(file)
        if (meta == null || meta.id != id) {
            // 损坏的那份直接删：留着它每次点都会失败一次，而它已经不可能被用上了。
            // 这里不打日志——本类要能在纯 JVM 单测里跑，不引 android.util.Log。
            runCatching { file.delete() }
            return null
        }
        return Entry(
            id = id,
            file = file,
            meta = meta,
            sizeBytes = file.length(),
            storedAt = file.lastModified(),
        )
    }

    /**
     * 把刚装成功的这份 zip 留下来，返回落地后的条目（失败返回 null）。
     *
     * 调用方传的是 ksud 装的那份临时副本。[consumeSource] 为 true 时优先**移动**而不是复制：
     * 那份副本本来就由 `flashModule` 的 finally 删掉，同一个分区上改名比再读一遍几十 MB 便宜得多；
     * 移动失败（跨文件系统等）自动退回复制，不改变语义。
     *
     * 整个动作 best-effort：留存失败不该让一次已经成功的安装看起来失败。
     */
    fun store(
        source: File,
        now: Long = System.currentTimeMillis(),
        consumeSource: Boolean = false,
    ): Entry? = runCatching {
        if (!source.isFile || source.length() <= 0L) return null
        val meta = ModuleProp.parseModuleProp(source) ?: return null
        val target = fileFor(meta.id)
        root.mkdirs()
        if (!place(source, target, consumeSource)) return null
        target.setLastModified(now)
        prune()
        Entry(
            id = meta.id,
            file = target,
            meta = meta,
            sizeBytes = target.length(),
            storedAt = target.lastModified(),
        )
    }.getOrNull()

    /**
     * 把 [source] 落到 [target]，成功返回 true。
     *
     * 先删旧目标：同一模块重装就是覆盖，而「重命名到一个已存在的文件」在各平台上行为不一致
     * （POSIX 覆盖、Windows 失败），不能指望它。写侧一律走临时名再改名，装到一半被杀掉时
     * 不会留下一个「名字对但内容半截」的包。
     */
    private fun place(source: File, target: File, consumeSource: Boolean): Boolean {
        if (target.exists() && !target.delete()) {
            // 删不掉旧的（极罕见）：别覆盖出半个文件，放弃这次留存。
            return false
        }
        if (consumeSource && source.renameTo(target)) return true
        val temp = File.createTempFile("incoming-", ".zip", root)
        return try {
            source.copyTo(temp, overwrite = true)
            temp.renameTo(target)
        } finally {
            if (temp.exists()) runCatching { temp.delete() }
        }
    }

    /** 丢掉这个 id 留存的包（用户在 App 里清了数据、或留存包被判废时）。 */
    fun remove(id: String): Boolean = runCatching { fileFor(id).delete() }.getOrDefault(false)

    /**
     * 按上限淘汰：条目数或总字节超了，就从最后修改时间最早的那份开始删。
     *
     * 用 lastModified 而不是另存一份时间戳：文件本身就是唯一事实来源，重装会刷新它。
     */
    fun prune() {
        val files = runCatching {
            root.listFiles { file -> file.isFile && file.name.endsWith(".zip") }
                ?.sortedBy { it.lastModified() }
        }.getOrNull() ?: return
        var total = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            if (count <= limits.maxEntries && total <= limits.maxTotalBytes) break
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                count--
                total -= size
            }
        }
    }

    /** 清空整个留存目录（设置里「清掉留存的原始包」用得上）。 */
    fun clear() {
        runCatching {
            root.listFiles()?.forEach { file -> if (file.isFile) file.delete() }
        }
    }

    private fun fileFor(id: String): File = File(root, fileNameFor(id))

    companion object {
        /** 留存目录名（filesDir 下）。备份规则里要排除它，见 res/xml/backup_rules.xml。 */
        const val DIR_NAME = "module_zips"

        /**
         * 把 id 里不能进文件名的字符压成 `_`。
         *
         * module id 本来就被 ksud 限定在 `[a-zA-Z][a-zA-Z0-9._-]+`，这里只是防御——
         * 万一将来放宽，也不能让 `../` 之类的 id 把文件写到目录外去。
         *
         * [ids] 返回的就是这个形态，调用方比对 id 时也得先过一遍它，否则遇到被替换过
         * 字符的 id 会「文件明明在、却判定没留存」。
         */
        fun safeId(id: String): String =
            id.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "module" }

        /** 留存文件名：`{safeId}.zip`。 */
        fun fileNameFor(id: String): String = "${safeId(id)}.zip"

        /** App 里的那一份（`filesDir/module_zips`）。 */
        fun default(): ModuleZipArchive =
            ModuleZipArchive(File(ksuApp.filesDir, DIR_NAME))
    }
}
