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

    override suspend fun install(zip: File): Result<Unit> = runCatching {
        val result = flashModule(Uri.fromFile(zip), onStdout = {}, onStderr = {})
        check(result.code == 0) { result.err.ifBlank { "ksud module install failed with code ${result.code}" } }
    }

    override suspend fun setDisabled(id: String, disabled: Boolean): Result<Unit> = runCatching {
        val verb = if (disabled) "disable" else "enable"
        check(execKsud("module $verb $id")) { "ksud module $verb $id failed" }
    }
}
