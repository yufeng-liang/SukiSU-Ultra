package com.sukisu.ultra.ui.screen.upload

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.ksuApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 「已验证」那一格的两块装饰：酷安头像，与勾选描边动画。
 *
 * 两块都**只是装饰**：头像取不到就退成一个空圆，动画在系统关掉动画时不播 ——
 * 任何一处失败都不影响「能不能提交」的判断（那一条只看 [com.sukisu.ultra.ui.component.CoolapkUidState.Verified]）。
 */

/** 头像最大字节数。酷安头像正常只有几十 KB，超过这个数说明拿到的不是头像。 */
private const val MAX_AVATAR_BYTES = 1L * 1024 * 1024

/** 解码目标边长。头像最终画在 28~40dp，96px 足够清晰又不会占内存。 */
private const val AVATAR_PX = 96

/** 同一张地址不重复下载；进程内缓存就够了，头像不是关键内容。 */
private val avatarCache = LruCache<String, ImageBitmap>(16)

/**
 * 头像下载专用的 client：整通调用 15 秒封顶。
 *
 * 全局 `okhttpClient` 只配了连接/读的默认值，一个慢速滴流（或干脆不响应）的图床地址
 * 能把一个 IO 线程按住很久；头像不是关键内容，宁可放弃也不要占着线程。
 */
private val avatarClient: OkHttpClient by lazy {
    ksuApp.okhttpClient.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
}

private fun loadAvatar(url: String): ImageBitmap? = runCatching {
    val bytes = avatarClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
        if (!resp.isSuccessful) return null
        val source = resp.body.source()
        // 先问长度：真去读一个几百 MB 的东西只为了让头像显示，不值得。
        if (source.request(MAX_AVATAR_BYTES + 1)) return null
        source.readByteArray()
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= AVATAR_PX) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?.asImageBitmap()
}.getOrNull()

/**
 * 酷安头像。地址为空、下载失败、解码失败都回 null（调用方画占位圆）。
 *
 * 地址只认 http(s)：服务端已经过滤过一遍，这里再挡一次是因为这个字符串来自网络，
 * 而 [okhttp3.Request.Builder.url] 对别的 scheme 会直接抛。
 */
@Composable
fun rememberUploadAvatar(url: String?): ImageBitmap? {
    val safe = url?.trim()?.takeIf {
        it.startsWith("http://", true) || it.startsWith("https://", true)
    }
    if (safe.isNullOrEmpty()) return null
    val cached = avatarCache.get(safe)
    if (cached != null) return cached
    val bitmap by produceState<ImageBitmap?>(null, safe) {
        value = withContext(Dispatchers.IO) { loadAvatar(safe) }
        if (value != null) avatarCache.put(safe, value!!)
    }
    return bitmap
}

/**
 * 勾选描边动画：一圈从 0 扫到整圈，随后一个勾从短到长画出来。
 *
 * 两个 `animateFloatAsState` 用同一段 420ms 的时长、勾比圈晚 140ms 起步 ——
 * 观感上就是「圈画完、勾跟上」。系统把动画关掉时 Compose 的时长会变成 0，
 * 于是直接落到终态（勾和圈都在），不会出现「什么都没画」。
 */
@Composable
fun VerifiedMark(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    // 为什么不能直接 targetValue = 1f：animateFloatAsState 没有 initialValue 参数
    // （那是 animateValueAsState 的），它的初值恒等于首帧的 targetValue。所以写死 1f
    // 时首帧就是终态，420ms/280ms 全白写。这里用「第一帧先给 0、下一帧才抬到 1」：
    // 组合时 started = false ⇒ 目标 0 ⇒ Animatable 以 0 起步、什么都不画；LaunchedEffect
    // 在首帧之后把 started 置 true ⇒ 目标变 1 ⇒ 从当前值 0 逐帧推到 1。
    //
    // 重播不用另加机制：VerifiedMark 只在 CoolapkUidState.Verified 那个 when 分支里被组合，
    // 从别的状态切进 Verified 是另一处调用点 ⇒ 全新组合 ⇒ remember 重来 ⇒ 再从 0 播一遍。
    //
    // 系统关掉动画（ANIMATOR_DURATION_SCALE = 0）时，Compose 的 MotionDurationScale 会把
    // 每帧 playTime 直接顶到动画总时长（已核对 doAnimationFrameWithScale 的字节码），
    // 于是第一次推进就 isFinished ⇒ 直接落终态，不需要额外判断。
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val ring by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = LinearEasing),
        label = "verifiedRing",
    )
    val tick by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 280, delayMillis = 140, easing = LinearEasing),
        label = "verifiedTick",
    )
    Canvas(modifier = modifier.size(size)) {
        val stroke = this.size.minDimension * 0.11f
        val inset = stroke / 2f
        drawArc(
            color = tint,
            startAngle = -90f,
            sweepAngle = 360f * ring.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        // 勾的两条边按进度插值：先画短的一段，再补上第二段。
        val progress = tick.coerceIn(0f, 1f)
        val p1 = Offset(this.size.width * 0.28f, this.size.height * 0.53f)
        val p2 = Offset(this.size.width * 0.44f, this.size.height * 0.70f)
        val p3 = Offset(this.size.width * 0.74f, this.size.height * 0.33f)
        if (progress <= 0f) return@Canvas
        val firstHalf = (progress / 0.45f).coerceIn(0f, 1f)
        val secondHalf = ((progress - 0.45f) / 0.55f).coerceIn(0f, 1f)
        drawLine(
            color = tint,
            start = p1,
            end = Offset(p1.x + (p2.x - p1.x) * firstHalf, p1.y + (p2.y - p1.y) * firstHalf),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        if (secondHalf > 0f) {
            drawLine(
                color = tint,
                start = p2,
                end = Offset(p2.x + (p3.x - p2.x) * secondHalf, p2.y + (p3.y - p2.y) * secondHalf),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * 头像 + 勾的合体：有头像就画头像、右下角压一个勾；没有就画一个带勾的空圆。
 *
 * [avatarUrl] 为空时也能用 —— 「已验证但酷安没给头像」是正常情况。
 */
@Composable
fun VerifiedAvatar(
    avatarUrl: String?,
    tint: Color,
    placeholder: Color,
    modifier: Modifier = Modifier,
    avatarSize: Dp = 32.dp,
    markSize: Dp = 14.dp,
) {
    val bitmap = rememberUploadAvatar(avatarUrl)
    Box(modifier = modifier.size(avatarSize)) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier
                    .size(avatarSize)
                    .clip(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(avatarSize)
                    .clip(CircleShape)
                    .background(placeholder),
            )
        }
        VerifiedMark(
            tint = tint,
            modifier = Modifier.align(Alignment.BottomEnd),
            size = markSize,
        )
    }
}
