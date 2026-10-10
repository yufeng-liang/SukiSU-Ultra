package com.sukisu.ultra.data.backup

import java.io.File

data class RootFileEntry(val path: String, val sizeBytes: Long, val lastModifiedEpochMs: Long)

/**
 * 需要 root 权限的文件操作。抽成接口是为了让 [LocalBackupStorage] 能在 JVM 单测里跑。
 * 所有方法都不抛异常，失败返回 false / 空集合。
 */
interface RootFiles {
    fun mkdirs(path: String): Boolean
    fun copyTo(from: File, toPath: String): Boolean
    fun copyFrom(path: String, to: File): Boolean

    /**
     * 同目录改名（`mv`），用来把写完的临时文件换到目标名下。
     *
     * `cp` 是先截断目标再写，中途断掉（进程被杀、断电、空间满）留下的是半截文件；改名由文件系统
     * 保证原子，读者只会看到旧内容或新内容。索引就靠这个性质活着：一份半截索引读不出来，而引擎
     * 读不出来就拒绝回写，用户看到的是"所有备份都没了"。
     */
    fun rename(fromPath: String, toPath: String): Boolean

    fun list(dir: String): List<RootFileEntry>
    fun delete(path: String): Boolean
    fun exists(path: String): Boolean
}
