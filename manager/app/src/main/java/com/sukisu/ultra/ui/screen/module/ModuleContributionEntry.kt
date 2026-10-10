package com.sukisu.ultra.ui.screen.module

import android.content.Context
import android.net.Uri
import com.sukisu.ultra.data.backup.StagingArea
import com.sukisu.ultra.data.model.Module
import com.sukisu.ultra.ksuApp
import com.sukisu.ultra.ui.util.CheckStatus
import com.sukisu.ultra.ui.util.ModuleContribution
import com.sukisu.ultra.ui.util.ModuleProp
import com.sukisu.ultra.ui.util.ModulePropInfo
import com.sukisu.ultra.ui.util.SubmitResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * 投稿入口的三件杂事：把已安装模块打包成 zip、查收录状态、以及真正的提交。
 *
 * 之所以要打包：投稿要传的是**模块文件本身**，而列表里的 [Module] 只是
 * `ksud module list` 报上来的一条元数据，/data/adb/modules 下没有现成的 zip。
 * 仓库里已经有一份"把已安装模块打成可被 ksud 直接安装的 zip"的实现
 * （[com.sukisu.ultra.data.backup.ZipModuleArchiver]，备份功能天天在用），这里复用它，
 * 不写第二套：两套实现迟早会在"取 modules 还是 modules_update"这种地方分叉，
 * 而分叉的结果是用户投稿上去一个只有 module.prop 的空壳。
 */
object ModuleContributionEntry {

    /** 投稿用的临时 zip 落在这里，与备份的 staging 分开，互不清理。 */
    private fun staging(): StagingArea = StagingArea(File(ksuApp.cacheDir, "contribution-staging"))

    /**
     * 把 [versionCode] 转成 [ModuleContribution.checkRecorded] 要的 Int。
     *
     * `ksud module list` 给的是 Long，而契约签名是 Int。超出 Int 范围的**不截断**——
     * 截断会让两个不同版本查到同一个收录结果——直接当作查不了（null）。
     */
    fun checkableVersionCode(versionCode: Long): Int? =
        if (versionCode < Int.MIN_VALUE || versionCode > Int.MAX_VALUE) null else versionCode.toInt()

    /** zip 文件名。id 里出现 `/` 会让 `renameTo` 静默失败，所以先压平。 */
    fun zipNameFor(id: String, versionCode: Long): String =
        "${id.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "module" }}-$versionCode.zip"

    /**
     * 为 [module] 备好一份待投稿的 zip。
     *
     * 返回 null 表示"这一刻打不出包"（模块目录没了、root 不可用等），
     * 调用方按"静默失败"处理。
     */
    suspend fun stageZip(module: Module): File? = withContext(Dispatchers.IO) {
        runCatching {
            val staging = staging()
            staging.cleanupStale()
            val archived = com.sukisu.ultra.data.backup.ZipModuleArchiver(staging)
                .archive(module)
                .getOrNull() ?: return@withContext null
            val target = File(archived.file.parentFile, zipNameFor(module.id, module.versionCode))
            if (target.exists()) target.delete()
            if (archived.file.renameTo(target)) target else archived.file
        }.getOrNull()
    }

    /** 从待投稿的 zip 里读出 module.prop（用于预填表单）。 */
    suspend fun readProp(zip: File): ModulePropInfo? = withContext(Dispatchers.IO) {
        runCatching { ModuleProp.parseModuleProp(zip) }.getOrNull()
    }

    /**
     * 查这一版有没有被索引收录。
     *
     * 任何失败（含 [checkableVersionCode] 返回 null、网络异常）都返回 null，
     * 与 [CheckStatus.UNKNOWN] 在调用方是同一件事：静默。
     */
    suspend fun check(module: Module): CheckStatus? = withContext(Dispatchers.IO) {
        val versionCode = checkableVersionCode(module.versionCode) ?: return@withContext null
        runCatching { ModuleContribution.checkRecorded(module.id, versionCode) }.getOrNull()
    }

    /**
     * 提交投稿。
     *
     * [source] 与 [uploader] 的非空校验不在这里做（[ModuleContribution.submitModule]
     * 会挡住并给出中文原因），但表单在 UI 层已经先挡了一道。
     */
    suspend fun submit(
        zip: File,
        meta: ModulePropInfo?,
        source: String,
        uploader: String,
    ): SubmitResult = ModuleContribution.submitModule(zip, meta, source, uploader)

    /**
     * 把一个 uri 拷成投稿用的 zip。
     *
     * 用于"刚从本地装上的那一份"这条路径：那时 ksud 已经把它解到模块目录里，
     * 但投稿要的是原始 zip，所以趁 uri 还有效先留一份。
     */
    suspend fun copyToZip(context: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
        runCatching {
            val staging = staging()
            staging.cleanupStale()
            val file = staging.newFile("contribute-", ".zip")
            val input: InputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext null
            input.use { it.copyTo(file.outputStream()) }
            file
        }.getOrNull()
    }
}
