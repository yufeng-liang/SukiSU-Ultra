package com.sukisu.ultra.ui.screen.settings.backup

import com.sukisu.ultra.data.backup.BackupEntry
import com.sukisu.ultra.data.backup.BackupFailure
import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupRunResult
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

    @Test
    fun `row title prefers the human readable name from meta`() {
        val row = BackupListFormatter.rows(listOf(entry), mapOf("m.meta.json" to meta)).single()
        assertEquals("Zygisk Assistant", row.title)
        assertTrue(row.subtitle.contains("2.1.3"))
        assertTrue(row.subtitle.contains("1.7 MB"))
    }

    @Test
    fun `row falls back to the entry id when meta is missing`() {
        val row = BackupListFormatter.rows(listOf(entry), emptyMap()).single()
        assertEquals("zygisk-assistant", row.title)
    }

    @Test
    fun `human size formats across units`() {
        assertEquals("512 B", BackupListFormatter.humanSize(512))
        assertEquals("1.5 KB", BackupListFormatter.humanSize(1536))
        assertEquals("1.7 MB", BackupListFormatter.humanSize(1834021))
    }

    @Test
    fun `summary reports written and skipped counts`() {
        val result = BackupRunResult(BackupKind.MODULE, written = listOf("a", "b"), skipped = listOf("c"))
        assertTrue(BackupListFormatter.summary(result).contains("2"))
        assertTrue(BackupListFormatter.summary(result).contains("1"))
    }

    @Test
    fun `summary surfaces failures`() {
        val result = BackupRunResult(BackupKind.MODULE, failures = listOf(BackupFailure("put", "a", "local", "disk full")))
        assertTrue(BackupListFormatter.summary(result).contains("disk full"))
    }

    @Test
    fun `rollback entries are flagged so the ui can label them`() {
        val rollback = entry.copy(fileName = "pre_restore_20261008_130000_${entry.fileName}")
        val rows = BackupListFormatter.rows(listOf(entry, rollback), emptyMap())
        assertEquals(listOf(false, true), rows.map { it.isRollback })
    }

    @Test
    fun `app password hint only fires when the server rejected the credentials`() {
        val unauthorized = BackupRunResult(
            BackupKind.MODULE,
            failures = listOf(BackupFailure("put", "a", "cloud", "HTTP 401", authFailed = true)),
        )
        val diskFull = BackupRunResult(
            BackupKind.MODULE,
            failures = listOf(BackupFailure("put", "a", "cloud", "disk full")),
        )
        assertTrue(BackupListFormatter.needsAppPasswordHint(unauthorized))
        assertFalse(BackupListFormatter.needsAppPasswordHint(diskFull))
    }

    @Test
    fun `summary joins its parts with the separator used for de-duplication`() {
        val result = BackupRunResult(BackupKind.MODULE, written = listOf("a"), skipped = listOf("b"))
        assertEquals(
            listOf("written 1", "skipped 1"),
            BackupListFormatter.summary(result).split(BackupListFormatter.SEPARATOR),
        )
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
        // summary() 把提示放在前面，随后失败的列表读取会给出同一句话——不能出现两次。
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
