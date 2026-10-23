package com.sukisu.ultra.ui.screen.extensions

import androidx.annotation.StringRes
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.util.ExtensionState

internal enum class ExtensionPill {
    Unsupported,
    Forced,
    Managed,
    Enabled,
    Disabled,
    Supported,
}

internal data class ExtensionPillSpec(
    val pill: ExtensionPill,
    @StringRes val labelRes: Int,
    val isOnTertiary: Boolean = false,
    val isOnPrimary: Boolean = false,
    val isOnError: Boolean = false,
    val isOnSurfaceVariant: Boolean = false,
)

internal fun pillFor(state: ExtensionState): ExtensionPillSpec = when {
    !state.supported -> ExtensionPillSpec(
        pill = ExtensionPill.Unsupported,
        labelRes = R.string.config_status_unsupported,
        isOnError = true,
    )
    state.forced -> ExtensionPillSpec(
        pill = ExtensionPill.Forced,
        labelRes = R.string.extension_status_forced,
        isOnTertiary = true,
    )
    state.managed -> ExtensionPillSpec(
        pill = ExtensionPill.Managed,
        labelRes = R.string.extension_status_managed,
        isOnTertiary = true,
    )
    !state.id.hasStickyBit -> ExtensionPillSpec(
        pill = ExtensionPill.Supported,
        labelRes = R.string.extension_status_supported,
        isOnPrimary = true,
    )
    state.value != 0L -> ExtensionPillSpec(
        pill = ExtensionPill.Enabled,
        labelRes = R.string.config_status_applied,
        isOnPrimary = true,
    )
    else -> ExtensionPillSpec(
        pill = ExtensionPill.Disabled,
        labelRes = R.string.extension_status_disabled,
        isOnSurfaceVariant = true,
    )
}

internal data class ExtensionStatusLabels(
    val unsupported: String,
    val forced: String,
    val managed: String,
    val supported: String,
    val enabled: String,
    val disabled: String,
) {
    fun forPill(pill: ExtensionPill): String = when (pill) {
        ExtensionPill.Unsupported -> unsupported
        ExtensionPill.Forced -> forced
        ExtensionPill.Managed -> managed
        ExtensionPill.Supported -> supported
        ExtensionPill.Enabled -> enabled
        ExtensionPill.Disabled -> disabled
    }
}