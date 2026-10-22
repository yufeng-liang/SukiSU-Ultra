package com.sukisu.ultra.ui.screen.upload

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as MaterialText
import androidx.compose.material3.TextButton as MaterialTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.LocalUiMode
import com.sukisu.ultra.ui.UiMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 人机校验 token 的来源。
 *
 * 单独抽成接口只为一件事：让「拿不到 token 就不提交」这条规则在任何实现下都成立。
 * 生产实现是 [WebViewTurnstileProvider]；单测或以后换验证码服务时，换一个实现即可，
 * 提交路径不需要知道验证码长什么样。
 */
interface TurnstileTokenProvider {
    /** 返回 null 表示**没有完成**校验（用户取消、页面出错、超时）。调用方必须放弃本次提交。 */
    suspend fun getToken(): String?
}

/**
 * 校验页地址。页面由网站侧提供，App 只负责把它装进 WebView 并接收回调。
 *
 * 用 Pages 的规范路径 `/turnstile`：`/turnstile.html` 会被 308 到它。
 * 站点密钥绑的是主机名，两者都能出题；少一跳就少一个 WebView 可能拦下的重定向。
 */
const val TURNSTILE_URL = "https://cool-module.pages.dev/turnstile"

/** 取令牌页的源。顶层文档只许待在这里（子框架放行给 Cloudflare）。 */
private const val TURNSTILE_HOST = "cool-module.pages.dev"

/**
 * 用 WebView 跑人机校验。
 *
 * 页面通过 JS 接口 `TurnstileBridge` 回调：`onToken(String)` / `onError(String)`。
 * 这里刻意**不伪造 token**：拿不到就是 null，界面显示「人机校验没完成，本次没有提交」。
 * 一次只允许一个校验在跑（[gate]）：两个提交并发弹两个 WebView 没有意义，
 * 第二个直接拿不到 token。
 *
 * 页面卡住不回调时靠 [TURNSTILE_TIMEOUT_MS] 收尾，否则提交按钮会永远停在「校验中」。
 */
class WebViewTurnstileProvider : TurnstileTokenProvider {

    private val gate = Mutex()
    private var pending: CompletableDeferred<String?>? = null

    private val _showing = MutableStateFlow(false)

    /** 界面据此决定要不要把 WebView 弹出来。 */
    val showing: StateFlow<Boolean> = _showing.asStateFlow()

    override suspend fun getToken(): String? = gate.withLock {
        val deferred = CompletableDeferred<String?>()
        pending = deferred
        _showing.value = true
        try {
            withTimeoutOrNull(TURNSTILE_TIMEOUT_MS) { deferred.await() }
        } finally {
            _showing.value = false
            pending = null
        }
    }

    /** 页面回调 token；空白 token 一律按失败处理。 */
    fun deliverToken(token: String) {
        pending?.complete(token.takeIf { it.isNotBlank() })
    }

    /** 页面报错（网络、脚本异常）。 */
    @Suppress("UNUSED_PARAMETER")
    fun deliverError(reason: String) {
        pending?.complete(null)
    }

    /** 用户取消 / 关闭对话框。 */
    fun cancel() {
        pending?.complete(null)
    }

    private companion object {
        /** 与整次上传同一个量级：再久用户只会盯着一个不会动的页面。 */
        const val TURNSTILE_TIMEOUT_MS = 120_000L
    }
}

/** 页面里的 `TurnstileBridge.onToken/onError` 落到这里。 */
private class TurnstileBridge(private val provider: WebViewTurnstileProvider) {

    @JavascriptInterface
    fun onToken(token: String) {
        provider.deliverToken(token)
    }

    @JavascriptInterface
    fun onError(reason: String) {
        provider.deliverError(reason)
    }
}

/**
 * 人机校验的模态窗口：一个 WebView + 一个取消按钮。
 *
 * 只在 [WebViewTurnstileProvider.showing] 为真时出现；关闭（返回键 / 取消）会 complete(null)，
 * 提交路径因此不会继续。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TurnstileDialog(provider: WebViewTurnstileProvider) {
    val showing by provider.showing.collectAsStateWithLifecycle()
    if (!showing) return

    val uiMode = LocalUiMode.current
    val containerColor = when (uiMode) {
        UiMode.Miuix -> MiuixTheme.colorScheme.surface
        UiMode.Material -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when (uiMode) {
        UiMode.Miuix -> MiuixTheme.colorScheme.onSurface
        UiMode.Material -> MaterialTheme.colorScheme.onSurface
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = { provider.cancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(containerColor)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                when (uiMode) {
                    UiMode.Miuix -> MiuixText(
                        text = stringResource(R.string.module_upload_turnstile_title),
                        color = contentColor,
                    )

                    UiMode.Material -> MaterialText(
                        text = stringResource(R.string.module_upload_turnstile_title),
                        color = contentColor,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                when (uiMode) {
                    UiMode.Miuix -> MiuixTextButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = { provider.cancel() },
                    )

                    UiMode.Material -> MaterialTextButton(onClick = { provider.cancel() }) {
                        MaterialText(stringResource(android.R.string.cancel))
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when (uiMode) {
                            UiMode.Miuix -> MiuixTheme.colorScheme.surfaceContainer
                            UiMode.Material -> MaterialTheme.colorScheme.surfaceContainerLowest
                        }
                    ),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // 只需要渲染这一页：文件 / content 访问与混合内容都不开。
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            addJavascriptInterface(TurnstileBridge(provider), "TurnstileBridge")
                            webViewClient = object : WebViewClient() {
                                /**
                                 * 只让顶层文档待在取令牌页所在的源。
                                 *
                                 * JS 接口是挂在 WebView 上的：一旦顶层被导航到别处，那个页面
                                 * 也能调 `TurnstileBridge`。令牌本身服务端会验，但一个随便的
                                 * 页面不该有机会冒充这道对话框。
                                 * 子框架（Turnstile 自己的 iframe）不拦：它需要和 Cloudflare 通信。
                                 */
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    if (request == null || !request.isForMainFrame) return false
                                    val url = request.url
                                    val trusted = (url.scheme == "https" && url.host == TURNSTILE_HOST) ||
                                        url.scheme == "about"
                                    if (trusted) return false
                                    provider.deliverError("校验页试图离开受信任站点")
                                    return true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    loading = false
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: android.webkit.WebResourceError?,
                                ) {
                                    // 只有主文档失败才算「校验页打不开」。favicon / 子资源失败
                                    // 不该把一次可能成功的挑战提前收掉。
                                    if (request?.isForMainFrame == true) {
                                        provider.deliverError(error?.description?.toString().orEmpty())
                                    }
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    errorResponse: android.webkit.WebResourceResponse?,
                                ) {
                                    if (request?.isForMainFrame == true &&
                                        (errorResponse?.statusCode ?: 0) >= 400
                                    ) {
                                        provider.deliverError("HTTP ${errorResponse?.statusCode}")
                                    }
                                }

                                override fun onRenderProcessGone(
                                    view: WebView?,
                                    detail: RenderProcessGoneDetail?,
                                ): Boolean {
                                    // 渲染进程被杀（低内存等）：收尾并让系统回收这个 WebView，
                                    // 否则要么卡到 120 秒，要么整个 App 被带走。
                                    provider.deliverError("校验组件崩溃")
                                    view?.destroy()
                                    return true
                                }
                            }
                            loadUrl(TURNSTILE_URL)
                            webView = this
                        }
                    },
                )
                // 页面在加载完成之前先给一个「正在进行」的提示，
                // 免得一块白底 WebView 看起来像卡死。
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(28.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { view ->
                // 先摘掉 JS 接口再销毁：销毁后的 renderer 仍可能被引用一会儿，
                // 那个接口不该跟着活到那时。
                view.removeJavascriptInterface("TurnstileBridge")
                view.stopLoading()
                view.destroy()
            }
            webView = null
            // 窗口被系统收走（旋转、Activity 重建）也要让提交路径立刻收尾。
            provider.cancel()
        }
    }
}
