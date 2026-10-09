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
