package com.sukisu.ultra.ui.screen.susfs.content.material

import androidx.compose.runtime.Composable
import com.sukisu.ultra.ui.screen.susfs.content.EnabledFeaturesContent
import com.sukisu.ultra.ui.screen.susfs.util.EnabledFeature

@Composable
fun EnabledFeaturesContentMaterial(
    enabledFeatures: List<EnabledFeature>,
    onRefresh: () -> Unit,
) = EnabledFeaturesContent(enabledFeatures, onRefresh)
