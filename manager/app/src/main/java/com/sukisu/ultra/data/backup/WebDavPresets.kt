package com.sukisu.ultra.data.backup

import androidx.annotation.StringRes
import com.sukisu.ultra.R

/**
 * 常见服务商的地址模板与提示。
 *
 * 小白用户唯一卡住的地方是"不知道填什么地址、不知道要开应用密码、不知道自己有没有 WebDAV"，
 * 预设只负责把地址填好，[hintRes] 那句才是真正教会他的东西——所以它必须显示在界面上，
 * 而且必须是资源 id：这些提示要跟着语言走，不能在代码里写死中文。
 *
 * [id] 是**落盘用的**槽位名（见 [CloudProfileBook]）：每个服务商各记一份没保存的草稿，
 * 靠它认。不能用 [label] 顶替——那是给人看的文案，跟着语言走、随时会改，拿它当键等于把
 * 用户存下的配置绑在中文措辞上。
 */
data class WebDavPreset(
    val id: String,
    val label: String,
    val urlTemplate: String,
    @StringRes val hintRes: Int,
)

object WebDavPresets {
    val ALL = listOf(
        WebDavPreset(
            id = "nutstore",
            label = "坚果云",
            urlTemplate = "https://dav.jianguoyun.com/dav/SukiSU-Backup",
            hintRes = R.string.backup_preset_hint_nutstore,
        ),
        WebDavPreset(
            id = "synology",
            label = "群晖 NAS",
            urlTemplate = "https://your-nas:5006/SukiSU-Backup",
            hintRes = R.string.backup_preset_hint_synology,
        ),
        WebDavPreset(
            id = "nextcloud",
            label = "Nextcloud",
            urlTemplate = "https://your-cloud/remote.php/dav/files/USERNAME/SukiSU-Backup",
            hintRes = R.string.backup_preset_hint_nextcloud,
        ),
        WebDavPreset(
            id = "alist",
            label = "Alist / OpenList",
            urlTemplate = "https://your-alist/dav/SukiSU-Backup",
            hintRes = R.string.backup_preset_hint_alist,
        ),
    )

    fun byId(id: String?): WebDavPreset? = id?.let { wanted -> ALL.firstOrNull { it.id == wanted } }

    /**
     * 地址正好等于某个模板时，那个预设就是"选中的"。
     *
     * 页面重开时用它把高亮恢复回来：用户上次点的是坚果云，这次进来看见坚果云还是亮的，
     * 不用回忆自己当初点的是哪个。只认完全相等——用户改过地址（Nextcloud 的模板带
     * `USERNAME`，必须改）之后就不是模板了，此时不高亮比错高亮一个更诚实。
     */
    fun match(url: String): WebDavPreset? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        return ALL.firstOrNull { it.urlTemplate == trimmed }
    }
}
