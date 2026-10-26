package com.sukisu.ultra.ui.screen.susfs.content

import androidx.compose.runtime.Composable
import com.sukisu.ultra.ui.LocalUiMode
import com.sukisu.ultra.ui.UiMode
import com.sukisu.ultra.ui.screen.susfs.content.material.PriorityUidFilterContentMaterial
import com.sukisu.ultra.ui.screen.susfs.content.miuix.PriorityUidFilterContentMiuix

@Composable
fun PriorityUidFilterContent(
    packages: Set<String>,
    isLoading: Boolean,
    onSelectApps: () -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
) {
    when (LocalUiMode.current) {
        UiMode.Material -> PriorityUidFilterContentMaterial(packages, isLoading, onSelectApps, onApply, onClear)
        UiMode.Miuix -> PriorityUidFilterContentMiuix(packages, isLoading, onSelectApps, onApply, onClear)
    }
}
