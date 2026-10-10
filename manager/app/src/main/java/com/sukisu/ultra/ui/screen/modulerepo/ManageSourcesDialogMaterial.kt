package com.sukisu.ultra.ui.screen.modulerepo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.data.repository.RepoSource
import com.sukisu.ultra.ui.component.dialog.rememberConfirmDialog

private val MaxSourcesDialogHeight = 460.dp

@Composable
fun ManageSourcesDialogMaterial(
    show: Boolean,
    onDismissRequest: () -> Unit,
    state: ModuleRepoUiState,
    actions: ModuleRepoActions,
) {
    var urlInput by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<RepoSource?>(null) }
    var editingSource by remember { mutableStateOf<RepoSource?>(null) }
    // 只有真的新增成功才清空输入框：失败时用户填的内容必须留在原处，否则他要重新敲一遍。
    // key 用「最后新增成功的地址」而不是源的数量——数量变化表达不了「这一次成功」，删掉一个已有源
    // 同样会改变数量，会凭空清掉用户正在输入的内容。已处理的地址记在 handledAddedUrl 里，这样对话框
    // 关掉再打开（effect 重新进入组合）不会又清一次；effect 挂在 show 之下，关掉即停止。
    val lastAddedSourceUrl = state.lastAddedSourceUrl
    var handledAddedUrl by remember { mutableStateOf<String?>(null) }
    if (show) {
        LaunchedEffect(lastAddedSourceUrl) {
            if (lastAddedSourceUrl != null && lastAddedSourceUrl != handledAddedUrl) {
                handledAddedUrl = lastAddedSourceUrl
                urlInput = ""
            }
        }
    }
    val deleteConfirmTitle = stringResource(R.string.module_repo_source_delete_confirm)
    val deleteDialog = rememberConfirmDialog(onConfirm = {
        pendingDelete?.let { actions.onRemoveSource(it.id) }
        pendingDelete = null
    })

    if (!show) return

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.module_repo_manage_sources)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = MaxSourcesDialogHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.sources.isEmpty()) {
                    Text(
                        text = stringResource(R.string.module_repo_sources_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.sources.forEach { source ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editingSource = source },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = source.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = source.enabled,
                                onCheckedChange = { actions.onSetSourceEnabled(source.id, it) },
                                modifier = Modifier.padding(start = 8.dp),
                            )
                            IconButton(onClick = {
                                pendingDelete = source
                                deleteDialog.showConfirm(title = deleteConfirmTitle, content = source.name)
                            }) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.delete),
                                )
                            }
                        }
                        // The address takes the full width below the name and may wrap, so a long
                        // index path stays readable instead of being cut off right after the host.
                        Text(
                            text = source.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 8.dp),
                        )
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }

                if (state.candidates.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.module_repo_candidates),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    state.candidates.forEach { candidate ->
                        val adding = candidate.url == state.addingSourceUrl
                        val addable = !candidate.isAdded && !state.isAddingSource
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = addable) {
                                    actions.onAddSource(candidate.url, candidate.name)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = candidate.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.module_repo_candidate_modules,
                                        candidate.moduleCount,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = when {
                                    candidate.isAdded ->
                                        stringResource(R.string.module_repo_candidate_added)

                                    adding -> stringResource(R.string.module_repo_source_adding)
                                    else -> stringResource(R.string.module_repo_candidate_add)
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = if (addable) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text(stringResource(R.string.module_repo_source_url_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                // 清空输入框交给「新增成功」驱动，失败时保留用户填的内容。
                onClick = {
                    actions.onAddSource(urlInput, null)
                },
                enabled = urlInput.isNotBlank() && !state.isAddingSource,
            ) {
                Text(
                    if (state.isAddingSource) {
                        stringResource(R.string.module_repo_source_adding)
                    } else {
                        stringResource(R.string.module_repo_add_source)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )

    editingSource?.let { source ->
        RenameSourceDialogMaterial(
            source = source,
            onDismissRequest = { editingSource = null },
            onConfirm = { name ->
                actions.onRenameSource(source.id, name)
                editingSource = null
            },
        )
    }
}

@Composable
private fun RenameSourceDialogMaterial(
    source: RepoSource,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var nameInput by rememberSaveable { mutableStateOf(source.name) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.module_repo_rename_source)) },
        text = {
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = { Text(stringResource(R.string.module_repo_source_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(nameInput) },
                enabled = nameInput.isNotBlank(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
