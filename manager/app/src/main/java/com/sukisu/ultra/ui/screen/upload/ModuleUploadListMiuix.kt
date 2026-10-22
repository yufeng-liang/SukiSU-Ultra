package com.sukisu.ultra.ui.screen.upload

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.theme.LocalEnableBlur
import com.sukisu.ultra.ui.util.BlurredBar
import com.sukisu.ultra.ui.util.CheckStatus
import com.sukisu.ultra.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Miuix 风格的上传列表。
 *
 * 这一屏是**状态板**：只回答「这一版收录了吗」，不做提交。真正的提交从挑 zip 开始，
 * 挑中的包会直接进表单（[ModuleUploadFormMiuix]）。
 *
 * 状态与 Material 那套共用 [ModuleUploadListState]；这里只负责把它画出来。
 */
@Composable
fun ModuleUploadListMiuix(
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
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    val visible = state.visibleRows
    val checked = state.rows.count { it.status != null }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.module_upload_title),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            val layoutDirection = LocalLayoutDirection.current
                            Icon(
                                modifier = Modifier.graphicsLayer {
                                    if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                },
                                imageVector = MiuixIcons.Back,
                                contentDescription = null,
                            )
                        }
                    },
                    // 刷新放顶栏：列表一滚行内按钮就看不见了，空列表时那一行也只剩它一个。
                    actions = {
                        IconButton(
                            onClick = onRefresh,
                            enabled = !state.refreshing && !state.loading,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.module_upload_refresh),
                                tint = colorScheme.onSurface,
                            )
                        }
                    },
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
            overscrollEffect = null,
        ) {
            item {
                TextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    label = stringResource(R.string.module_upload_search_hint),
                    singleLine = true,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                )
            }

            item {
                FilterRow(
                    selected = state.filter,
                    onFilter = onFilter,
                )
            }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.remaining > 0) {
                            TextButton(
                                text = stringResource(R.string.module_upload_continue_check),
                                onClick = onContinueCheck,
                                enabled = !state.checking,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (state.checking) {
                            Text(
                                text = stringResource(R.string.module_upload_checking),
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        } else if (checked > 0) {
                            Text(
                                text = stringResource(
                                    R.string.module_upload_check_progress,
                                    checked,
                                    state.remaining,
                                ),
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
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
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth(),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.module_upload_pick_zip),
                        summary = stringResource(R.string.module_upload_pick_zip_summary),
                        startAction = {
                            Icon(
                                Icons.Rounded.CloudUpload,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.module_upload_pick_zip),
                                tint = colorScheme.onBackground,
                            )
                        },
                        onClick = onPickZip,
                    )
                }
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
                        fontSize = 14.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            items(visible, key = { it.id }) { row ->
                UploadRowCardMiuix(
                    row = row,
                    expanded = state.expandedId == row.id,
                    onToggle = { onToggleRow(row.id) },
                    onPick = { onPickForRow(row) },
                )
            }

            item {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun FilterRow(
    selected: ModuleUploadRules.Filter,
    onFilter: (ModuleUploadRules.Filter) -> Unit,
) {
    val labels = listOf(
        ModuleUploadRules.Filter.ALL to R.string.module_upload_filter_all,
        ModuleUploadRules.Filter.NOT_RECORDED to R.string.module_upload_filter_not_recorded,
        ModuleUploadRules.Filter.RECORDED to R.string.module_upload_filter_recorded,
        ModuleUploadRules.Filter.UNKNOWN to R.string.module_upload_filter_unknown,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEach { (filter, label) ->
            val active = filter == selected
            Text(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) colorScheme.primary else colorScheme.surfaceContainer)
                    .selectable(
                        selected = active,
                        role = Role.Tab,
                        onClick = { onFilter(filter) },
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                text = stringResource(label),
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                color = if (active) colorScheme.onPrimary else colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun UploadRowCardMiuix(
    row: UploadRow,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
        onClick = onToggle,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = row.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusPill(row.status, row.checking)
            }
            Text(
                text = "${row.version}, ${row.author}",
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!row.enabled) TagMiuix(stringResource(R.string.module_upload_tag_disabled))
                if (row.remove) TagMiuix(stringResource(R.string.module_upload_tag_remove))
                if (row.metamodule) TagMiuix(stringResource(R.string.module_upload_tag_metamodule))
            }

            if (expanded) {
                val copy = ModuleUploadRules.statusCopy(row.status)
                Text(
                    modifier = Modifier.padding(top = 2.dp),
                    text = stringResource(copy.title),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(copy.summary),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
                if (row.description.isNotBlank()) {
                    Text(
                        text = row.description,
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    text = stringResource(R.string.module_upload_pick_original_zip),
                    onClick = onPick,
                )
                Text(
                    text = stringResource(
                        R.string.module_upload_pick_original_zip_summary,
                        row.id,
                    ),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(status: CheckStatus?, checking: Boolean) {
    val (background, content) = when {
        checking -> colorScheme.surfaceContainer to colorScheme.onSurfaceVariantSummary
        status == CheckStatus.RECORDED -> colorScheme.secondaryContainer to colorScheme.onSurface
        status == CheckStatus.NEW || status == CheckStatus.VERSION_UPDATE ->
            colorScheme.tertiaryContainer to colorScheme.onTertiaryContainer

        else -> colorScheme.surfaceContainer to colorScheme.onSurfaceVariantSummary
    }
    val label = when {
        checking -> stringResource(R.string.module_upload_checking)
        else -> stringResource(ModuleUploadRules.statusCopy(status).title)
    }
    Text(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        text = label,
        fontSize = 11.sp,
        color = content,
    )
}

@Composable
private fun TagMiuix(text: String) {
    Text(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colorScheme.surfaceContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        text = text,
        fontSize = 10.sp,
        color = colorScheme.onSurfaceVariantSummary,
    )
}
