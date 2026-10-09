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
    fun list(dir: String): List<RootFileEntry>
    fun delete(path: String): Boolean
    fun exists(path: String): Boolean
}
