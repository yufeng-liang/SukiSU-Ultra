package com.sukisu.ultra.ui.screen.about

import androidx.compose.runtime.Immutable

@Immutable
data class AboutUiState(
    val title: String,
    val appName: String,
    val versionName: String,
    val links: List<LinkInfo>,
    val checkUpdate: Boolean = true,
    val checkModuleUpdate: Boolean = true,
)

@Immutable
data class AboutScreenActions(
    val onBack: () -> Unit,
    val onOpenLink: (String) -> Unit,
    val onSetCheckUpdate: (Boolean) -> Unit = {},
    val onSetCheckModuleUpdate: (Boolean) -> Unit = {},
)
