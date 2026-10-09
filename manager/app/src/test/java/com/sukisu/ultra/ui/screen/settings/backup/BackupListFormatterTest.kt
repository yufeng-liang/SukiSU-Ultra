package com.sukisu.ultra.ui.screen.settings.backup

import com.sukisu.ultra.data.backup.BackupEntry
import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.ModuleBackupMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupListFormatterTest {

    private val entry = BackupEntry(
        BackupKind.MODULE, "zygisk-assistant", "module_zygisk-assistant_12_20261008_120000.zip",
        "m.meta.json", 1834021L, "sha", "2026-10-08T12:00:00Z"
    )

    private val meta = ModuleBackupMeta(
        "zygisk-assistant", "Zygisk Assistant", "2.1.3", 12L, "snake-4", false, false, "dev", "2026-10-08T12:00:00Z"
    )

    /** boot 的 sha1（40 位）——行标题只该显示前 12 位，与归档文件名里的前缀一致。 */
    private val bootSha1 = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678"

    private val bootEntry = BackupEntry(
        BackupKind.BOOT, bootSha1, "boot_a1b2c3d4e5f6_20261008_120000.img",
        "boot_a1b2c3d4e5f6_20261008_120000.img.meta.json", 86_400_000L, "sha",
        "2026-10-08T12:00:00Z"
    )

    /**
     * 文案与时间格式化由 ViewModel 提供（这一层是纯 JVM 的，拿不到 Context），所以测试里给固定值：
     * 这里要盯的是"哪段文案被用在哪"，不是翻译本身。
     */
    private val labels = BackupRowLabels(
        bootTitle = "Stock image",
        disabled = "Disabled",
        formatTime = { iso -> "T($iso)" },
    )

    @Test
    fun `row title prefers the human readable name from meta`() {
        val row = BackupListFormatter.rows(listOf(entry), mapOf("m.meta.json" to meta), labels).single()
        assertEquals("Zygisk Assistant", row.title)
        assertTrue(row.subtitle.contains("2.1.3"))
        assertTrue(row.subtitle.contains("1.7 MB"))
    }

    @Test
    fun `row falls back to the entry id when meta is missing`() {
        val row = BackupListFormatter.rows(listOf(entry), emptyMap(), labels).single()
        assertEquals("zygisk-assistant", row.title)
    }

    @Test
    fun `row time goes through the formatter instead of the raw index value`() {
        val row = BackupListFormatter.rows(listOf(entry), emptyMap(), labels).single()
        assertTrue(row.subtitle, row.subtitle.contains("T(2026-10-08T12:00:00Z)"))
    }

    @Test
    fun `a disabled module is labelled in the caller's language`() {
        val disabled = meta.copy(disabled = true)
        val row = BackupListFormatter.rows(listOf(entry), mapOf("m.meta.json" to disabled), labels).single()
        assertTrue(row.subtitle, row.subtitle.contains("Disabled"))
    }

    @Test
    fun `boot rows are named as stock images rather than showing a raw sha1`() {
        val row = BackupListFormatter.rows(listOf(bootEntry), emptyMap(), labels).single()
        // 40 位 sha1 摆在标题上没人看得懂；12 位前缀与归档文件名一致，用户能对上。
        assertEquals("Stock image a1b2c3d4e5f6", row.title)
        assertFalse(row.title.contains(bootSha1))
        assertTrue(row.subtitle, row.subtitle.contains("82.4 MB"))
    }

    @Test
    fun `human size formats across units`() {
        assertEquals("512 B", BackupListFormatter.humanSize(512))
        assertEquals("1.5 KB", BackupListFormatter.humanSize(1536))
        assertEquals("1.7 MB", BackupListFormatter.humanSize(1834021))
    }

    @Test
    fun `rollback entries are flagged so the ui can label them`() {
        val rollback = entry.copy(fileName = "pre_restore_20261008_130000_${entry.fileName}")
        val rows = BackupListFormatter.rows(listOf(entry, rollback), emptyMap(), labels)
        assertEquals(listOf(false, true), rows.map { it.isRollback })
    }

    @Test
    fun `merging keeps the operation result and the list failure`() {
        assertEquals(
            "written 0${SEP}skipped 0${SEP}list is unreadable",
            BackupListFormatter.mergeMessages("written 0${SEP}skipped 0", "list is unreadable"),
        )
    }

    @Test
    fun `merging drops a segment that is already present`() {
        val hint = "use an app password"
        // 同一句话可能被两个来源各说一遍——不能出现两次。
        assertEquals(
            "use an app password${SEP}written 0",
            BackupListFormatter.mergeMessages("use an app password${SEP}written 0", hint),
        )
    }

    @Test
    fun `merging with no existing message yields the incoming text`() {
        assertEquals("cloud is not configured", BackupListFormatter.mergeMessages(null, "cloud is not configured"))
    }

    private companion object {
        val SEP = BackupListFormatter.SEPARATOR
    }
}
