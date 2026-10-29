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
    data class Usage(val entries: Int, val totalBytes: Long)

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
        onFailure: (Throwable) -> Unit = {},
    ): Entry? {
        val entry = runCatching {
            // 每一格「没留存」都要落进 onFailure：以前这四格写的是 `return null`——
            // 在 runCatching 的 lambda 里那是**非局部返回**，直接跳出了整个 store()，
            // 外面的 getOrElse 根本不执行，于是 onFailure 永远收不到回调，
            // 调用方只能靠「文件不见了」猜原因。
            check(source.isFile && source.length() > 0L) {
                "源文件不存在或是空文件：${source.name}"
            }
            val meta = ModuleProp.parseModuleProp(source)
                ?: throw IllegalStateException("读不出模块信息（module.prop 缺 id / name）：${source.name}")
            val target = fileFor(meta.id)
            root.mkdirs()
            // 改名与复制都没成（跨分区、目标目录不可写、空间不足）
            check(place(source, target, consumeSource)) { "写入 ${target.name} 失败" }
            target.setLastModified(now)
            prune(keep = target)
            // prune() 可能把刚存进去的那一份也淘汰掉（单包就超过总上限时）：
            // 文件都没了还返回一个 Entry，等于让调用方以为留存成功、点开却失败。
            check(target.isFile) { "留存后立刻被淘汰：${target.name}" }
            Entry(
                id = meta.id,
                file = target,
                meta = meta,
                sizeBytes = target.length(),
                storedAt = target.lastModified(),
            )
        }
        return entry.getOrElse { error ->
            // 本类不引 android.util.Log（要能在纯 JVM 单测里跑），失败原因交给调用方处理。
            onFailure(error)
            null
        }
    }

    /**
     * 把 [source] 落到 [target]，成功返回 true。
     *
     * 写侧一律先落临时名再改名，装到一半被杀掉时不会留下一个「名字对但内容半截」的包。
     * 临时名与备份名都不以 `.zip` 结尾，[ids]/[usage]/[prune] 的 `.zip` 过滤天然把它们
     * 排除在外，不会污染计数与淘汰。
     *
     * 顶掉旧包时先把它改名成备份、新包就位后再删备份：任何一步失败都能把旧包还回去。
     * 旧版直接 `target.delete()` 再写，复制失败就等于「旧包没了、新包也没到」。
     */
    private fun place(source: File, target: File, consumeSource: Boolean): Boolean {
        val temp = File.createTempFile("incoming-", PART_SUFFIX, root)
        return try {
            // 先把 createTempFile 建出来的空文件删掉：Windows 上 renameTo 到一个已存在的
            // 目标会直接失败（POSIX 则是覆盖），留着它会让下面的改名恒走复制分支，
            // 「搬走源文件」的语义就悄悄退化成「复制」，源文件留在原地。
            temp.delete()
            // consumeSource 优先走改名（同一分区比再读一遍几十 MB 便宜）；
            // 移动失败（跨文件系统等）退回复制，不改变语义。
            val staged = if (consumeSource && source.renameTo(temp)) {
                true
            } else {
                source.copyTo(temp, overwrite = true)
                temp.isFile && temp.length() > 0L
            }
            staged && replace(temp, target)
        } catch (e: Throwable) {
            false
        } finally {
            if (temp.exists()) runCatching { temp.delete() }
        }
    }

    /** 用已写好的 [temp] 顶掉 [target]；失败时把旧包恢复回去。 */
    private fun replace(temp: File, target: File): Boolean {
        val backup = File(root, target.name + BACKUP_SUFFIX)
        val hadTarget = target.exists()
        if (hadTarget) {
            runCatching { backup.delete() }
            if (!target.renameTo(backup)) return false
        }
        if (temp.renameTo(target)) {
            runCatching { backup.delete() }
            return true
        }
        // 新包没就位：把旧包还回去，别让这次留存把上一份也一起弄丢。
        if (hadTarget) runCatching { backup.renameTo(target) }
        return false
    }

    /** 丢掉这个 id 留存的包（用户在 App 里清了数据、或留存包被判废时）。 */
    fun remove(id: String): Boolean = runCatching { fileFor(id).delete() }.getOrDefault(false)

    /**
     * 按上限淘汰：条目数或总字节超了，就从最后修改时间最早的那份开始删。
     *
     * 用 lastModified 而不是另存一份时间戳：文件本身就是唯一事实来源，重装会刷新它。
     *
     * [keep] 是刚存进来的那一份：哪怕它一份就超过了总上限，也不能当场淘汰——否则
     * 用户刚装完的包立刻消失，而 [store] 返回的 Entry 指向一个已经不存在的文件。
     * 真装不下就让它成为唯一的幸存者，下一次存别的包时再按最旧淘汰。
     */
    fun prune(keep: File? = null) {
        val files = runCatching {
            root.listFiles { file -> file.isFile && file.name.endsWith(".zip") }
                ?.sortedBy { it.lastModified() }
        }.getOrNull() ?: return
        var total = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            if (count <= limits.maxEntries && total <= limits.maxTotalBytes) break
            // 只剩它自己时就别再删了：留存目录空着也不比「留着这一份」更符合预期。
            if (keep != null && file == keep && count <= 1) break
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                count--
                total -= size
            }
        }
    }

    /**
     * 清空整个留存目录，返回**实际**释放的字节数。
     *
     * 返回值是逐份累加删成功的那些，而不是清空前 stat 一遍的总和：删不掉的（被占用、
     * 权限异常）不该算进「已释放」，否则用户看到的数字和实际腾出的空间对不上。
     */
    fun clear(): Long {
        var freed = 0L
        runCatching {
            root.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val size = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) freed += size
            }
        }
        return freed
    }

    /**
     * 删掉所有不属于 [keep] 的留存包，返回**实际**释放的字节数。
     *
     * 为什么需要它：留存的包按 id 落盘，与「当前装着哪些模块」无关。用户卸载一个模块后，
     * 那份包既没有列表行可以点「用留存的包」，也不会被 [prune] 主动淘汰（它只在超上限时
     * 才动手），于是一直占着空间，用户唯一的出路是「清空全部」——连带删掉他还想留的那些。
     * 这个方法给出第三条路：只回收已卸载模块的那几份。
     *
     * [keep] 传的是「当前安装的模块 id」，调用方负责先过 [safeId]；这里再比一次文件名形态，
     * 免得 `a/b` 与 `a_b` 这类被压过字符的 id 认错。
     */
    fun clearExcept(keep: Set<String>): Long {
        var freed = 0L
        runCatching {
            root.listFiles()?.forEach { file ->
                if (!file.isFile || !file.name.endsWith(".zip")) return@forEach
                if (file.name.removeSuffix(".zip") in keep) return@forEach
                val size = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) freed += size
            }
        }
        return freed
    }

    private fun fileFor(id: String): File = File(root, fileNameFor(id))

    companion object {
        /** 留存目录名（filesDir 下）。备份规则里要排除它，见 res/xml/backup_rules.xml。 */
        const val DIR_NAME = "module_zips"

        /** 落盘临时名后缀。刻意不以 `.zip` 结尾：`.zip` 过滤会把它当正式条目。 */
        private const val PART_SUFFIX = ".part"

        /** 顶掉旧包时的备份名后缀，同上。 */
        private const val BACKUP_SUFFIX = ".bak"

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
