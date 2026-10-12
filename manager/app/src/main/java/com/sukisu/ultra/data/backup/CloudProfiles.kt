package com.sukisu.ultra.data.backup

import org.json.JSONObject

/**
 * 某个服务商槽位里记着的那一份云端配置。
 *
 * [password] 在内存里是明文（表单要用它去连），落盘由 [CloudProfileBookJson] 做信封加密——
 * 和已保存的那份凭据同一个规矩：明文绝不进 SharedPreferences。
 */
data class CloudProfile(
    val url: String = "",
    val user: String = "",
    val password: String = "",
) {
    val isBlank: Boolean get() = url.isBlank() && user.isBlank() && password.isBlank()

    /** 能不能真的拿去连一次：地址和用户名是 WebDAV 的最低要求。 */
    val isUsable: Boolean get() = url.isNotBlank() && user.isNotBlank()
}

/** 槽位名：四个服务商各一个，剩下所有手填的地址共用一个。 */
object CloudSlots {
    const val CUSTOM = "custom"

    fun of(preset: WebDavPreset?): String = preset?.id ?: CUSTOM

    fun presetOf(slot: String?): WebDavPreset? = WebDavPresets.byId(slot)
}

/**
 * 每个服务商各记一份配置，其中只有一份是"正在生效"的。
 *
 * 解决的问题是两件具体的事：①选了某个服务商、填了地址和密码但没点保存就离开页面，回来时
 * 那几行字还在；②配过好几家，在芯片之间来回点时谁也不该被冲掉。
 *
 * 三条不变量：
 * - [drafts] 只存**没生效**的那几份。生效的那份就是 `settings.webDavUrl/User/Password`
 *   本身，不在这里再抄一遍——抄一份就有两个真相，改了设置忘了改草稿就会连错服务器。
 * - 保存一个槽位时，原来生效的那家先被收进 [drafts]（[withSaved]），所以"配了多家"是可能的：
 *   切回去照样填着上次的东西。
 * - [last] 是用户最后看着的那个槽位（不一定是生效的那个）。离开页面再回来要回到他刚才那屏，
 *   否则他会以为刚填的东西没了。
 */
data class CloudProfileBook(
    val drafts: Map<String, CloudProfile> = emptyMap(),
    /** 正在生效的槽位；null 表示还没配过任何云端。 */
    val active: String? = null,
    /** 最后在看的槽位；null 表示还没选过（按生效的那家或手填槽位开局）。 */
    val last: String? = null,
) {

    /**
     * 某个槽位现在该显示的那份配置。
     *
     * 顺序是"草稿 → 生效的那份 → 空"。生效的那家没有草稿（保存时就清掉了），所以这里回落到
     * [live] 正好让用户看见自己配的是什么；别的槽位没有草稿就是一张白纸，由界面决定要不要
     * 拿模板去填（点芯片才算数，重开页面不该凭空多出一个地址）。
     */
    fun profileFor(slot: String, live: CloudProfile): CloudProfile =
        drafts[slot] ?: if (slot == active) live else CloudProfile()

    /** 记下这个槽位刚被编辑成什么样，并把它记为"最后在看的那个"。 */
    fun withEdit(slot: String, profile: CloudProfile): CloudProfileBook =
        copy(drafts = drafts + (slot to profile), last = slot)

    /** 只换当前在看的槽位（点芯片），不动任何草稿。 */
    fun withSlot(slot: String): CloudProfileBook = copy(last = slot)

    /**
     * 这个槽位保存成功了：它成为生效的那家。
     *
     * 原来生效的那家先收进草稿再让位——不收的话，用户在 B 上点保存，A 的地址和密码就随着
     * `settings` 被覆盖而彻底消失了，"配了多家"也就无从谈起。
     * 新生效的这家把自己的草稿删掉：它的值现在就是 `settings` 里那份，留着就是第二个真相。
     */
    fun withSaved(slot: String, live: CloudProfile): CloudProfileBook {
        val stashed = if (active != null && active != slot && !live.isBlank) {
            drafts + (active to live)
        } else {
            drafts
        }
        return copy(drafts = stashed - slot, active = slot, last = slot)
    }

    /**
     * 记着可用配置的槽位：界面上给这几个打点，用户一眼看出哪几家填过。
     *
     * 判据是[CloudProfile.isUsable]而不是"有草稿"：点一下芯片只是把模板填进地址栏，地址有了、
     * 用户名还空着——那不是一份配置，给它打点等于骗用户"这家配好了"。
     */
    fun remembered(live: CloudProfile): Set<String> {
        val fromDrafts = drafts.filterValues { it.isUsable }.keys
        val activeSlot = active?.takeIf { live.isUsable }
        return fromDrafts + listOfNotNull(activeSlot)
    }

    companion object {
        /**
         * 升级路径：这本书还不知道哪一家在生效，而设置里已经存着一份配置。
         *
         * 这本书是后加的，老用户的 prefs 里只有那三个值。不认一次的话，他们打开页面会看到
         * **空表单**——地址栏里一个字都没有，而"备份写到哪儿"那行又明明白白写着他的服务器。
         * 地址能对上某个模板就认那一家，对不上（Nextcloud 改过 `USERNAME`、自建的）算手填槽位。
         */
        fun migrated(book: CloudProfileBook, savedUrl: String): CloudProfileBook {
            if (book.active != null || savedUrl.isBlank()) return book
            val slot = CloudSlots.of(WebDavPresets.match(savedUrl))
            return book.copy(active = slot, last = book.last ?: slot)
        }
    }
}

/**
 * 草稿的 JSON 编解码。
 *
 * 密码走信封加密，[encrypt] / [decrypt] 由调用方注入：真机上是 AndroidKeyStore 那把密钥，
 * 单测里是一把现造的 AES 密钥——`KeystoreKeyProvider` 在纯 JVM 上拿不到。
 *
 * 解不出来一律退化成空书而不是抛异常：一份读不懂的草稿不该让备份页整个打不开。少的是
 * "上次填到一半的那几行字"，用户重填一遍就行；崩掉的话连已保存的云端都进不去。
 */
object CloudProfileBookJson {

    private const val SCHEMA = "sukisu.backup.cloud-profiles"

    /** 加密失败（密钥库不可用）时返回 null，那一格密码就不落盘。 */
    fun render(book: CloudProfileBook, encrypt: (String) -> String?): String = JSONObject().apply {
        put("schema", SCHEMA)
        put("version", ArchiveNaming.MANIFEST_VERSION)
        put("active", book.active ?: JSONObject.NULL)
        put("last", book.last ?: JSONObject.NULL)
        put(
            "drafts",
            JSONObject().apply {
                book.drafts.forEach { (slot, profile) ->
                    put(
                        slot,
                        JSONObject().apply {
                            put("url", profile.url)
                            put("user", profile.user)
                            // 存不进去就留空：下次回来地址和用户名还在，密码要重填。
                            // 写明文是另一条路——那条路不能走。
                            put("password", profile.password.takeIf { it.isNotBlank() }?.let(encrypt) ?: "")
                        },
                    )
                }
            },
        )
    }.toString()

    fun parse(raw: String?, decrypt: (String) -> String): CloudProfileBook {
        if (raw.isNullOrBlank()) return CloudProfileBook()
        return runCatching {
            val root = JSONObject(raw)
            if (root.optString("schema") != SCHEMA) return CloudProfileBook()
            val draftsJson = root.optJSONObject("drafts") ?: JSONObject()
            val drafts = draftsJson.keys().asSequence().associateWith { slot ->
                val item = draftsJson.optJSONObject(slot) ?: JSONObject()
                CloudProfile(
                    url = item.optString("url"),
                    user = item.optString("user"),
                    password = item.optString("password").takeIf { it.isNotBlank() }
                        ?.let { runCatching { decrypt(it) }.getOrDefault("") }
                        .orEmpty(),
                )
            }.filterValues { !it.isBlank }
            CloudProfileBook(
                drafts = drafts,
                active = root.optStringOrNull("active"),
                last = root.optStringOrNull("last"),
            )
        }.getOrDefault(CloudProfileBook())
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}
