package com.sukisu.ultra.data.backup

import com.sukisu.ultra.data.model.Module
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileInputStream
import java.util.zip.ZipOutputStream

/**
 * 把已安装模块目录打成一个标准模块 zip：zip 根直接是 module.prop / system / service.sh 等，
 * 因此产物可以直接喂给 `ksud module install`。
 *
 * 不打包 disable / remove / update 这三个控制标记——它们是运行期状态，由备份的 meta 记录，
 * 恢复时再补写，避免"备份里的禁用状态被当成模块内容"这种歧义。
 *
 * 取源优先 `modules_update`：ksud 安装/升级先把内容解到那里，只在 `modules/<id>` 留
 * module.prop 与 update 标记，重启时才整体 rename 进 `modules`。安装后自动备份跑在重启之前，
 * 只读 `modules` 会备出"只有 module.prop 的空壳"（新装）或"升级前的旧文件"（升级），
 * 而且顶着新 versionCode 的文件名上报成功。
 */
class ZipModuleArchiver(private val staging: StagingArea) : ModuleArchiver {

    override suspend fun archive(module: Module): Result<ArchivedModule> = runCatching {
        staging.cleanupStale()
        val sourceRoot = sourceRootFor(module)
        require(sourceRoot.exists()) { "module directory missing: ${module.id}" }

        val outFile = staging.newFile("module-archive-", ".zip")
        ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
            sourceRoot.listFiles().orEmpty()
                .filterNot { it.name in CONTROL_MARKERS }
                .forEach { child -> addEntry(zip, child, child.name) }
        }
        ArchivedModule(outFile, outFile.length(), outFile.inputStream().use { it.sha256Hex() })
    }

    /**
     * 待安装目录只有在真的带 module.prop 时才算"新版本的内容"。
     *
     * 一次中断的安装会在 `modules_update/<id>` 留下空目录，只按"目录存在"取源会打出一个
     * 没有任何内容的 zip，却顶着新 versionCode 上报成功。
     */
    private fun sourceRootFor(module: Module): SuFile {
        val id = ArchiveNaming.safeId(module.id)
        val pending = SuFile("$MODULE_UPDATE_DIR/$id")
        if (pending.exists() && SuFile("$MODULE_UPDATE_DIR/$id/module.prop").exists()) return pending
        return SuFile("$MODULE_DIR/$id")
    }

    private fun addEntry(zip: ZipOutputStream, file: SuFile, entryPath: String) {
        if (file.isDirectory) {
            zip.putNextEntry(DeterministicZip.newEntry("$entryPath/"))
            zip.closeEntry()
            // 顺序也要固定：listFiles() 给的是文件系统顺序，同一份内容两次可能不同，打出来的
            // 字节不同、sha256 就不同，去重照样命不中。
            file.listFiles().orEmpty().sortedBy { it.name }.forEach { addEntry(zip, it, "$entryPath/${it.name}") }
            return
        }
        zip.putNextEntry(DeterministicZip.newEntry(entryPath))
        SuFileInputStream.open(file).use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private companion object {
        const val MODULE_DIR = "/data/adb/modules"
        const val MODULE_UPDATE_DIR = "/data/adb/modules_update"
        val CONTROL_MARKERS = setOf("disable", "remove", "update")
    }
}
