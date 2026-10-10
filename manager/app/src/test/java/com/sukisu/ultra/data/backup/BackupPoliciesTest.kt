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
    fun `keep quota is clamped to the retention limits`() {
        // 偏好项是从磁盘读出来的：手改过、从旧版本升上来都可能越界。额度 0 会让下一次备份
        // 把刚写进去的那一份当场删掉，上限则是给磁盘一个说得清的边界。
        assertEquals(1, RetentionPolicy.keepFor(BackupKind.MODULE, moduleKeep = 0, bootKeep = 2))
        assertEquals(1, RetentionPolicy.keepFor(BackupKind.BOOT, moduleKeep = 5, bootKeep = -3))
        assertEquals(
            RetentionLimit.MAX,
            RetentionPolicy.keepFor(BackupKind.MODULE, moduleKeep = 999, bootKeep = 2),
        )
        // boot 上限更紧：单张镜像 32–96MB，20 份就是近 2GB。
        assertEquals(
            RetentionLimit.MAX_BOOT,
            RetentionPolicy.keepFor(BackupKind.BOOT, moduleKeep = 5, bootKeep = 999),
        )
    }

    @Test
    fun `retention limits clamp into range`() {
        assertEquals(1, RetentionLimit.clampModule(0))
        assertEquals(1, RetentionLimit.clampModule(-7))
        assertEquals(RetentionLimit.MAX, RetentionLimit.clampModule(1000))
        assertEquals(RetentionLimit.MAX_BOOT, RetentionLimit.clampBoot(1000))
        assertEquals(RetentionLimit.MAX_ROLLBACK, RetentionLimit.clampRollback(1000))
        assertEquals(5, RetentionLimit.clampModule(5))
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
                listOf(rollback("2026-10-01T00:00:00Z"), rollback("2026-10-02T00:00:00Z"), original),
                keep = 1,
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

        val expired = RollbackPolicy.expiredRollbacks(existing, keep = 1)

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
            RollbackPolicy.expiredRollbacks(existing, keep = 1).map { it.fileName },
        )
    }

    @Test
    fun `rollback quota follows the configured keep count`() {
        val rollbackOf = { createdAt: String ->
            BackupEntry(BackupKind.MODULE, "a", RollbackPolicy.PREFIX + createdAt, null, 1L, "s", createdAt)
        }
        val existing = (1..3).map { rollbackOf("2026-10-0${it}T00:00:00Z") }

        // 额度调到 3 就一份都不淘汰——保留几份是用户说了算，不是写死的 1。
        assertEquals(emptyList<String>(), RollbackPolicy.expiredRollbacks(existing, keep = 3).map { it.fileName })
        assertEquals(2, RollbackPolicy.expiredRollbacks(existing, keep = 1).size)
    }
}
