package com.sukisu.ultra.ui.screen.dynamicmanagers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.component.AppIconImage
import com.sukisu.ultra.ui.component.material.ExpressiveScaffold
import com.sukisu.ultra.ui.component.material.ExpressiveSwitch
import com.sukisu.ultra.ui.component.material.TonalCard
import com.sukisu.ultra.ui.component.material.expressiveTopAppBarColors
import com.sukisu.ultra.ui.component.statustag.StatusTag
import com.sukisu.ultra.ui.util.DynamicManagerState

@Composable
internal fun DynamicManagersMaterial(
    state: DynamicManagerState?,
    currentManager: InstalledApp?,
    installedApps: List<InstalledApp>,
    apps: List<InstalledApp>,
    busy: Boolean,
    error: String?,
    actions: DynamicManagersActions,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val listState: LazyListState = rememberLazyListState()

    ExpressiveScaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dynamic_manager_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    IconButton(onClick = actions.onRefresh) {
                        Icon(Icons.Outlined.Refresh, null)
                    }
                },
                colors = expressiveTopAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            val addManager = stringResource(R.string.dynamic_manager_add)
            SmallExtendedFloatingActionButton(
                onClick = { if (!busy) actions.onLoadApps() },
                modifier = Modifier.alpha(if (busy) 0.38f else 1f),
                icon = { Icon(Icons.Outlined.Add, addManager) },
                text = { Text(text = addManager) },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 16.dp + 56.dp + 16.dp,
            ),
        ) {
            item(key = "status", contentType = "status") {
                StatusCard(
                    state = state,
                    busy = busy,
                    error = error,
                    onSetEnabled = actions.onSetEnabled,
                )
            }

            currentManager?.let { app ->
                item(key = "current_header", contentType = "header") {
                    SectionTitle(stringResource(R.string.dynamic_manager_current))
                }
                item(key = "current", contentType = "manager") {
                    val currentDetail = state?.entries
                        ?.firstOrNull { it.packageName == app.packageName }
                        ?.let { "${it.certSize} bytes · ${it.hash}" }
                    ManagerCard(
                        app = app,
                        packageName = app.packageName,
                        uid = app.uid,
                        detail = currentDetail,
                        current = true,
                        onRemove = null,
                    )
                }
            }

            val entries = state?.entries.orEmpty()
            if (entries.isNotEmpty()) {
                item(key = "authorized_header", contentType = "header") {
                    SectionTitle(stringResource(R.string.dynamic_manager_authorized))
                }
                items(
                    entries.filter { it.packageName != currentManager?.packageName },
                    key = { it.packageName },
                    contentType = { "manager" },
                ) { entry ->
                    val app = installedApps.firstOrNull { it.packageName == entry.packageName }
                    ManagerCard(
                        app = app,
                        packageName = entry.packageName,
                        uid = entry.uid,
                        detail = "${entry.certSize} bytes · ${entry.hash}",
                        current = false,
                    ) {
                        actions.onRemove(entry.packageName)
                    }
                }
            }

            if (apps.isNotEmpty()) {
                item(key = "apps_header", contentType = "header") {
                    SectionTitle(stringResource(R.string.dynamic_manager_select_app))
                }
                items(
                    apps.filterNot { app -> entries.any { it.packageName == app.packageName } },
                    key = { "app_${it.packageName}" },
                    contentType = { "app" },
                ) { app ->
                    AppCandidateCard(app = app, onAdd = { actions.onAdd(app) })
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    state: DynamicManagerState?,
    busy: Boolean,
    error: String?,
    onSetEnabled: (Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    TonalCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { },
    ) {
        Column(
            modifier = Modifier.padding(18.dp, 16.dp, 16.dp, 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.dynamic_manager_enabled),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.dynamic_manager_summary),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                ExpressiveSwitch(
                    enabled = state != null,
                    checked = state?.enabled ?: false,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        onSetEnabled(it)
                    },
                )
            }

            AnimatedVisibility(
                visible = busy,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )
            }

            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ManagerCard(
    app: InstalledApp?,
    packageName: String,
    uid: Int,
    detail: String?,
    current: Boolean,
    onRemove: (() -> Unit)?,
) {
    var expanded by rememberSaveable(packageName) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    TonalCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier
                .alpha(if (current) 0.85f else 1f)
                .padding(18.dp, 16.dp, 16.dp, 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (app != null) {
                    AppIconImage(Modifier.size(48.dp), app.packageInfo, app.label)
                } else {
                    Spacer(Modifier.size(48.dp))
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = app?.label ?: packageName,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "UID $uid",
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                if (current) {
                    StatusTag(
                        label = stringResource(R.string.dynamic_manager_default),
                        contentColor = colors.onTertiaryContainer,
                        backgroundColor = colors.tertiaryContainer,
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    detail?.let {
                        Text(
                            text = it,
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Text(
                        text = packageName,
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            if (onRemove != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    FilledTonalButton(
                        onClick = onRemove,
                        modifier = Modifier.defaultMinSize(52.dp, 32.dp),
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                    ) {
                        Icon(
                            modifier = Modifier.size(20.dp),
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = null,
                        )
                        Text(
                            modifier = Modifier.padding(start = 7.dp),
                            fontFamily = MaterialTheme.typography.labelMedium.fontFamily,
                            fontSize = MaterialTheme.typography.labelMedium.fontSize,
                            text = stringResource(R.string.dynamic_manager_remove),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppCandidateCard(
    app: InstalledApp,
    onAdd: () -> Unit,
) {
    TonalCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { },
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconImage(Modifier.size(48.dp), app.packageInfo, app.label)

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = app.label,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.packageName,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            FilledTonalButton(
                onClick = onAdd,
                modifier = Modifier.defaultMinSize(52.dp, 32.dp),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.dynamic_manager_authorize, app.label),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}
