package com.sukisu.ultra.ui.screen.upload

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember

/** 转一圈的时长。900ms 是「看得出在动、又不至于闪」的那一档。 */
private const val SPIN_PERIOD_MS = 900

/**
 * 顶栏刷新图标的旋转角（度）。
 *
 * 两套主题共用这一个：旋转与主题无关，落点也都是 `graphicsLayer.rotationZ`，
 * 没有必要写两遍。
 *
 * 返回 `State` 而不是 `Float` 是有意的：调用方在 `graphicsLayer { }` 里读 `.value`，
 * 每帧只重做那一层图层，**不触发重组**。若这里直接 `by` 出 Float，60fps 的动画会把
 * 整个 `actions` lambda（连同旁边两颗按钮）一起重组，为一条进度指示白烧一帧的 CPU。
 *
 * [spinning] 为 false 时**不创建**动画：`rememberInfiniteTransition` 一旦挂上就会
 * 一直跑帧回调，而这一屏绝大部分时间是不忙的。
 */
@Composable
internal fun rememberSpinAngle(spinning: Boolean): State<Float> {
    // 不转时给一个常量 State：同一个 key、永不变化，图层里读它不会有任何回调。
    if (!spinning) return remember { mutableFloatStateOf(0f) }
    val transition = rememberInfiniteTransition(label = "upload-refresh")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SPIN_PERIOD_MS, easing = LinearEasing),
        ),
        label = "upload-refresh-angle",
    )
}
