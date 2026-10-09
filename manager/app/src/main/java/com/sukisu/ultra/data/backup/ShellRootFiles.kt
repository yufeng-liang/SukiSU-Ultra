package com.sukisu.ultra.data.backup

import com.sukisu.ultra.ui.util.getRootShell
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile
import java.io.File

/**
 * 用 libsu 以 root 身份做文件操作。
 * 目标目录在 /sdcard 下，属共享存储，app 自己（非 root）未必可写，因此统一走 root。
 */
class ShellRootFiles(private val shell: () -> Shell = { getRootShell() }) : RootFiles {

    private fun exec(command: String): Boolean =
        runCatching { shell().newJob().add(command).to(ArrayList(), null).exec().isSuccess }.getOrDefault(false)

    /**
     * 单引号包裹，并把路径里自带的单引号按 `'\''` 拆开。
     *
     * 这些命令以 root 身份经 `sh -c` 执行，路径又来自归档名/索引（外部可写），
     * 只做 `'$path'` 包裹的话，一个带单引号的文件名就能把命令接续出去。
     */
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    override fun mkdirs(path: String): Boolean = exec("mkdir -p ${quote(path)}")

    override fun copyTo(from: File, toPath: String): Boolean = exec("cp ${quote(from.absolutePath)} ${quote(toPath)}")

    override fun copyFrom(path: String, to: File): Boolean = exec("cp ${quote(path)} ${quote(to.absolutePath)}")

    override fun list(dir: String): List<RootFileEntry> {
        val suDir = SuFile(dir)
        if (!suDir.exists()) return emptyList()
        return suDir.listFiles().orEmpty().filter { it.isFile }.map {
            RootFileEntry(it.absolutePath, it.length(), it.lastModified())
        }
    }

    override fun delete(path: String): Boolean = exec("rm -f ${quote(path)}")

    override fun exists(path: String): Boolean = SuFile(path).exists()
}
