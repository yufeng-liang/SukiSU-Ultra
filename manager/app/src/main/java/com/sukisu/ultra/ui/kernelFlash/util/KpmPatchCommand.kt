package com.sukisu.ultra.ui.kernelFlash.util

/** Shared kptools-android invocation for AnyKernel3's `Image` and boot.img's extracted `kernel`. */
object KpmPatchCommand {
    internal fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Reject missing input and empty/stale output before a caller replaces its kernel. */
    fun buildChecked(
        tool: String,
        kernelInput: String,
        kpimg: String,
        kernelOutput: String,
        undo: Boolean,
    ): String {
        require(kernelInput != kernelOutput) { "KPM output must differ from the input" }
        return "[ -s ${quote(kernelInput)} ] && rm -f ${quote(kernelOutput)} && " +
            build(tool, kernelInput, kpimg, kernelOutput, undo) +
            " && [ -s ${quote(kernelOutput)} ]"
    }

    fun build(
        tool: String,
        kernelInput: String,
        kpimg: String,
        kernelOutput: String,
        undo: Boolean,
    ): String {
        return "${quote(tool)} ${if (undo) "-u" else "-p"} -s 123 " +
            "-i ${quote(kernelInput)} -k ${quote(kpimg)} -o ${quote(kernelOutput)}"
    }
}
