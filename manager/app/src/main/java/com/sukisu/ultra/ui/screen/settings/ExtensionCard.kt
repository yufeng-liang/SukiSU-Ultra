package com.sukisu.ultra.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.util.ExtensionId
import com.sukisu.ultra.ui.util.ExtensionState

/**
 * One row in the extension list. Shows status (enabled/disabled/not supported).
 * The whole card is clickable if the extension is supported; unsupported
 * extensions display a non-interactive status and no navigation arrow.
 */
@Composable
fun ExtensionCard(
    title: String,
    summary: String,
    state: ExtensionState?,
    onOpenDetails: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val supported = state?.supported == true

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (supported && onOpenDetails != null) {
                    Modifier.clickable(onClick = onOpenDetails)
                } else {
                    Modifier
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Memory,
                contentDescription = null,
                modifier = Modifier.padding(end = 12.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StatusLine(state = state)
            }
            if (supported && onOpenDetails != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = stringResource(
                        R.string.extension_open_details
                    ),
                )
            }
        }
    }
}

@Composable
private fun StatusLine(state: ExtensionState?) {
    // Compute the (label, color) pair once. Centralising the decision
    // here keeps the precedence readable: missing-state > unsupported >
    // forced > managed > enabled/disabled > sticky-bit support hint.
    //
    // Extensions without a runtime sticky bit (KPM, CPU spoof, umount
    // paths) report value=0 even when their subsystem is healthy. The
    // helper `statusFor` collapses that to a neutral "Supported" label
    // so the overview doesn't show a misleading "Disabled" pill for
    // an extension that is just unsupported in the toggle sense.
    val resolved = state?.let { statusFor(it) }
    if (resolved != null) {
        val (label, color) = resolved
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/**
 * Precedence for the status pill on the extension card.
 *
 * `unsupported` wins over `forced` because the UI must show a
 * "Not supported" hint when the kernel never registered a handler,
 * even if the id appears in `force_extension=`. (In practice that
 * combination is rejected by `ksu_extension_apply_forced` with a
 * warning, so the pill matches what the kernel actually did.)
 */
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
    @androidx.annotation.StringRes val labelRes: Int,
    val isOnTertiary: Boolean = false,
    val isOnPrimary: Boolean = false,
    val isOnError: Boolean = false,
    val isOnSurfaceVariant: Boolean = false,
)

/**
 * Pure mapping from an `ExtensionState` to a `ExtensionPill`. Has no
 * Composable dependencies so it can be unit-tested directly.
 *
 * Sticky-bit semantics: the "enabled/disabled" pair only applies to
 * extensions that actually expose a runtime toggle. For the others
 * (KPM, CPU spoof, umount paths) we fall through to a neutral
 * "Supported" hint so the overview never advertises a toggle that
 * doesn't exist.
 */
internal fun pillFor(state: ExtensionState): ExtensionPillSpec = when {
    !state.supported -> ExtensionPillSpec(
        pill = ExtensionPill.Unsupported,
        labelRes = R.string.extension_status_unsupported,
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
        labelRes = R.string.extension_status_enabled,
        isOnPrimary = true,
    )
    else -> ExtensionPillSpec(
        pill = ExtensionPill.Disabled,
        labelRes = R.string.extension_status_disabled,
        isOnSurfaceVariant = true,
    )
}

@Composable
private fun statusFor(state: ExtensionState): Pair<Int, androidx.compose.ui.graphics.Color> {
    val spec = pillFor(state)
    val color = when {
        spec.isOnError -> MaterialTheme.colorScheme.error
        spec.isOnTertiary -> MaterialTheme.colorScheme.tertiary
        spec.isOnPrimary -> MaterialTheme.colorScheme.primary
        spec.isOnSurfaceVariant -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    return spec.labelRes to color
}

@Composable
fun ExtensionCard(
    id: ExtensionId,
    state: ExtensionState?,
    onOpenDetails: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    ExtensionCard(
        title = stringResource(id.displayRes),
        summary = stringResource(
            when (id) {
                ExtensionId.Kpm -> R.string.extension_kpm_summary
                ExtensionId.CpuSpoof -> R.string.extension_cpu_spoof_summary
                ExtensionId.UmountPaths -> R.string.extension_umount_paths_summary
                ExtensionId.DynamicManager -> R.string.extension_dynamic_manager_summary
            }
        ),
        state = state,
        onOpenDetails = onOpenDetails,
        modifier = modifier,
    )
}
