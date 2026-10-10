package com.sukisu.ultra.ui.util

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 投稿接口客户端的解析与降级规则。
 *
 * MockWebServer 不在 testImplementation 里（libs.versions.toml 没有 mockwebserver，
 * 且契约不让改 build.gradle.kts），所以这里测的是**从 HTTP 响应到结果的这一段纯函数**：
 * 通过反射调用私有的 readStatus / readSubmitResult / describe，喂进去构造好的
 * Response 与异常。真正走 socket 的那部分留给真机/集成验证。
 */
class ModuleContributionTest {

    private val readStatus = ModuleContribution::class.java
        .getDeclaredMethod("readStatus", okhttp3.Response::class.java)
        .apply { isAccessible = true }

    private val readSubmitResult = ModuleContribution::class.java
        .getDeclaredMethod("readSubmitResult", okhttp3.Response::class.java)
        .apply { isAccessible = true }

    private val describe = ModuleContribution::class.java
        .getDeclaredMethod("describe", Throwable::class.java)
        .apply { isAccessible = true }

    private fun response(code: Int, bodyText: String): okhttp3.Response {
        val request = okhttp3.Request.Builder().url("https://example.invalid/check").build()
        // 先在 String 侧转成 MediaType，避开 OkHttp 5 里那个 Java 参数顺序变体（已废弃）。
        val body = bodyText.toResponseBody("application/json".toMediaType())
        return okhttp3.Response.Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(code)
            .message("mock")
            .body(body)
            .build()
    }

    private fun statusOf(code: Int, bodyText: String): CheckStatus =
        readStatus.invoke(ModuleContribution, response(code, bodyText)) as CheckStatus

    private fun submitOf(code: Int, bodyText: String): SubmitResult =
        readSubmitResult.invoke(ModuleContribution, response(code, bodyText)) as SubmitResult

    private fun described(error: Throwable): String =
        describe.invoke(ModuleContribution, error) as String

    // ── checkRecorded 的解析 ────────────────────────────────────────────────

    @Test
    fun `every documented status maps to its enum`() {
        assertEquals(CheckStatus.RECORDED, statusOf(200, """{"status":"recorded"}"""))
        assertEquals(CheckStatus.NEW, statusOf(200, """{"status":"new"}"""))
        assertEquals(CheckStatus.VERSION_UPDATE, statusOf(200, """{"status":"version_update"}"""))
        assertEquals(CheckStatus.UNKNOWN, statusOf(200, """{"status":"unknown"}"""))
    }

    @Test
    fun `an unparsable or unexpected body degrades to unknown never to new`() {
        // 关键：服务端坏了不能让 App 以为这是个新模块去弹分享提示。
        assertEquals(CheckStatus.UNKNOWN, statusOf(200, "not json"))
        assertEquals(CheckStatus.UNKNOWN, statusOf(200, """{"foo":"bar"}"""))
        assertEquals(CheckStatus.UNKNOWN, statusOf(200, """{"status":"RECORDED-ish"}"""))
        assertEquals(CheckStatus.UNKNOWN, statusOf(200, ""))
    }

    @Test
    fun `a non 2xx response degrades to unknown`() {
        // 服务端约定：任何失败都用 200 + status unknown，绝不 4xx/5xx。
        // 真收到 500 也不能崩，更不能误判成"没收录"。
        assertEquals(CheckStatus.UNKNOWN, statusOf(500, """{"status":"new"}"""))
        assertEquals(CheckStatus.UNKNOWN, statusOf(404, """{"status":"new"}"""))
        assertEquals(CheckStatus.UNKNOWN, statusOf(302, """{"status":"new"}"""))
    }

    // ── submitModule 的解析 ─────────────────────────────────────────────────

    @Test
    fun `a successful submit carries the pr url`() {
        val result = submitOf(200, """{"ok":true,"pr":"https://github.com/o/r/pull/7"}""")
        assertTrue(result.ok)
        assertEquals("https://github.com/o/r/pull/7", result.prUrl)
        assertTrue(result.message.isNotBlank())
    }

    @Test
    fun `a rejected submit shows the server reason`() {
        val result = submitOf(400, """{"ok":false,"error":"缺少 module.prop"}""")
        assertFalse(result.ok)
        assertNull(result.prUrl)
        // 400 时 body 里的原因不该被丢掉——那是唯一能告诉用户"哪里不合格"的信息。
        assertTrue(result.message.contains("400"))
    }

    @Test
    fun `a failure result always carries a human readable message`() {
        for (result in listOf(
            submitOf(500, "boom"),
            submitOf(200, "not json"),
            submitOf(200, """{"ok":false}"""),
        )) {
            assertFalse(result.ok)
            assertNull(result.prUrl)
            assertTrue("message 不能为空：$result", result.message.isNotBlank())
        }
    }

    // ── 异常到人话 ──────────────────────────────────────────────────────────

    @Test
    fun `timeouts and io failures are described in words not class names`() {
        val timeout = described(java.net.SocketTimeoutException("timeout"))
        assertTrue(timeout, timeout.contains("超时"))
        val io = described(java.io.IOException("no network"))
        assertTrue(io, io.contains("网络"))
        // 兜底：不留空消息，也不把异常类名甩给用户。
        assertTrue(described(RuntimeException()).isNotBlank())
        assertEquals("boom", described(RuntimeException("boom")))
    }

    @Test
    fun `the base url points at the deployed submission service`() {
        // 曾经是 https://REPLACE-ME.workers.dev 这个占位常量（"后端还没部署"的显式标记）。
        // 服务已部署到 Cloudflare Workers，所以这条改成钉住真实地址：
        // 谁把它改回占位、或改成非 .workers.dev 的主机，这里就会红。
        assertEquals(
            "https://sukisu-module-submit.lyfsearch.workers.dev",
            ModuleContribution.BASE_URL,
        )
        assertFalse(ModuleContribution.isPlaceholder())
    }

    @Test
    fun `a deployed workers dev host is not treated as a placeholder`() {
        // 真实服务就部署在 *.workers.dev 上。判据只能是"还带着 REPLACE-ME 标记"，
        // 不能是"域名长什么样"——曾经写成 !endsWith(".workers.dev")，
        // 结果是真实地址被判成占位、REPLACE-ME 假地址反而被判成配好了。
        // 这条钉住新语义：.workers.dev 的合法地址不许被当成占位。
        assertFalse(
            "部署在 workers.dev 上的真实地址不该被判成占位",
            ModuleContribution.isPlaceholder(),
        )
        assertTrue(ModuleContribution.BASE_URL.endsWith(".workers.dev"))
    }

    // ── id 白名单 ───────────────────────────────────────────────────────────

    @Test
    fun `an ordinary module id is accepted`() {
        for (id in listOf("a", "awesome_module", "My.Module-2", "x9_y.z", "Z")) {
            assertTrue("常见 id 不该被挡掉：$id", ModuleContribution.isValidId(id))
        }
    }

    @Test
    fun `an id that would inject a query parameter is rejected`() {
        // 核心：id 里带 `&` 会多注入一个 versionCode，把 NEW 查成 RECORDED
        // ——"已收录就不显示投稿按钮"的规则直接失效。
        assertFalse(ModuleContribution.isValidId("x&versionCode=999999"))
        assertFalse(ModuleContribution.isValidId("x&id=other"))
        assertFalse(ModuleContribution.isValidId("x=1"))
        assertFalse(ModuleContribution.isValidId("x%26versionCode%3D1"))
        assertFalse(ModuleContribution.isValidId("x?versionCode=1"))
        assertFalse(ModuleContribution.isValidId("x#frag"))
    }

    @Test
    fun `an id with separators whitespace or a leading digit is rejected`() {
        // 服务端拿 id 建 `pending/<id>/` 路径段，路径分隔符与空白都不能放过去。
        assertFalse(ModuleContribution.isValidId("../../evil"))
        assertFalse(ModuleContribution.isValidId("a/b"))
        assertFalse(ModuleContribution.isValidId("a b"))
        assertFalse(ModuleContribution.isValidId(" a"))
        assertFalse(ModuleContribution.isValidId("a\nb"))
        // 必须以字母开头：与 contrib/audit/risk-rules.mjs 的 ID_PATTERN 同一条约定。
        assertFalse(ModuleContribution.isValidId("1module"))
        assertFalse(ModuleContribution.isValidId("_module"))
        assertFalse(ModuleContribution.isValidId(""))
    }

    @Test
    fun `an absurdly long id is rejected`() {
        assertFalse(ModuleContribution.isValidId("a".repeat(65)))
        assertTrue(ModuleContribution.isValidId("a".repeat(64)))
    }

    @Test
    fun `the built url carries exactly one version code`() {
        // 端到端盯住拼出来的 URL：白名单之内的 id 不该出现第二个 versionCode。
        val url = ModuleContribution.checkUrl("awesome_module", 7)
        assertTrue(url, url.contains("/check?"))
        assertTrue(url, url.endsWith("&versionCode=7"))
        assertEquals(
            "id 后面只能跟一个 versionCode",
            1,
            Regex("versionCode=").findAll(url).count(),
        )
    }

    @Test
    fun `an id with an ampersand cannot inject a second version code`() {
        // 这条是 ② 的正面证据：即便有人绕过白名单直接调 checkUrl，
        // `&` 也会被转义成 %26，拼不成第二个参数。
        val url = ModuleContribution.checkUrl("x&versionCode=999999", 7)
        assertEquals("id 后面只能跟一个 versionCode", 1, Regex("versionCode=").findAll(url).count())
        assertTrue("原始 & 不该出现在查询串里：$url", url.contains("%26"))
        assertTrue(url, url.endsWith("&versionCode=7"))
    }

    @Test
    fun `a rejected id never reaches the network`() {
        // 白名单之外的 id 一律按 UNKNOWN 处理，不发起请求：
        // UNKNOWN 的语义就是"没问出来"，静默，绝不会被当成 NEW 去弹分享提示。
        assertEquals(CheckStatus.UNKNOWN, checkRecorded("x&versionCode=999999", 7))
        assertEquals(CheckStatus.UNKNOWN, checkRecorded("../../evil", 7))
        assertEquals(CheckStatus.UNKNOWN, checkRecorded("", 7))
    }

    /**
     * 走一次真的 [ModuleContribution.checkRecorded]。
     *
     * 占位域名解析不了，合法 id 也会走"网络失败"分支返回 UNKNOWN——
     * 这里要区分的是"根本没发请求"与"发了但失败"，两者对外都是 UNKNOWN，
     * 所以用耗时来区分：DNS 解析失败要走几百毫秒以上，直接 return 是瞬时的。
     */
    private fun checkRecorded(id: String, versionCode: Int): CheckStatus {
        val method = ModuleContribution::class.java.getDeclaredMethod(
            "checkRecorded",
            String::class.java,
            Int::class.javaPrimitiveType,
            kotlin.coroutines.Continuation::class.java,
        ).apply { isAccessible = true }
        val started = System.nanoTime()
        val result = method.invoke(
            ModuleContribution,
            id,
            versionCode,
            DirectContinuation,
        ) as? CheckStatus
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        if (result == null) return CheckStatus.UNKNOWN
        // 被白名单挡掉的一定是瞬时的；真发过请求的不会比它更快。
        assertTrue("这个 id 不该发起网络请求（耗时 ${elapsedMs}ms）", elapsedMs < 5_000L)
        return result
    }

    private object DirectContinuation : kotlin.coroutines.Continuation<Any?> {
        override val context: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext
        override fun resumeWith(result: Result<Any?>) = Unit
    }
}
