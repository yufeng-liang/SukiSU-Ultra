package com.sukisu.ultra.data.backup

/**
 * 用户经常把凭据直接写进 WebDAV URL（https://user:pass@host/dav），
 * OkHttp 的异常 message 会把整条 URL 带出来，最终显示到 UI。
 * 所有进入 BackupFailure.cause 与 UI 文案的字符串都先过这里。
 *
 * userinfo 只可能在 scheme 之后、第一个 `/`、`?`、`#` 之前出现，所以字符类把这几个都排除掉：
 * 这样路径与查询串里的 `@`（…/a@b.zip、?user=x@y）不会被误判成凭据，端口（host:8443）也不会。
 * 用户名可以缺（`https://:token@host` 是 token 直接当密码用的常见写法），密码也可以缺
 * （`https://user:@host`），两者都算凭据。
 *
 * 已知不覆盖：密码里带 `/`（RFC 3986 不允许出现在 userinfo 里）与没有 scheme 的 `user:pass@host`——
 * 后者无法与普通文本区分，硬匹配会误伤路径。
 */
object Redaction {

    private val CREDENTIALS =
        Regex("""([a-zA-Z][a-zA-Z0-9+.-]*://)([^/@\s?#:]*)(:[^/@\s?#]*)?@""")

    fun redactUrl(text: String): String = CREDENTIALS.replace(text) { match ->
        val prefix = match.groupValues[1]
        // 第三组为空串 = 压根没有 `:密码` 这一段（Kotlin 里未参与匹配的组取空串）。
        if (match.groupValues[3].isEmpty()) "${prefix}***@" else "${prefix}***:***@"
    }

    fun redactMessage(text: String?): String = text?.let { redactUrl(it) } ?: ""
}
