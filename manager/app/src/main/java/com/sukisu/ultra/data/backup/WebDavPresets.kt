package com.sukisu.ultra.data.backup

import androidx.annotation.StringRes
import com.sukisu.ultra.R

/**
 * 常见服务商的地址模板与提示。
 *
 * 小白用户唯一卡住的地方是"不知道填什么地址、不知道要开应用密码、不知道自己有没有 WebDAV"，
 * 预设只负责把地址填好，[hintRes] 那句才是真正教会他的东西——所以它必须显示在界面上，
 * 而且必须是资源 id：这些提示要跟着语言走，不能在代码里写死中文。
 */
data class WebDavPreset(
    val label: String,
    val urlTemplate: String,
    @StringRes val hintRes: Int,
)

object WebDavPresets {
    val ALL = listOf(
        WebDavPreset(
            label = "坚果云",
            urlTemplate = "https://dav.jianguoyun.com/dav/SukiSU-Backup",
            hintRes = R.string.backup_preset_hint_nutstore,
        ),
        WebDavPreset(
            label = "群晖 NAS",
            urlTemplate = "https://your-nas:5006/SukiSU-Backup",
            hintRes = R.string.backup_preset_hint_synology,
        ),
        WebDavPreset(
            label = "Nextcloud",
            urlTemplate = "https://your-cloud/remote.php/dav/files/USERNAME/SukiSU-Backup",
            hintRes = R.string.backup_preset_hint_nextcloud,
        ),
        WebDavPreset(
            label = "Alist / OpenList",
            urlTemplate = "https://your-alist/dav/SukiSU-Backup",
            hintRes = R.string.backup_preset_hint_alist,
        ),
    )
}
