package com.sukisu.ultra.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.theme.isInDarkTheme

/**
 * 「模块生态版」徽标：实心绿底小圆角标签，标识此定制构建带模块生态（自定义仓库源 + 酷模投稿）。
 *
 * 颜色固定不取主题色板——两套主题（Miuix / Material）都没有绿色语义槽，这里用同一套绿：
 * 浅色深绿底白字、深色亮绿底墨绿字，两种主题下都有足够对比度。
 * BasicText 而非主题 Text：这个组件要同时活在两套主题里，不依赖任何一方。
 */
@Composable
fun ModuleEcoBadge(modifier: Modifier = Modifier) {
    val dark = isInDarkTheme()
    BasicText(
        text = stringResource(R.string.module_eco_badge),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (dark) Color(0xFF4BB75D) else Color(0xFF139242))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        style = TextStyle(
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (dark) Color(0xFF04240D) else Color.White,
        ),
    )
}
