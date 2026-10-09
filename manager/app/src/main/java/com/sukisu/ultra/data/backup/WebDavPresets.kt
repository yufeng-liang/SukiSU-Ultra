package com.sukisu.ultra.data.backup

/**
 * 常见服务商的地址模板。小白用户唯一卡住的地方是"不知道填什么地址、不知道要开应用密码"，
 * 预设只负责把地址填好，用户名/密码仍需用户自己填（各账号不同）。
 */
data class WebDavPreset(val label: String, val urlTemplate: String, val hint: String)

object WebDavPresets {
    val ALL = listOf(
        WebDavPreset("坚果云", "https://dav.jianguoyun.com/dav/SukiSU-Backup", "在「账户信息 → 安全选项 → 第三方应用管理」添加应用并生成应用密码"),
        WebDavPreset("群晖 NAS", "https://your-nas:5006/SukiSU-Backup", "需在套件中心启用 WebDAV Server，端口 5005(HTTP)/5006(HTTPS)"),
        WebDavPreset("Nextcloud", "https://your-cloud/remote.php/dav/files/USERNAME/SukiSU-Backup", "密码栏填应用密码（设置 → 安全 → 创建新应用密码）"),
        WebDavPreset("Alist / OpenList", "https://your-alist/dav/SukiSU-Backup", "用 Alist 把阿里云盘/夸克/百度等挂载后，即可通过 WebDAV 备份到这些网盘"),
    )
}
