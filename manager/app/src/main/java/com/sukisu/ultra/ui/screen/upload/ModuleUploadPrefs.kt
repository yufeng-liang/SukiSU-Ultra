package com.sukisu.ultra.ui.screen.upload

import android.content.Context
import androidx.core.content.edit
import com.sukisu.ultra.ksuApp
import com.sukisu.ultra.ui.util.UploadSession

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

    // ---- 长期会话令牌（一次改签名，九十天免改）----
    //
    // 四个值分开存、都带类型：JSON 里塞一个对象在 SharedPreferences 上不可读，
    // 出问题时（比如令牌串被手改成 63 位）也没法只坏一处。分开存之后
    // `readSession()` 能逐项判定，坏哪一项就整份当没有。
    const val KEY_SESSION_TOKEN = "module_upload_session_token"
    const val KEY_SESSION_UID = "module_upload_session_uid"
    const val KEY_SESSION_NICK = "module_upload_session_nickname"
    const val KEY_SESSION_AVATAR = "module_upload_session_avatar"
    const val KEY_SESSION_EXPIRES_AT = "module_upload_session_expires_at"

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

    /**
     * 读本地存的那一份长期令牌；没存过、存坏了、或者已经到期，都返回 null。
     *
     * 到期判定放在这一层而不是界面：界面每次重组都会问一次，判据只有一处才不会
     * 出现「一个界面说过期、另一个说没过期」。真正的作数方仍是服务端 ——
     * 本地改 `expiresAt` 不会延长任何东西，只会让 App 更早/更晚去问一次。
     */
    fun readSession(now: Long = System.currentTimeMillis()): UploadSession? = runCatching {
        val p = prefs() ?: return null
        val session = UploadSession(
            token = p.getString(KEY_SESSION_TOKEN, null).orEmpty(),
            uid = p.getString(KEY_SESSION_UID, null).orEmpty(),
            nickname = p.getString(KEY_SESSION_NICK, null).orEmpty(),
            avatar = p.getString(KEY_SESSION_AVATAR, null).orEmpty(),
            expiresAt = p.getLong(KEY_SESSION_EXPIRES_AT, 0L),
        )
        session.takeIf { it.validAt(now) }
    }.getOrNull()

    /** 存下一张令牌。形状不对的一律不存（脏数据不许落到盘上）。 */
    fun writeSession(session: UploadSession) {
        if (!session.wellFormed) return
        runCatching {
            prefs()?.edit {
                putString(KEY_SESSION_TOKEN, session.token)
                putString(KEY_SESSION_UID, session.uid)
                putString(KEY_SESSION_NICK, session.nickname)
                putString(KEY_SESSION_AVATAR, session.avatar)
                putLong(KEY_SESSION_EXPIRES_AT, session.expiresAt)
            }
        }
    }

    /** 「退出验证」：把本地这一份抹掉。服务端那张另外吊销（见 ViewModel）。 */
    fun clearSession() {
        runCatching {
            prefs()?.edit {
                remove(KEY_SESSION_TOKEN)
                remove(KEY_SESSION_UID)
                remove(KEY_SESSION_NICK)
                remove(KEY_SESSION_AVATAR)
                remove(KEY_SESSION_EXPIRES_AT)
            }
        }
    }

    private fun prefs() = runCatching {
        ksuApp.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }.getOrNull()
}
