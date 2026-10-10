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
    fun `retention never expires the run that was just written`() {
        // 设备时钟被往回调过：刚写下的这一代 createdAt 比旧备份还早，按时间排序它是"最老"的，
        // 于是"备份成功"的那一刻它就被自己删掉了。keepEntries 说的是"这些是刚写进去的"，
        // 不依赖时钟也不依赖排序。
        val existing = listOf(
            entry("fresh", "9", "2026-09-01T00:00:00Z"),
            entry("older", "8", "2026-10-01T00:00:00Z"),
            entry("newest", "7", "2026-10-08T00:00:00Z"),
        )

        val expired = RetentionPolicy.expired(
            existing = existing,
            kind = BackupKind.MODULE,
            keep = 2,
            keepEntries = setOf("module_fresh_1_2026-09-01T00:00:00Z.zip"),
        ).map { it.entryId }

        // 受保护的一代留下，名额内最新的那一代也留下，走的是中间那份。
        assertEquals(listOf("older"), expired)
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
        assertEquals(5, RetentionLimit.clampModule(5))
    }

    @Test
    fun `the retention for a run is read at that moment, not cached`() {
        // 引擎曾经是 `by lazy` 的，连当时的额度一起被缓存：用户在设置页把保留份数从 5 改成 2，
        // 本次会话里下一次备份还是按 5 裁——看着改了不生效。额度必须每次现读。
        var moduleKeep = 2
        var bootKeep = 1
        assertEquals(2 to 1, backupRetentionNow({ moduleKeep }, { bootKeep }))

        moduleKeep = 7
        bootKeep = 3

        assertEquals(7 to 3, backupRetentionNow({ moduleKeep }, { bootKeep }))
    }

}
