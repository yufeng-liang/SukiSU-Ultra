package com.sukisu.ultra.ui.screen.upload

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.util.CheckStatus
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** 一枚 zip 在墙上占的宽度。72dp 是「一眼认得出图标、又能在窄屏上并排看到三枚」的折中。 */
private val TILE_WIDTH = 72.dp

/**
 * Miuix 风格的 zip 图标墙：这一批里每一枚一张图标卡，横向可滑、点哪张编辑哪张。
 *
 * 舞台感由三件事给出来 —— 选中的那张不缩放、不透明、描一圈主色边；两侧的缩到 0.9、
 * 压到六成不透明；选中变化时自动把这枚滚到中间。这样「中间放大、两边缩小」不需要
 * 嵌套一个 Pager，也就没有横滑手势和外面 LazyColumn 竖滑打架的问题。
 *
 * 墙上不给「移除」：那是对当前枚的动作，放在下面详情卡上，避免小图标上再挤一个按钮。
 */
@Composable
fun UploadZipWallMiuix(
    items: List<UploadZip>,
    selectedPath: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onPickMore: () -> Unit,
) {
    if (items.isEmpty()) return
    val listState = rememberLazyListState()
    // 选中变化（点别的、或删掉当前这枚）时把那一枚滚到中间，不然「放大的那张」可能在屏幕外。
    LaunchedEffect(selectedPath, items.size) {
        val index = items.indexOfFirst { it.path == selectedPath }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    LazyRow(
        state = listState,
        modifier = Modifier.width(TILE_WIDTH * 3 + 20.dp + 20.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items.size, key = { items[it].path }) { index ->
            val item = items[index]
            ZipTileMiuix(
                item = item,
                selected = item.path == selectedPath,
                enabled = enabled,
                onClick = { onSelect(item.path) },
            )
        }
        item {
            AddTileMiuix(enabled = enabled, onClick = onPickMore)
        }
    }
}

@Composable
private fun ZipTileMiuix(
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
                    Modifier.border(1.5.dp, colorScheme.primary, RoundedCornerShape(14.dp))
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
                    .background(colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    imageVector = Icons.Rounded.FolderZip,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(
                        if (selected) colorScheme.primary else colorScheme.onSurfaceVariantSummary,
                    ),
                    modifier = Modifier.size(26.dp),
                )
            }
            ZipBadgeMiuix(
                item = item,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        Text(
            text = item.name.ifBlank { item.id },
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 图标右上角那一个小点：只说「这一枚能不能上传」这一件事。
 *
 * 查重中 / 没查到一律灰，已收录红（不能传），没收录与版本更新主色（能传）。
 */
@Composable
private fun ZipBadgeMiuix(item: UploadZip, modifier: Modifier = Modifier) {
    val dot = when {
        item.checking -> colorScheme.onSurfaceVariantSummary
        item.status == CheckStatus.RECORDED -> colorScheme.error
        item.status == CheckStatus.NEW -> colorScheme.primary
        item.status == CheckStatus.VERSION_UPDATE -> colorScheme.primary
        item.status == CheckStatus.UNKNOWN -> colorScheme.onSurfaceVariantSummary
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
private fun AddTileMiuix(enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(TILE_WIDTH)
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = 1.dp,
                color = colorScheme.onSurfaceVariantSummary.copy(alpha = 0.35f),
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
            Image(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                colorFilter = ColorFilter.tint(colorScheme.onSurfaceVariantSummary),
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = stringResource(R.string.module_upload_wall_add),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
