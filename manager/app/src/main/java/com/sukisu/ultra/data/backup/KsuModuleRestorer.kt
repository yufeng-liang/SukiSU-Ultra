package com.sukisu.ultra.data.backup

import android.net.Uri
import com.sukisu.ultra.ui.util.execKsud
import com.sukisu.ultra.ui.util.flashModule
import java.io.File

/**
 * 恢复走 ksud 官方安装流程（安装脚本、权限、SELinux、metamodule 校验都现成），
 * 不自己往 /data/adb/modules 里塞文件。
 */
class KsuModuleRestorer : ModuleRestorer {

    override suspend fun install(zip: File): Result<Unit> = runCatchingCancellable {
        // 恢复装的是我们自己用 ZipModuleArchiver 重新打出来的包，字节与作者发布的原始包不同：
        // 拿它覆盖上传页要用的「原始 zip」，等于把用户手里唯一一份正品换掉，所以不留存。
        val result = flashModule(Uri.fromFile(zip), onStdout = {}, onStderr = {}, archiveSource = false)
        // ksud 的 stderr 就是最有用的原因；它可能是空的，那就让上层只说"安装失败"，
        // 不要把 `IllegalStateException` 这种类名或者一句英文塞给用户。
        if (result.code != 0) throw IllegalStateException(result.err)
    }

    override suspend fun setDisabled(id: String, disabled: Boolean): Result<Unit> = runCatchingCancellable {
        // id 会被拼进以 root 身份执行的 `ksud module disable|enable <id>`，而它来自索引——索引
        // 躺在共享存储或远端 WebDAV 上，外部可写。形状不对就拒绝，不做"转义一下再拼"这种补救：
        // 清洗过的 id 看着正常，命令也照样是我们不想执行的那条。
        require(ArchiveNaming.isValidModuleId(id)) { "unusable module id" }
        val verb = if (disabled) "disable" else "enable"
        if (!execKsud("module $verb $id")) throw IllegalStateException()
    }
}
