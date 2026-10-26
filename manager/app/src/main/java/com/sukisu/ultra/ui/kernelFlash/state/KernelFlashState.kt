package com.sukisu.ultra.ui.kernelFlash.state

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.kernelFlash.util.AssetsUtil
import com.sukisu.ultra.ui.kernelFlash.util.KpmPatchCommand
import com.sukisu.ultra.ui.kernelFlash.util.RemoteToolsDownloader
import com.sukisu.ultra.ui.util.getRootShell
import com.sukisu.ultra.ui.util.install
import com.sukisu.ultra.ui.util.rootAvailable
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


/**
 * @author ShirkNeko
 * @date 2025/5/31.
 */
data class FlashState(
    val isFlashing: Boolean = false,
    val isCompleted: Boolean = false,
    val progress: Float = 0f,
    val currentStep: String = "",
    val logs: List<String> = emptyList(),
    val error: String = ""
)

class HorizonKernelState {
    private val _state = MutableStateFlow(FlashState())
    val state: StateFlow<FlashState> = _state.asStateFlow()

    fun updateProgress(progress: Float) {
        _state.update { it.copy(progress = progress) }
    }

    fun updateStep(step: String) {
        _state.update { it.copy(currentStep = step) }
    }

    fun addLog(log: String) {
        _state.update {
            it.copy(logs = it.logs + log)
        }
    }

    fun setError(error: String) {
        _state.update { it.copy(isFlashing = false, isCompleted = false, error = error) }
    }

    fun tryStartFlashing(): Boolean {
        val current = _state.value
        return !(current.isFlashing || current.isCompleted || current.error.isNotEmpty()) && _state.compareAndSet(current, FlashState(isFlashing = true))
    }

    fun completeFlashing() {
        _state.update { it.copy(isFlashing = false, isCompleted = true, progress = 1f) }
    }
}

class HorizonKernelWorker(
    private val context: Context,
    private val state: HorizonKernelState,
    private val uri: Uri,
    private val slot: String? = null,
    private val kpmPatchEnabled: Boolean = false,
    private val kpmUndoPatch: Boolean = false
) : Thread() {
    private val sessionDir = File(context.filesDir, "kernel_flash")
    private val filePath = File(sessionDir, "kernel.zip").absolutePath
    private val binaryPath = File(sessionDir, "META-INF/com/google/android/update-binary").absolutePath
    private val workDir = File(sessionDir, "work").absolutePath
    private var originalSlot: String? = null

    override fun run() {
        var failure: Exception? = null
        try {
            state.updateStep(context.getString(R.string.horizon_preparing))
            state.updateStep(context.getString(R.string.horizon_cleaning_files))
            state.updateProgress(0.1f)
            if (!rootAvailable()) {
                throw IOException(context.getString(R.string.root_required))
            }
            require(slot == null || slot == "a" || slot == "b") { "Invalid target slot" }
            cleanup()
            if (!sessionDir.mkdirs()) throw IOException("Failed to create flash directory")

            state.updateStep(context.getString(R.string.horizon_copying_files))
            state.updateProgress(0.2f)
            copy()

            if (!File(filePath).exists()) {
                throw IOException(context.getString(R.string.horizon_copy_failed))
            }

            state.updateStep(context.getString(R.string.horizon_extracting_tool))
            state.updateProgress(0.4f)
            getBinary()

            // KPM修补
            if (kpmPatchEnabled || kpmUndoPatch) {
                state.updateStep(context.getString(R.string.kpm_preparing_tools))
                state.updateProgress(0.5f)
                prepareKpmToolsWithDownload()

                state.updateStep(
                    if (kpmUndoPatch) context.getString(R.string.kpm_undoing_patch)
                    else context.getString(R.string.kpm_applying_patch)
                )
                state.updateProgress(0.55f)
                performKpmPatch()
            }

            state.updateStep(context.getString(R.string.horizon_patching_script))
            state.updateProgress(0.6f)
            patch()

            state.updateStep(context.getString(R.string.horizon_flashing))
            state.updateProgress(0.7f)

            try {
                if (isAbDevice() && slot != null) {
                    state.updateStep(context.getString(R.string.horizon_getting_original_slot))
                    state.updateProgress(0.72f)
                    originalSlot = ShellUtils.fastCmd(getRootShell(), "getprop ro.boot.slot_suffix")
                        .trim().takeIf { it == "_a" || it == "_b" }
                        ?: throw IOException("Unable to read original slot")
                    state.updateStep(context.getString(R.string.horizon_setting_target_slot))
                    state.updateProgress(0.74f)
                    requireCommand(true, "resetprop -n ro.boot.slot_suffix _$slot")
                }
                flash()
            } finally {
                originalSlot?.let {
                    state.updateStep(context.getString(R.string.horizon_restoring_original_slot))
                    requireCommand(true, "resetprop ro.boot.slot_suffix ${shellQuote(it)}")
                }
            }

            try {
                install()
            } catch (e: Exception) {
                state.updateStep("ksud update skipped: ${e.message}")
            }
        } catch (e: Exception) {
            failure = e
        } finally {
            try {
                cleanup()
            } catch (e: Exception) {
                state.addLog("Failed to clean flash directory: ${e.message}")
            }
            val error = failure
            if (error != null) {
                state.setError(error.message ?: context.getString(R.string.horizon_unknown_error))
            } else {
                state.updateStep(context.getString(R.string.horizon_flash_complete_status))
                state.completeFlashing()
            }
        }
    }

    private fun prepareKpmToolsWithDownload() {
        try {
            File(workDir).mkdirs()
            val downloader = RemoteToolsDownloader(context, workDir)

            val progressListener = object : RemoteToolsDownloader.DownloadProgressListener {
                override fun onProgress(fileName: String, progress: Int, total: Int) {
                    val percentage = if (total > 0) (progress.toLong() * 100 / total).toInt() else 0
                    state.addLog("Downloading $fileName: $percentage% ($progress/$total bytes)")
                }

                override fun onLog(message: String) {
                    state.addLog(message)
                }

                override fun onError(fileName: String, error: String) {
                    state.addLog("Warning: $fileName - $error")
                }

                override fun onSuccess(fileName: String, isRemote: Boolean) {
                    val source = if (isRemote) "remote" else "local"
                    state.addLog("✓ $fileName $source version prepared successfully")
                }
            }

            val results = runBlocking {
                downloader.downloadToolsAsync(progressListener)
            }
            results.forEach { (name, result) ->
                if (!result.success) {
                    throw IOException("Unable to prepare $name: ${result.errorMessage}")
                }
            }
            requireCommand(true, "chmod a+rx $workDir/kptools")
            state.addLog("KPM tools preparation completed, starting patch operation")
        } catch (e: Exception) {
            state.addLog("KPM tools preparation failed: ${e.message}")
            throw e
        }
    }

    /**
     * 执行KPM修补操作
     */
    private fun performKpmPatch() {
        try {
            // 创建临时解压目录
            val extractDir = "$workDir/extracted"
            File(extractDir).mkdirs()

            // 解压压缩包到临时目录
            val unzipResult = runCommand(true, "cd $extractDir && unzip -o \"$filePath\"")
            if (unzipResult != 0) {
                throw IOException(context.getString(R.string.kpm_extract_zip_failed))
            }

            // 查找Image文件
            val findImageResult = try {
                val shell = getRootShell()
                ShellUtils.fastCmd(shell, "find $extractDir -name '*Image*' -type f").trim()
            } catch (_: Exception) {
                throw IOException(context.getString(R.string.kpm_image_file_not_found))
            }
            if (findImageResult.isBlank()) {
                throw IOException(context.getString(R.string.kpm_image_file_not_found))
            }

            val imageFile = findImageResult.lines().first().trim()
            // AnyKernel3 stores its kernel as Image; keep the package filename and pass that exact path.
            val imageName = File(imageFile).name
            val imageDir = File(imageFile).parent ?: throw IOException("Invalid image path")

            state.addLog(context.getString(R.string.kpm_found_image_file, imageFile))

            // 复制KPM工具到Image文件所在目录
            requireCommand(true, "cp ${shellQuote("$workDir/kptools")} ${shellQuote("$imageDir/")}")
            requireCommand(true, "cp ${shellQuote("$workDir/kpimg")} ${shellQuote("$imageDir/")}")

            if (kpmUndoPatch) {
                // ---- 取消修补：返回值允许为空，失败时不覆盖原文件 ----
                // 仅当 kptools 执行成功且生成了 oImage 时才替换原 Image；
                // 命令末尾用 `[ -f oImage ] && mv ...` 保证原文件不被半成品覆盖。
                val undoCommand = "cd ${shellQuote(imageDir)} && chmod a+rx kptools && " +
                        KpmPatchCommand.build("./kptools", imageName, "kpimg", "oImage", true) + " && " +
                        "[ -f oImage ] && mv oImage ${shellQuote(imageName)}"

                val patchResult: Int? = runCommand(true, undoCommand)
                if (patchResult == null) {
                    state.addLog("${context.getString(R.string.kpm_undo_patch_failed)} (no exit code)")
                } else if (patchResult != 0) {
                    state.addLog(context.getString(R.string.kpm_undo_patch_failed))
                } else {
                    state.addLog(context.getString(R.string.kpm_undo_patch_success))
                }
            } else {
                // ---- 打补丁：必须成功 ----
                val patchCommand = "cd ${shellQuote(imageDir)} && chmod a+rx kptools && " +
                        KpmPatchCommand.build("./kptools", imageName, "kpimg", "oImage", false) + " && [ -s oImage ] && mv oImage ${shellQuote(imageName)}"

                val patchResult = runCommand(true, patchCommand)
                if (patchResult != 0) {
                    throw IOException(context.getString(R.string.kpm_patch_failed))
                }
                state.addLog(context.getString(R.string.kpm_patch_success))
            }

            // 清理KPM工具文件
            requireCommand(true, "rm -f ${shellQuote("$imageDir/kptools")} ${shellQuote("$imageDir/kpimg")} ${shellQuote("$imageDir/oImage")}")

            // 重新打包ZIP文件
            val originalFileName = File(filePath).name
            val patchedFilePath = "$workDir/patched_$originalFileName"

            repackZipFolder(extractDir, patchedFilePath)

            // 替换原始文件
            requireCommand(true, "mv \"$patchedFilePath\" \"$filePath\"")

            state.addLog(context.getString(R.string.kpm_file_repacked))

        } catch (e: Exception) {
            state.addLog(context.getString(R.string.kpm_patch_operation_failed, e.message))
            throw e
        } finally {
            // 清理临时文件
            runCommand(true, "rm -rf $workDir")
        }
    }

    private fun repackZipFolder(sourceDir: String, zipFilePath: String) {
        try {
            val buffer = ByteArray(1024)
            val sourceFolder = File(sourceDir)

            FileOutputStream(zipFilePath).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    sourceFolder.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            val relativePath = file.relativeTo(sourceFolder).path
                            val zipEntry = ZipEntry(relativePath)
                            zos.putNextEntry(zipEntry)

                            file.inputStream().use { fis ->
                                var length: Int
                                while (fis.read(buffer).also { length = it } > 0) {
                                    zos.write(buffer, 0, length)
                                }
                            }

                            zos.closeEntry()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            throw IOException("Failed to create zip file: ${e.message}", e)
        }
    }

    // 检查设备是否为AB分区设备
    private fun isAbDevice(): Boolean {
        return try {
            val shell = getRootShell()
            val abUpdate = ShellUtils.fastCmd(shell, "getprop ro.build.ab_update").trim()
            if (!abUpdate.toBoolean()) return false

            val slotSuffix = ShellUtils.fastCmd(shell, "getprop ro.boot.slot_suffix").trim()
            slotSuffix.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun cleanup() {
        if (sessionDir.exists()) {
            requireCommand(true, "rm -rf ${shellQuote(sessionDir.absolutePath)}")
        }
    }

    private fun copy() {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException(context.getString(R.string.horizon_copy_failed))
        input.use {
            FileOutputStream(filePath).use { output -> it.copyTo(output) }
        }
    }

    private fun getBinary() {
        requireCommand(false, "unzip ${shellQuote(filePath)} '*/update-binary' -d ${shellQuote(sessionDir.absolutePath)}")
        if (!File(binaryPath).exists()) {
            throw IOException("Failed to extract update-binary")
        }
    }

    @SuppressLint("StringFormatInvalid")
    private fun patch() {
        val kernelVersion = try {
            val shell = getRootShell()
            ShellUtils.fastCmd(shell, "cat /proc/version")
        } catch (_: Exception) {
            ""
        }
        val versionRegex = """\d+\.\d+\.\d+""".toRegex()
        val version = kernelVersion.let { versionRegex.find(it) }?.value ?: ""
        val toolName = if (version.isNotEmpty()) {
            val parts = version.split('.')
            if (parts.size >= 2) {
                val major = parts[0].toIntOrNull() ?: 0
                val minor = parts[1].toIntOrNull() ?: 0
                if (major < 5 || (major == 5 && minor <= 10)) "5_10" else "5_15+"
            } else {
                "5_15+"
            }
        } else {
            "5_15+"
        }
        val toolPath = "${sessionDir.absolutePath}/mkbootfs"
        AssetsUtil.exportFiles(context, "$toolName-mkbootfs", toolPath)
        state.addLog("${context.getString(R.string.kernel_version_log, version)} ${context.getString(R.string.tool_version_log, toolName)}")
        requireCommand(false,
            $$"sed -i '/chmod -R 755 tools bin;/i cp -f $$toolPath $AKHOME/tools;' $$binaryPath"
        )
    }

    private fun flash() {
        val process = ProcessBuilder("su")
            .redirectErrorStream(true)
            .start()

        try {
            process.outputStream.bufferedWriter().use { writer ->
                writer.write("export POSTINSTALL=${shellQuote(sessionDir.absolutePath)}\n")

                // 写入槽位信息到临时文件
                slot?.let { selectedSlot ->
                    writer.write("echo \"$selectedSlot\" > ${sessionDir.absolutePath}/bootslot\n")
                }

                // 构建刷写命令
                val flashCommand = buildString {
                    append("sh ${shellQuote(binaryPath)} 3 1 ${shellQuote(filePath)}")
                    if (slot != null) {
                        append(" \"$(cat ${sessionDir.absolutePath}/bootslot)\"")
                    }
                    append("\n")
                }

                writer.write(flashCommand)
                writer.write("exit $?\n")
                writer.flush()
            }

            process.inputStream.bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    if (line.startsWith("ui_print")) {
                        val logMessage = line.removePrefix("ui_print").trim()
                        state.addLog(logMessage)

                        when {
                            logMessage.contains("extracting", ignoreCase = true) -> {
                                state.updateProgress(0.75f)
                            }
                            logMessage.contains("installing", ignoreCase = true) -> {
                                state.updateProgress(0.85f)
                            }
                            logMessage.contains("complete", ignoreCase = true) -> {
                                state.updateProgress(0.95f)
                            }
                        }
                    }
                }
            }
            if (process.waitFor() != 0) {
                throw IOException(context.getString(R.string.flash_failed_message))
            }
        } finally {
            process.destroy()
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private fun requireCommand(su: Boolean, cmd: String) {
        if (runCommand(su, cmd) != 0) throw IOException("Command failed: $cmd")
    }

    private fun runCommand(su: Boolean, cmd: String): Int? {
        return try {
            val process = ProcessBuilder(if (su) "su" else "sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            try {
                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { state.addLog(it) }
                }
                process.waitFor()
            } finally {
                process.destroy()
            }
        } catch (e: Exception) {
            state.addLog("Command failed: ${e.message}")
            null
        }
    }
}
