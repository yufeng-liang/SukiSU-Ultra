package com.sukisu.ultra.ui.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 接住落在这一层上的点击，不让它穿到下面那一层去。
 *
 * 顶栏是半透明的、列表从它下面滚过去（Miuix 那套模糊栏就是这么用的），而顶栏自己除了按钮
 * 之外没有可点的地方。不加这一层，点在顶栏的空白处（比如两个分页标签中间）会穿到下面那一行：
 * 实测会把「选择要备份的模块」折叠掉，而屏幕上看着只是点了一下标签栏。
 *
 * 命中测试到这一层就停住——子节点（返回键、分页标签）照常收到事件，下面那一层再也收不到。
 * 只"接住"不"消费"：在 Final 阶段取一次事件、什么都不做，所以点击、滚动、侧滑返回的行为
 * 和加它之前完全一样。
 */
fun Modifier.blockTaps(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Final)
        }
    }
}
