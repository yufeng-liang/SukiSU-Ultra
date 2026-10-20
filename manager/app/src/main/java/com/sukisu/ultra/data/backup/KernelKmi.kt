package com.sukisu.ultra.data.backup

/**
 * GKI 内核的 KMI（Kernel Module Interface）指纹。
 *
 * `uname -r` 的形状是 `<版本>.<大版本>.<补丁>-android<release>-<kmi>-<hash>`，例如
 * `5.15.104-android13-8-g1234567890ab`。KMI 只由 `android<release>-<kmi>` 两段决定
 * （开头的 `5.15` 是它的载体，同一 KMI 下补丁版本随便升）：
 *
 * - KMI 相同：月度 OTA（只升补丁号）之后把旧快照刷回去大概率照常开机——GKI 冻结了
 *   内核与 vendor 模块之间的接口；
 * - KMI 不同（大版本升级、或同代升了 generation）：旧内核和新系统 vendor_dlkm 里的
 *   模块 vermagic 对不上，大概率卡在开机画面。
 *
 * 只做 UI 警告不做数据层拦截：同 KMI 恢复是完全正当的场景，而「认不出」（非 GKI、
 * 自定义内核改了命名）宁可少管也不误伤——见 [changed] 的 null 语义。
 */
object KernelKmi {

    /**
     * 从 `uname -r` 里抓 KMI 三段：版本前缀（`5.15`）、android release（`13`）、generation（`8`）。
     *
     * android 段之前允许有任意内容（自定义内核会往版本号里缀东西），段本身必须以
     * `android<数字>-<数字>` 开头才认。补丁号（`5.15.104` 的 `104`）可有可无——两段式的
     * `5.15-android13-8` 少见但存在，认不出它的代价是少警告一次，宁滥勿缺。
     */
    private val KMI = Regex("^(\\d+\\.\\d+)(?:\\.\\d+)?.*android(\\d+)-(\\d+)")

    /**
     * [release] 的 KMI 键，如 `5.15-android13-8`；认不出返回 null。
     *
     * 非 GKI 内核（`4.14.190-perf+`）没有 android 段；自定义内核把名字缀在尾部
     * （`6.1.75-android14-6-SukiSU-custom`）不影响解析——android 段之前的任意内容都会被跳过。
     */
    fun kmiOf(release: String?): String? {
        if (release.isNullOrBlank()) return null
        val match = KMI.find(release.trim()) ?: return null
        return "${match.groupValues[1]}-android${match.groupValues[2]}-${match.groupValues[3]}"
    }

    /**
     * 快照与当前系统是否属于不同 KMI。
     *
     * 只有两边都认得出才给结论；任何一边认不出（null、空白、非 GKI 命名）都返回 null，
     * 表示「没法判断」——调用方照旧走普通确认，不弹警告。
     */
    fun changed(snapshotRelease: String?, currentRelease: String?): Boolean? {
        val snapshot = kmiOf(snapshotRelease) ?: return null
        val current = kmiOf(currentRelease) ?: return null
        return snapshot != current
    }
}
