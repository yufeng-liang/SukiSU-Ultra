package com.sukisu.ultra.ui.screen.upload

import android.content.Context
import androidx.core.content.edit
import com.sukisu.ultra.ksuApp

/**
 * 上传表单记住的「上次用的酷安 UID」。
 *
 * 只记这一项：它是每次都要重新输入、又几乎不变的那一个字段。出处、勾选、口令都不记——
 * 那些是「这一次上传」的知情与承诺，跨次复用等于替用户提前签了字。
 *
 * key 与读写单独放出来，和 [com.sukisu.ultra.data.repository.ModuleContributionPrefs]
 * 同一个理由：key 一改名，老用户会静默回到空输入，而这不是一个能被察觉的错误。
 */
object ModuleUploadPrefs {

    const val KEY_LAST_UID = "module_upload_last_uid"

    /** 与 [com.sukisu.ultra.ui.util.ModuleContribution.isValidUid] 的 `\d{4,12}` 同一条形状。 */
    private const val UID_MAX_LEN = 12

    /** 输入框的过滤：只留数字并截到 12 位（纯函数，单测直接锁住）。 */
    fun sanitizeUid(raw: String): String = raw.filter { it.isDigit() }.take(UID_MAX_LEN)

    /** 读上次的 UID；没存过或存坏了都返回空串（空串在界面上就是「没填」）。 */
    fun lastUid(): String = runCatching {
        prefs()?.getString(KEY_LAST_UID, null).orEmpty()
    }.getOrDefault("")

    /** 记下这次核实通过的 UID。写失败不影响本次上传。 */
    fun rememberUid(uid: String) {
        val cleaned = sanitizeUid(uid)
        if (cleaned.isEmpty()) return
        runCatching { prefs()?.edit { putString(KEY_LAST_UID, cleaned) } }
    }

    private fun prefs() = runCatching {
        ksuApp.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }.getOrNull()
}
