package com.sukisu.ultra.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun entry(id: String, sha: String, createdAt: String, kind: BackupKind = BackupKind.MODULE) =
    BackupEntry(kind, id, "module_${id}_1_$createdAt.zip", null, 1L, sha, createdAt)

class BackupPoliciesTest {

    @Test
    fun `duplicate is found by sha within the same kind`() {
        val existing = listOf(entry("a", "aaa", "2026-10-08T10:00:00Z"))
        assertEquals("a", DuplicatePolicy.findDuplicate(existing, BackupKind.MODULE, "aaa")?.entryId)
    }

    @Test
    fun `same sha in another kind is not a duplicate`() {
        val existing = listOf(entry("b", "aaa", "2026-10-08T10:00:00Z", BackupKind.BOOT))
        assertNull(DuplicatePolicy.findDuplicate(existing, BackupKind.MODULE, "aaa"))
    }

    @Test
    fun `retention keeps the newest entries and expires the rest`() {
        val existing = listOf(
            entry("old", "1", "2026-10-01T00:00:00Z"),
            entry("mid", "2", "2026-10-05T00:00:00Z"),
            entry("new", "3", "2026-10-08T00:00:00Z"),
        )
        assertEquals(listOf("old"), RetentionPolicy.expired(existing, BackupKind.MODULE, keep = 2).map { it.entryId })
    }

    @Test
    fun `retention with non positive keep expires everything`() {
        val existing = listOf(entry("a", "1", "2026-10-01T00:00:00Z"))
        assertEquals(listOf("a"), RetentionPolicy.expired(existing, BackupKind.MODULE, keep = 0).map { it.entryId })
    }

    @Test
    fun `retention counts backups, not files`() {
        // 真机上踩到的坑：一次备份 11 个模块写出 11 个归档，按"文件数"算额度会当场把刚写好的
        // 6 个删掉（额度 5）——用户看到"已写入 11 项"，列表里只剩 5 项。额度说的是"留几次备份"。
        val session = "20261008_120000"
        val oneRun = (1..11).map { i ->
            BackupEntry(
                BackupKind.MODULE,
                "m$i",
                "module_m${i}_1_$session.zip",
                null,
                1L,
                "sha$i",
                "2026-10-08T12:00:00Z",
            )
        }

        assertEquals(emptyList<String>(), RetentionPolicy.expired(oneRun, BackupKind.MODULE, keep = 5).map { it.entryId })
    }

    @Test
    fun `retention drops the oldest whole sessions`() {
        val existing = listOf(
            entry("a1", "1", "20261001_010101"),
            entry("a2", "2", "20261001_010101"),
            entry("b1", "3", "20261002_020202"),
            entry("c1", "4", "20261003_030303"),
        )

        // keep = 2 → 留最近两次（10-02、10-03）；10-01 那两个一起走，不能只删其中一个。
        assertEquals(
            listOf("a1", "a2"),
            RetentionPolicy.expired(existing, BackupKind.MODULE, keep = 2).map { it.entryId },
        )
    }

    @Test
    fun `retention ignores other kinds`() {
        val existing = listOf(
            entry("m1", "1", "2026-10-01T00:00:00Z"),
            entry("m2", "2", "2026-10-02T00:00:00Z"),
            entry("b1", "3", "2026-10-01T00:00:00Z", BackupKind.BOOT),
        )
        assertEquals(
            emptyList<String>(),
            RetentionPolicy.expired(existing, BackupKind.MODULE, keep = 2).map { it.entryId }
        )
    }

    @Test
    fun `keep quota differs per kind`() {
        assertEquals(5, RetentionPolicy.keepFor(BackupKind.MODULE, moduleKeep = 5, bootKeep = 2))
        assertEquals(2, RetentionPolicy.keepFor(BackupKind.BOOT, moduleKeep = 5, bootKeep = 2))
    }

    @Test
    fun `rollback points are named, detected and capped at one`() {
        val original = entry("a", "1", "2026-10-08T12:00:00Z")
        val name = RollbackPolicy.rollbackNameFor(original, "20261008_120000")
        assertEquals("pre_restore_20261008_120000_module_a_1_2026-10-08T12:00:00Z.zip", name)

        val rollback = { createdAt: String ->
            BackupEntry(BackupKind.MODULE, "a", RollbackPolicy.PREFIX + createdAt, null, 1L, "s", createdAt)
        }
        assertTrue(RollbackPolicy.isRollback(rollback("2026-10-01T00:00:00Z")))
        assertFalse(RollbackPolicy.isRollback(original))
        assertEquals(
            listOf("2026-10-01T00:00:00Z"),
            RollbackPolicy.expiredRollbacks(
                listOf(rollback("2026-10-01T00:00:00Z"), rollback("2026-10-02T00:00:00Z"), original)
            ).map { it.createdAt }
        )
        // 回滚点不吃业务额度
        assertEquals(
            emptyList<String>(),
            RetentionPolicy.expired(listOf(rollback("2026-10-01T00:00:00Z")), BackupKind.MODULE, keep = 1)
                .map { it.entryId }
        )
    }

    @Test
    fun `rollback retention is per item, not global`() {
        val rollbackOf = { id: String, createdAt: String ->
            BackupEntry(BackupKind.MODULE, id, RollbackPolicy.PREFIX + createdAt + "_" + id, null, 1L, "s", createdAt)
        }
        val existing = listOf(
            rollbackOf("a", "2026-10-01T00:00:00Z"),
            rollbackOf("a", "2026-10-02T00:00:00Z"),
            rollbackOf("b", "2026-10-01T00:00:00Z"),
        )

        val expired = RollbackPolicy.expiredRollbacks(existing)

        // 只该淘汰 a 的旧那一份：恢复模块 b 不能把 a 的安全网一起删掉。
        assertEquals(listOf("2026-10-01T00:00:00Z_a"), expired.map { it.fileName.removePrefix(RollbackPolicy.PREFIX) })
    }

    @Test
    fun `rollback retention keeps the newest per item and both kinds apart`() {
        val existing = listOf(
            BackupEntry(BackupKind.MODULE, "a", RollbackPolicy.PREFIX + "old_a", null, 1L, "s", "2026-10-01T00:00:00Z"),
            BackupEntry(BackupKind.MODULE, "a", RollbackPolicy.PREFIX + "new_a", null, 1L, "s", "2026-10-02T00:00:00Z"),
            BackupEntry(BackupKind.BOOT, "a", RollbackPolicy.PREFIX + "boot_a", null, 1L, "s", "2026-10-01T00:00:00Z"),
        )

        // 只有 MODULE/a 的旧那一份该过期；BOOT/a 是另一项，不动。
        assertEquals(
            listOf(RollbackPolicy.PREFIX + "old_a"),
            RollbackPolicy.expiredRollbacks(existing).map { it.fileName },
        )
    }
}
