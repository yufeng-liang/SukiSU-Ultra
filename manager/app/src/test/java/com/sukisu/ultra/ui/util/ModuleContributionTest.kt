package com.sukisu.ultra.ui.util

import com.sukisu.ultra.ui.component.ModuleContributionRules
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

    private val readUidLookup = ModuleContribution::class.java
        .getDeclaredMethod("readUidLookup", okhttp3.Response::class.java)
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

    private fun uidOf(code: Int, bodyText: String): CoolapkUidLookup =
        readUidLookup.invoke(ModuleContribution, response(code, bodyText)) as CoolapkUidLookup

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
        assertTrue(result.message, result.message.contains("缺少 module.prop"))
        // HTTP 码一起留着：body 不是 JSON 或没有 error 时，它是用户仅有的一句。
        assertTrue(result.message, result.message.contains("400"))
    }

    @Test
    fun `a 403 ownership rejection keeps the reason that the nonce rotation reads`() {
        // 服务端把"口令已经用过"回在 403 的 body.error 里（worker/src/index.ts 的 rejectOwnership）。
        // 这句话是 App 侧唯一的重掷 nonce 依据：丢了它，那条判定就退化成"从不重掷"，
        // 而用户只会看到一句没有原因的"服务端返回 HTTP 403"。
        val result = submitOf(
            403,
            """{"ok":false,"error":"这个口令已经用过，请重新生成后再提交"}""",
        )
        assertFalse(result.ok)
        assertTrue(result.message, result.message.contains("口令已经用过"))
        assertTrue(result.message, result.message.contains("403"))
        // 同一个 403 的另一种原因（等级不够）不该被混成一句话：两种失败用户要做的事相反。
        val level = submitOf(403, """{"ok":false,"error":"投稿需要酷安等级 Lv2 及以上"}""")
        assertTrue(level.message, level.message.contains("Lv2"))
    }

    @Test
    fun `a non 2xx reason is bounded so a giant error body cannot become the error line`() {
        // 这一句是对话框里**原样渲染**的一行错误提示，而服务端并不保证它是"给人看的一句话"：
        // 中间夹着 Cloudflare，出错时回的是上游栈回显 / 整页 HTML，几 KB 起步。
        // 不设上限，一次失败就会把口令行下面撑出一整屏网页 —— 用户既读不出原因，也复制不了口令。
        val giant = "E".repeat(5_000)
        val result = submitOf(503, """{"ok":false,"error":"$giant"}""")
        assertFalse(result.ok)
        // 钉住上限本身：谁把 200 改成别的数（或把这层 take 删掉），这条就红。
        assertEquals("E".repeat(200) + "（服务端返回 HTTP 503）", result.message)
        // 非 JSON 的错误页压根进不了 error 字段，落到那句短兜底，同样撑不出屏幕。
        val html = submitOf(503, "<html><body>" + "x".repeat(4_000) + "</body></html>")
        assertEquals("投稿失败（服务端返回 HTTP 503）", html.message)
        // 上限不能把重掷判据要吃的那半句切掉：proofWasAlreadyUsed 认的是句首的「口令已经用过」。
        val used = "这个口令已经用过，请重新生成后再提交"
        val rotated = submitOf(403, """{"ok":false,"error":"$used${"（请重试）".repeat(400)}"}""")
        assertTrue(
            "截断后判据仍要认得出这一句",
            ModuleContributionRules.proofWasAlreadyUsed(rotated.message),
        )
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

    // ── lookupCoolapkUser（/user）的解析 ────────────────────────────────────

    @Test
    fun `the four documented uid states map to their results`() {
        assertEquals(
            CoolapkUidLookup.Verified("彧祜"),
            uidOf(200, """{"status":"verified","uid":"2781460","nickname":"彧祜"}"""),
        )
        assertEquals(CoolapkUidLookup.NotFound, uidOf(200, """{"status":"not_found","uid":"99999999"}"""))
        assertEquals(CoolapkUidLookup.SelfReported, uidOf(200, """{"status":"self_reported","uid":"1"}"""))
        assertEquals(CoolapkUidLookup.Unavailable, uidOf(200, """{"status":"unavailable"}"""))
    }

    @Test
    fun `a verified response without a nickname is not verified`() {
        // 没有昵称就没有可署名的东西：宁可当"没核实成"，也不能放一个空署名进审核队列。
        assertEquals(CoolapkUidLookup.Unavailable, uidOf(200, """{"status":"verified","uid":"1"}"""))
        assertEquals(CoolapkUidLookup.Unavailable, uidOf(200, """{"status":"verified","nickname":"  "}"""))
    }

    @Test
    fun `anything unexpected degrades to unavailable never to verified`() {
        // 服务端承诺任何情况都回 200；收到非 2xx 说明部署版本不对或中间有劫持 ——
        // 那种时候只能当"这次没查成"，绝不能升格成 verified（那是昵称属于这个 UID 的唯一凭据）。
        for (code in listOf(500, 404, 302, 403)) {
            assertEquals("HTTP $code", CoolapkUidLookup.Unavailable, uidOf(code, """{"status":"verified","nickname":"x"}"""))
        }
        assertEquals(CoolapkUidLookup.Unavailable, uidOf(200, "not json"))
        assertEquals(CoolapkUidLookup.Unavailable, uidOf(200, """{"foo":"bar"}"""))
        assertEquals(CoolapkUidLookup.Unavailable, uidOf(200, ""))
    }

    @Test
    fun `the user url carries the uid as a query parameter`() {
        assertEquals(
            "https://sukisu-module-api.pages.dev/user?uid=2781460",
            ModuleContribution.userUrl("2781460"),
        )
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
        // 现在钉住真实地址：Cloudflare Pages 网关（它转发到 Worker，因为 workers.dev
        // 在国内被 DNS 投毒 + SNI 阻断，直连必然失败）。谁把它改回占位或改成别的
        // 主机，这里就会红。
        assertEquals(
            "https://sukisu-module-api.pages.dev",
            ModuleContribution.BASE_URL,
        )
        assertFalse(ModuleContribution.isPlaceholder())
    }

    @Test
    fun `a deployed host is not treated as a placeholder`() {
        // 判据只能是"还带着 REPLACE-ME 标记"，不能是"域名长什么样"——曾经写成
        // !endsWith(".workers.dev")，结果是真实地址被判成占位、REPLACE-ME 假地址
        // 反而被判成配好了。地址从 workers.dev 换到 pages.dev 也印证了这一点：
        // 钉域名形状的判据，换一次入口就错一次。
        assertFalse(
            "部署在 pages.dev 上的真实地址不该被判成占位",
            ModuleContribution.isPlaceholder(),
        )
        assertTrue(ModuleContribution.BASE_URL.endsWith(".pages.dev"))
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
