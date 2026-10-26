package com.sukisu.ultra.ui.kernelFlash.util

import android.content.Context
import com.sukisu.ultra.ui.util.rootAvailable
import kotlinx.coroutines.runBlocking
import java.io.File

/** Shared tools and kernel patching; callers own boot/ZIP packaging and flashing. */
class KpmTools private constructor(
    val executable: File,
    private val kpimg: File,
    val launcher: String?,
) {
    val command: String
        get() = launcher?.let { "${KpmPatchCommand.quote(it)} ${KpmPatchCommand.quote(executable.absolutePath)}" }
            ?: KpmPatchCommand.quote(executable.absolutePath)

    fun patch(input: File, output: File, undo: Boolean, execute: (String) -> Unit) {
        require(input.canonicalFile != output.canonicalFile) { "KPM output must differ from the input" }
        execute(KpmPatchCommand.buildChecked(
            executable.absolutePath, input.absolutePath, kpimg.absolutePath,
            output.absolutePath, undo, launcher,
        ))
    }

    companion object {
        fun prepare(
            context: Context,
            directory: File,
            log: (String) -> Unit,
            execute: (String) -> Unit,
        ): KpmTools {
            val listener = object : RemoteToolsDownloader.DownloadProgressListener {
                override fun onProgress(fileName: String, progress: Int, total: Int) = Unit
                override fun onLog(message: String) = log(message)
                override fun onError(fileName: String, error: String) = log("Warning: $fileName: $error")
                override fun onSuccess(fileName: String, isRemote: Boolean) {
                    log("$fileName source: ${if (isRemote) "remote" else "bundled fallback"}")
                }
            }
            val results = runBlocking {
                RemoteToolsDownloader(context, directory.absolutePath).downloadToolsAsync(listener)
            }
            for (name in listOf("kptools", "kpimg")) {
                val result = results[name]
                check(result?.success == true) {
                    "Cannot prepare $name: ${result?.errorMessage ?: "missing download result"}"
                }
            }
            val executable = File(directory, "kptools")
            execute("chmod 700 ${KpmPatchCommand.quote(executable.absolutePath)}")
            // App storage cannot be executed directly without root on modern Android.
            val launcher = if (rootAvailable()) null else "/system/bin/linker64"
            if (launcher != null) check(File(launcher).isFile) { "System linker64 is unavailable" }
            log("KPM executable launcher: ${launcher ?: "direct (root)"}")
            return KpmTools(executable, File(directory, "kpimg"), launcher)
        }
    }
}
