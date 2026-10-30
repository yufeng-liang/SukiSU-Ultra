package com.sukisu.ultra.ui.screen.upload

import android.content.Context
import androidx.core.content.edit
import com.sukisu.ultra.data.backup.sha256Hex
import com.sukisu.ultra.ksuApp
import com.sukisu.ultra.ui.util.UploadSession
import java.security.SecureRandom

/**
 * 上传表单记住的「上次用的酷安 UID」。
 *
 * 只记这一项：它是每次都要重新输入、又几乎不变的那一个字段。出处、勾选、口令都不记——
 * 那些是「这一次上传」的知情与承诺，跨次复用等于替用户提前签了字。
 *
 * key 与读写单独放出来，和 [com.sukisu.ultra.data.repository.ModuleContributionPrefs]
 * 同一个理由：key 一改名，老用户会静默回到空输入，而这不是一个能被察觉的错误。
 *
 * ── 为什么令牌单独一个 prefs 文件 ──────────────────────────────────────────────
 *
 * 令牌是**九十天有效的凭据**（服务端 KV 里只存它的 sha256，拿到原文就能冒充这个 UID
 * 提交）。而 Android 的自动备份会把 sharedpref 整份拷进 Google Drive / 换机传输 ——
 * 凭据离开这台设备，等于把「这台设备已验证」变成「任何恢复过这份备份的设备都验证过」。
 *
 * 备份规则只能按**文件**粒度排除，不能按 key 粒度：所以要么把令牌挪进独立文件
 * （`res/xml/backup_rules.xml` 与 `res/xml/data_extraction_rules.xml` 排除它），
 * 要么整份 `settings.xml` 不备份 —— 后者会把 WebDAV 配置、主题、各种开关一起丢掉。
 * 前者的代价写在下面 [PREFS_NAME] 的注释里。
 *
 * 没有选「加密后仍存原 prefs」（复用 `data/backup/CredentialCipher.kt`）：那需要一个
 * 密钥管理的故事（密钥存哪、丢了怎么办、Keystore 不可用时怎么降级），而这条链的收益
 * 只是「备份里那一串不可读」—— 排除整份文件是更小、更可验证的改动。
 */
object ModuleUploadPrefs {

    /**
     * 独立 prefs 文件名。**只放会话令牌那五个键与身份验证串**，不放 UID。
     *
     * 改名（从 `settings` 到这个名字）意味着老用户盘上那一张令牌读不到了 ——
     * 一次性的代价：他们下次投稿要再改一次签名，之后又是九十天。这是刻意的取舍：
     * 把一张九十天凭据留在「会被云备份拷走」的文件里，比让老用户重验一次更贵。
     * 读不到就是读不到，没有迁移代码：`readSession()` 见到空串就返回 null，
     * 界面回到「要改签名」那条路，不需要任何特殊分支。
     */
    private const val PREFS_NAME = "module_upload_session"

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

    // ---- 身份验证串（哈希承诺，九十天内复用）----
    //
    // 与令牌放在同一份 prefs 文件里：它同样是「这台设备已验证过」这件事的依据，
    // 被云备份拷走等于换台机器也能直接换令牌（见文件头那段取舍）。
    const val KEY_IDENTITY_SECRET = "module_upload_identity_secret"

    /** 身份验证串的字节数：16 字节 → 32 位小写十六进制，与契约的 `^[0-9a-f]{32}$` 一致。 */
    private const val SECRET_BYTES = 16

    /** 契约 §4：承诺串是 `sha256("sukisu-identity-v2:" + secret)` 的前 24 位。 */
    private const val IDENTITY_COMMIT_PREFIX = "sukisu-identity-v2:"
    const val IDENTITY_COMMIT_LEN = 24

    private val SECRET_RANDOM = SecureRandom()

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
        val p = sessionPrefs() ?: return null
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
            sessionPrefs()?.edit {
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
            sessionPrefs()?.edit {
                remove(KEY_SESSION_TOKEN)
                remove(KEY_SESSION_UID)
                remove(KEY_SESSION_NICK)
                remove(KEY_SESSION_AVATAR)
                remove(KEY_SESSION_EXPIRES_AT)
            }
        }
    }

    /**
     * 读本地的身份验证串；没存过、存坏了都返回空串（空串在界面上就是「还没生成」）。
     *
     * 形状判定与服务端同一条（`^[0-9a-f]{32}$`）：盘上那串被手改坏了，与其发出去换一个
     * `identity_invalid`，不如当它不存在、由界面重新生成一串。
     */
    fun identitySecret(): String = runCatching {
        val secret = sessionPrefs()?.getString(KEY_IDENTITY_SECRET, null).orEmpty()
        secret.takeIf { SECRET_HEX_RE.matches(it) }.orEmpty()
    }.getOrDefault("")

    /**
     * 生成一串新的身份验证串并落盘。
     *
     * 只在两处调用：本地还没有（或存坏了）、以及用户主动点「换一串」。**不在令牌到期时换**——
     * 换一串就要用户再改一次酷安签名，那正是哈希承诺要省掉的动作。
     */
    fun renewIdentitySecret(): String {
        val bytes = ByteArray(SECRET_BYTES)
        SECRET_RANDOM.nextBytes(bytes)
        val secret = bytes.joinToString("") { "%02x".format(it) }
        runCatching { sessionPrefs()?.edit { putString(KEY_IDENTITY_SECRET, secret) } }
        return secret
    }

    /** 拿本地那串；没有就先生成一串。 */
    fun ensureIdentitySecret(): String = identitySecret().ifEmpty { renewIdentitySecret() }

    /**
     * 承诺串：用户抄进酷安签名的那一行。
     *
     * 与服务端 [com.sukisu.ultra.ui.util.ModuleContribution] 契约同一算法——
     * `sha256("sukisu-identity-v2:" + secret)` 的前 24 位。签名里只有这一串，
     * 反推不出 secret，所以别人看见签名也领不走令牌。
     */
    fun identityCommitment(secret: String): String =
        (IDENTITY_COMMIT_PREFIX + secret).toByteArray(Charsets.UTF_8)
            .sha256Hex()
            .take(IDENTITY_COMMIT_LEN)

    /**
     * 「上次用的 UID」仍然在 `settings` 里：它不是凭据，不需要跟着令牌搬家。
     *
     * 两套 prefs 分开取，是为了让「哪个键在哪一份文件里」只在这一处体现 ——
     * 备份规则排除的是 [PREFS_NAME] 那一份，UID 不受影响。
     */
    private fun prefs() = runCatching {
        ksuApp.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }.getOrNull()

    /** 令牌专用的那一份 prefs（被备份规则排除，见文件头）。 */
    private fun sessionPrefs() = runCatching {
        ksuApp.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }.getOrNull()

    /** 身份验证串的形状：与服务端 `IDENTITY_SECRET_RE` 同一条尺子。 */
    private val SECRET_HEX_RE = Regex("^[0-9a-f]{32}$")
}
