package com.sukisu.ultra.ui.screen.upload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.component.material.ExpressiveScaffold
import com.sukisu.ultra.ui.component.material.IconTile
import com.sukisu.ultra.ui.component.material.SegmentedColumn
import com.sukisu.ultra.ui.component.material.SegmentedListItem
import com.sukisu.ultra.ui.component.material.TonalCard
import com.sukisu.ultra.ui.component.material.TopBarBackButton
import com.sukisu.ultra.ui.component.material.expressiveTopAppBarColors
import com.sukisu.ultra.ui.component.statustag.StatusTag
import com.sukisu.ultra.ui.util.CheckStatus

/**
 * Material 风格的上传列表，与 [ModuleUploadListMiuix] 同一份状态、同一组规则。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleUploadListMaterial(
    state: ModuleUploadListState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onContinueCheck: () -> Unit,
    onFilter: (ModuleUploadRules.Filter) -> Unit,
    onQueryChange: (String) -> Unit,
    onToggleRow: (String) -> Unit,
    onPickForRow: (UploadRow) -> Unit,
    onPickZip: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val visible = state.visibleRows
    val checked = state.rows.count { it.status != null }

    ExpressiveScaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.module_upload_title)) },
                navigationIcon = { TopBarBackButton(onClick = onBack) },
                scrollBehavior = scrollBehavior,
                colors = expressiveTopAppBarColors(),
            )
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 16.dp),
            contentPadding = innerPadding,
        ) {
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    label = { Text(stringResource(R.string.module_upload_search_hint)) },
                    singleLine = true,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val labels = listOf(
                        ModuleUploadRules.Filter.ALL to R.string.module_upload_filter_all,
                        ModuleUploadRules.Filter.NOT_RECORDED to R.string.module_upload_filter_not_recorded,
                        ModuleUploadRules.Filter.RECORDED to R.string.module_upload_filter_recorded,
                        ModuleUploadRules.Filter.UNKNOWN to R.string.module_upload_filter_unknown,
                    )
                    labels.forEach { (filter, label) ->
                        FilterChip(
                            selected = state.filter == filter,
                            onClick = { onFilter(filter) },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
            }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onRefresh, enabled = !state.refreshing && !state.loading) {
                            Text(stringResource(R.string.module_upload_refresh))
                        }
                        if (state.remaining > 0) {
                            TextButton(onClick = onContinueCheck, enabled = !state.checking) {
                                Text(stringResource(R.string.module_upload_continue_check))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (state.checking) {
                            Text(
                                text = stringResource(R.string.module_upload_checking),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (checked > 0) {
                            Text(
                                text = stringResource(
                                    R.string.module_upload_check_progress,
                                    checked,
                                    state.remaining,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (state.remaining > 0) {
                        // 一次只查 200 条；剩下的靠「继续查」，这里说清楚为什么没查完。
                        Text(
                            text = stringResource(
                                R.string.module_upload_continue_hint,
                                ModuleUploadRules.CHECK_CAP,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SegmentedColumn(
                    modifier = Modifier.padding(top = 4.dp),
                    content = listOf {
                        SegmentedListItem(
                            onClick = onPickZip,
                            headlineContent = { Text(stringResource(R.string.module_upload_pick_zip)) },
                            supportingContent = { Text(stringResource(R.string.module_upload_pick_zip_summary)) },
                            leadingContent = {
                                IconTile(
                                    Icons.Outlined.CloudUpload,
                                    stringResource(R.string.module_upload_pick_zip),
                                )
                            },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            },
                        )
                    },
                )
            }

            if (visible.isEmpty()) {
                item {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        text = stringResource(
                            if (state.rows.isEmpty()) {
                                R.string.module_upload_empty
                            } else {
                                R.string.module_upload_empty_filtered
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(visible, key = { it.id }) { row ->
                UploadRowCardMaterial(
                    row = row,
                    expanded = state.expandedId == row.id,
                    onToggle = { onToggleRow(row.id) },
                    onPick = { onPickForRow(row) },
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun UploadRowCardMaterial(
    row: UploadRow,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPick: () -> Unit,
) {
    TonalCard(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
        onClick = onToggle,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = row.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusPillMaterial(row.status, row.checking)
            }
            Text(
                text = "${row.version}, ${row.author}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!row.enabled) {
                    StatusTag(
                        label = stringResource(R.string.module_upload_tag_disabled),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
                if (row.remove) {
                    StatusTag(
                        label = stringResource(R.string.module_upload_tag_remove),
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        backgroundColor = MaterialTheme.colorScheme.errorContainer,
                    )
                }
                if (row.metamodule) {
                    StatusTag(
                        label = stringResource(R.string.module_upload_tag_metamodule),
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        backgroundColor = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (expanded) {
                val copy = ModuleUploadRules.statusCopy(row.status)
                Text(
                    modifier = Modifier.padding(top = 2.dp),
                    text = stringResource(copy.title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(copy.summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row.description.isNotBlank()) {
                    Text(
                        text = row.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onPick) {
                    Text(stringResource(R.string.module_upload_pick_original_zip))
                }
                Text(
                    text = stringResource(
                        R.string.module_upload_pick_original_zip_summary,
                        row.id,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusPillMaterial(status: CheckStatus?, checking: Boolean) {
    val (background, content) = when {
        checking -> MaterialTheme.colorScheme.surfaceContainerHighest to
            MaterialTheme.colorScheme.onSurfaceVariant

        status == CheckStatus.RECORDED -> MaterialTheme.colorScheme.secondaryContainer to
            MaterialTheme.colorScheme.onSecondaryContainer

        status == CheckStatus.NEW || status == CheckStatus.VERSION_UPDATE ->
            MaterialTheme.colorScheme.tertiaryContainer to
                MaterialTheme.colorScheme.onTertiaryContainer

        else -> MaterialTheme.colorScheme.surfaceContainerHighest to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when {
        checking -> stringResource(R.string.module_upload_checking)
        else -> stringResource(ModuleUploadRules.statusCopy(status).title)
    }
    StatusTag(
        label = label,
        contentColor = content,
        backgroundColor = background,
    )
}
