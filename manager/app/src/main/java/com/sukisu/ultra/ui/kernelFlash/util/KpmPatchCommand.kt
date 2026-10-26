package com.sukisu.ultra.ui.kernelFlash.util

/** Shared kptools invocation for AnyKernel3's `Image` and boot.img's extracted `kernel`. */
object KpmPatchCommand {
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun build(
        tool: String,
        kernelInput: String,
        kpimg: String,
        kernelOutput: String,
        undo: Boolean,
        launcher: String? = null,
    ): String {
        val executable = launcher?.let { "${quote(it)} ${quote(tool)}" } ?: quote(tool)
        return "$executable ${if (undo) "-u" else "-p"} -s 123 " +
            "-i ${quote(kernelInput)} -k ${quote(kpimg)} -o ${quote(kernelOutput)}"
    }
}
