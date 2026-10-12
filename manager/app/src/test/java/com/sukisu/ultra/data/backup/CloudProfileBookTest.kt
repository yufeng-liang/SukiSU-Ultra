package com.sukisu.ultra.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

/**
 * 每个服务商各记一份配置的状态机。
 *
 * 要守住的是两件用户说得出的事：①填了没保存就切走，回来时那几行字还在；②配过好几家，
 * 在芯片之间来回点谁也不被冲掉。
 */
class CloudProfileBookTest {

    private val live = CloudProfile(url = "https://dav.example/dav/x", user = "u", password = "p")

    private fun profile(url: String = "", user: String = "", password: String = "") =
        CloudProfile(url, user, password)

    @Test
    fun `a draft of the current slot wins over the live config`() {
        val book = CloudProfileBook(drafts = mapOf("nutstore" to profile("https://draft", "u2")), active = "nutstore")

        // 用户在这家改过但没保存：表单该显示他改的那份，而不是 settings 里那份。
        assertEquals(profile("https://draft", "u2"), book.profileFor("nutstore", live))
    }

    @Test
    fun `the active slot falls back to the live config`() {
        val book = CloudProfileBook(active = "nutstore")

        assertEquals(live, book.profileFor("nutstore", live))
    }

    @Test
    fun `a slot that was never touched comes back empty`() {
        // 空表单，不是模板：重开页面时凭空多出一个地址会让用户以为"我填过这个"。
        // 模板只在用户点芯片那一下才填（见 selectPreset）。
        assertEquals(CloudProfile(), CloudProfileBook(active = "nutstore").profileFor("alist", live))
    }

    @Test
    fun `editing records the draft and makes it the slot in view`() {
        val edited = profile("https://nas", "me", "pw")
        val book = CloudProfileBook().withEdit("synology", edited)

        assertEquals(mapOf("synology" to edited), book.drafts)
        assertEquals("synology", book.last)
        // 编辑不等于生效：active 还是空的，云端仍按 settings 里那份走。
        assertNull(book.active)
    }

    @Test
    fun `saving stashes the provider that was in effect`() {
        val saved = CloudProfileBook(active = "nutstore").withSaved("synology", live)

        assertEquals("synology", saved.active)
        // 原来生效的坚果云被收进草稿：不收的话，切回坚果云就只剩一个空表单。
        assertEquals(live, saved.drafts["nutstore"])
    }

    @Test
    fun `saving clears the draft of the slot that just took over`() {
        val draft = profile("https://nas", "me", "pw")
        val book = CloudProfileBook(drafts = mapOf("synology" to draft), active = "nutstore")

        val saved = book.withSaved("synology", draft)

        // 这一份现在就是 settings 里那份了，草稿留着就是第二个真相。
        assertNull(saved.drafts["synology"])
        assertEquals("synology", saved.active)
        assertEquals("synology", saved.last)
    }

    @Test
    fun `re-saving the same provider does not stash anything`() {
        val book = CloudProfileBook(active = "nutstore")

        val saved = book.withSaved("nutstore", live)

        assertTrue(saved.drafts.isEmpty())
        assertEquals("nutstore", saved.active)
    }

    @Test
    fun `a blank live config is not stashed as a draft`() {
        // 从没配过云端（active 有名字但三个值都空）时保存别家，不该给原来那家留一份空草稿。
        val book = CloudProfileBook(active = "nutstore").withSaved("synology", CloudProfile())

        assertTrue(book.drafts.isEmpty())
    }

    @Test
    fun `remembered lists the slots holding usable credentials`() {
        val book = CloudProfileBook(
            drafts = mapOf(
                "synology" to profile("https://nas", "me", "pw"),
                "alist" to profile("https://your-alist/dav/SukiSU-Backup"),
            ),
            active = "nutstore",
        )

        // 只填了模板地址、没填用户名的那家不算：点一下芯片就会填出地址，那不是"配好了"。
        assertEquals(setOf("synology", "nutstore"), book.remembered(live))
    }

    @Test
    fun `the active slot is not remembered when the saved config is unusable`() {
        val book = CloudProfileBook(active = "nutstore")

        assertTrue(book.remembered(CloudProfile(url = "https://dav.example")).isEmpty())
    }

    @Test
    fun `migration recovers the active slot from a saved template url`() {
        val migrated = CloudProfileBook.migrated(CloudProfileBook(), WebDavPresets.ALL.first().urlTemplate)

        assertEquals(WebDavPresets.ALL.first().id, migrated.active)
        // 顺带把它设成"当前在看的"，老用户开页面看见的就是自己配的那家。
        assertEquals(migrated.active, migrated.last)
    }

    @Test
    fun `migration falls back to the custom slot for a hand-written url`() {
        val migrated = CloudProfileBook.migrated(CloudProfileBook(), "https://my-own-box:5006/dav/backup")

        assertEquals(CloudSlots.CUSTOM, migrated.active)
    }

    @Test
    fun `migration leaves a book that already knows its active slot alone`() {
        val book = CloudProfileBook(drafts = mapOf("alist" to profile("https://a", "u")), active = "alist", last = "alist")

        assertEquals(book, CloudProfileBook.migrated(book, "https://my-own-box:5006/dav/backup"))
    }

    @Test
    fun `migration leaves a fresh install with nothing saved alone`() {
        assertEquals(CloudProfileBook(), CloudProfileBook.migrated(CloudProfileBook(), ""))
    }
}

/** 草稿落盘：密码走信封加密，读不回来时退化成空书而不是崩掉。 */
class CloudProfileBookJsonTest {

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val encrypt: (String) -> String? = { CredentialCipher.encrypt(key, it) }
    private val decrypt: (String) -> String = { CredentialCipher.decrypt(key, it) }

    private fun roundTrip(book: CloudProfileBook): CloudProfileBook =
        CloudProfileBookJson.parse(CloudProfileBookJson.render(book, encrypt), decrypt)

    @Test
    fun `round trip keeps every slot and the bookkeeping fields`() {
        val book = CloudProfileBook(
            drafts = mapOf(
                "synology" to CloudProfile("https://nas", "me", "app-password"),
                CloudSlots.CUSTOM to CloudProfile("https://own", "root", ""),
            ),
            active = "nutstore",
            last = "synology",
        )

        assertEquals(book, roundTrip(book))
    }

    @Test
    fun `the password is not written in the clear`() {
        val raw = CloudProfileBookJson.render(
            CloudProfileBook(drafts = mapOf("synology" to CloudProfile("https://nas", "me", "s3cret"))),
            encrypt,
        )

        assertTrue("明文密码不该出现在落盘内容里", !raw.contains("s3cret"))
    }

    @Test
    fun `a password that cannot be encrypted is dropped, the rest survives`() {
        // 系统密钥库不可用：地址和用户名照存，密码只能留空——写明文是另一条路，那条不能走。
        val raw = CloudProfileBookJson.render(
            CloudProfileBook(drafts = mapOf("synology" to CloudProfile("https://nas", "me", "s3cret"))),
            encrypt = { null },
        )
        val parsed = CloudProfileBookJson.parse(raw, decrypt)

        assertEquals(CloudProfile("https://nas", "me", ""), parsed.drafts["synology"])
    }

    @Test
    fun `a password that cannot be decrypted comes back empty instead of crashing`() {
        val raw = CloudProfileBookJson.render(
            CloudProfileBook(drafts = mapOf("synology" to CloudProfile("https://nas", "me", "s3cret"))),
            encrypt,
        )

        // 换了密钥（系统清掉了 Keystore）时读回来的是空串，地址和用户名还在。
        val parsed = CloudProfileBookJson.parse(raw, decrypt = { throw IllegalStateException("no key") })

        assertEquals(CloudProfile("https://nas", "me", ""), parsed.drafts["synology"])
    }

    @Test
    fun `an empty or missing blob is an empty book`() {
        assertEquals(CloudProfileBook(), CloudProfileBookJson.parse("", decrypt))
        assertEquals(CloudProfileBook(), CloudProfileBookJson.parse(null, decrypt))
    }

    @Test
    fun `a blob from another schema is ignored`() {
        assertEquals(CloudProfileBook(), CloudProfileBookJson.parse("""{"schema":"something.else"}""", decrypt))
    }

    @Test
    fun `garbage does not take the whole page down`() {
        // 一份读不懂的草稿只该丢掉"上次填到一半的那几行字"，不该让已保存的云端也进不去。
        assertEquals(CloudProfileBook(), CloudProfileBookJson.parse("{not json at all", decrypt))
    }

    @Test
    fun `a blank draft entry is not kept`() {
        val raw = CloudProfileBookJson.render(
            CloudProfileBook(drafts = mapOf("alist" to CloudProfile())),
            encrypt,
        )

        assertTrue(CloudProfileBookJson.parse(raw, decrypt).drafts.isEmpty())
    }
}
