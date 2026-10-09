package com.sukisu.ultra.ui.screen.settings.backup

/**
 * 收起状态下，云端那一行右侧显示的地址。
 *
 * 整条 URL 会很长（Nextcloud 的模板是 `https://your-cloud/remote.php/dav/files/USERNAME/SukiSU-Backup`，
 * 60 个字符），在一行里放不下就会折成两行——而收起状态的全部意义就是"只占一行"。主机名足够让
 * 用户认出自己填的是哪家，要看完整地址展开即可。
 *
 * 纯函数，所以能在 JVM 单测里直接断言（UI 里没法测字符串）。
 */
object CloudSummary {

    /**
     * @param url 输入框里当前的地址
     * @param configured 是否已经保存过（未保存时地址可能只是刚点预设填进去的模板）
     * @param unset 未配置时的占位文案（已本地化）
     */
    fun of(url: String, configured: Boolean, unset: String): String {
        if (!configured) return unset
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return unset
        return hostOf(trimmed) ?: trimmed
    }

    /**
     * 取主机名（带非默认端口）；`URI` 认不出来（缺 scheme、含空格等）时返回 null，由调用方退回原文。
     *
     * 端口要留着：NAS 常跑在 5006 这种非标端口上，`your-nas` 和 `your-nas:5006` 对用户来说是
     * 两个不同的地址。
     */
    private fun hostOf(url: String): String? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        return if (uri.port == -1) host else "$host:${uri.port}"
    }
}
