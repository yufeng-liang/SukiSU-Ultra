package com.sukisu.ultra.ui.screen.upload

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.util.CheckStatus
import kotlin.math.roundToInt

/** 与 Miuix 版同一个尺度，两套主题下墙的宽度和观感一致。 */
private val TILE_WIDTH = 72.dp

/** 墙的宽度：正好三枚 + 两段 10dp 间距 + 两侧 4dp 内边距再留点余量。 */
private val WALL_WIDTH = TILE_WIDTH * 3 + 40.dp

/**
 * Material 3 风格的 zip 图标墙，行为与 [UploadZipWallMiuix] 完全一致。
 *
 * 选中那张放大、描主色边、满不透明，两侧缩到 0.9 并压暗；选中变化时自动把这枚
 * 尽量滚到中间（列表两端没那么多余量时只能贴边）。只在 LazyRow 里横向滑，不嵌套
 * Pager —— 外面是竖滑的 LazyColumn，两层手势不会打架。
 *
 * 一枚都没有时照样画墙，只画末尾那个「添加」空位 —— 它是全页唯一的加包入口。
 */
@Composable
fun UploadZipWallMaterial(
    items: List<UploadZip>,
    selectedPath: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onPickMore: () -> Unit,
) {
    val listState = rememberLazyListState()
    val centerOffsetPx = with(LocalDensity.current) { ((WALL_WIDTH - TILE_WIDTH) / 2).toPx() }
    LaunchedEffect(selectedPath) {
        val index = items.indexOfFirst { it.path == selectedPath }
        if (index < 0) return@LaunchedEffect
        listState.animateScrollToItem(index, scrollOffset = -centerOffsetPx.roundToInt())
    }
    LazyRow(
        state = listState,
        modifier = Modifier.width(WALL_WIDTH),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items.size, key = { items[it].path }) { index ->
            val item = items[index]
            ZipTileMaterial(
                item = item,
                selected = item.path == selectedPath,
                enabled = enabled,
                onClick = { onSelect(item.path) },
            )
        }
        item {
            AddTileMaterial(enabled = enabled, onClick = onPickMore)
        }
    }
}

@Composable
private fun ZipTileMaterial(
    item: UploadZip,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(if (selected) 1f else 0.9f, label = "zipTileScale")
    val fade by animateFloatAsState(if (selected) 1f else 0.62f, label = "zipTileAlpha")
    Column(
        modifier = Modifier
            .width(TILE_WIDTH)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = fade
            }
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (selected) {
                    Modifier.border(
                        1.5.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(14.dp),
                    )
                } else {
                    Modifier
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderZip,
                    contentDescription = null,
                    tint = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(26.dp),
                )
            }
            ZipBadgeMaterial(
                item = item,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        Text(
            text = item.name.ifBlank { item.id },
            // 用主题的 labelSmall（而不是写死 11sp）：墙里的包名与表单里其他小字同一档字号，
            // 换主题或改字体缩放时跟着一起动。
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ZipBadgeMaterial(item: UploadZip, modifier: Modifier = Modifier) {
    val dot = when {
        item.checking -> MaterialTheme.colorScheme.onSurfaceVariant
        item.status == CheckStatus.RECORDED -> MaterialTheme.colorScheme.error
        item.status == CheckStatus.NEW -> MaterialTheme.colorScheme.primary
        item.status == CheckStatus.VERSION_UPDATE -> MaterialTheme.colorScheme.primary
        item.status == CheckStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> return
    }
    Box(
        modifier = modifier
            .padding(2.dp)
            .size(9.dp)
            .clip(CircleShape)
            .background(dot),
    )
}

@Composable
private fun AddTileMaterial(enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(TILE_WIDTH)
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = stringResource(R.string.module_upload_wall_add),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
