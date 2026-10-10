package com.sukisu.ultra.ui.screen.settings.backup

import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupGroupingTest {

    private fun row(
        fileName: String,
        createdAt: String,
        origin: BackupOrigin = BackupOrigin.LOCAL,
        kind: BackupKind = BackupKind.MODULE,
        title: String = fileName,
    ) = BackupRow(
        id = "${origin.name}/$fileName",
        title = title,
        subtitle = "1.7 MB",
        fileName = fileName,
        origin = origin,
        originLabel = "This device",
        kind = kind,
        createdAt = createdAt,
    )

    /** 标题就是时刻本身，测试里直接回显，方便断言"这一组取的是哪一刻"。 */
    private val label: (String) -> String = { iso -> "T($iso)" }

    @Test
    fun `the session id is the timestamp embedded in the archive name`() {
        assertEquals("20261009_093107", BackupGrouping.sessionOf("module_zygisk-assistant_12_20261009_093107.zip"))
        assertEquals("20261009_093426", BackupGrouping.sessionOf("pre_restore_20261009_093426_module_x_1_20261009_093107.zip"))
        assertEquals("20261009_093107", BackupGrouping.sessionOf("boot_a1b2c3d4e5f6_20261009_093107.img"))
    }

    @Test
    fun `a name without a timestamp has no session`() {
        assertNull(BackupGrouping.sessionOf("module_weird.zip"))
    }

    /**
     * 一次备份的 11 个归档共用同一个时间戳，必须并成一组——按索引里的 createdAt 分堆会把它们
     * 拆成 11 组，因为那是每个文件各自取的时刻。
     */
    @Test
    fun `archives of one session become one group even when their timestamps differ by milliseconds`() {
        val groups = BackupGrouping.group(
            listOf(
                row("module_a_1_20261009_093107.zip", "2026-10-09T09:31:07.100Z"),
                row("module_b_2_20261009_093107.zip", "2026-10-09T09:31:07.250Z"),
            ),
            label,
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().rows.size)
        // 组标题取最新那一刻，不是"第一条恰好是什么"。
        assertEquals("T(2026-10-09T09:31:07.250Z)", groups.single().label)
    }

    @Test
    fun `two sessions are two groups and the newest comes first`() {
        val groups = BackupGrouping.group(
            listOf(
                row("module_a_1_20261008_120000.zip", "2026-10-08T12:00:00Z"),
                row("module_a_1_20261009_093107.zip", "2026-10-09T09:31:07Z"),
            ),
            label,
        )
        assertEquals(2, groups.size)
        assertEquals("T(2026-10-09T09:31:07Z)", groups.first().label)
        assertEquals("T(2026-10-08T12:00:00Z)", groups.last().label)
    }

    /** 同一时刻在整秒时没有小数部分，字典序会把 ".100Z" 排在 "Z" 前面——排序得按时刻来。 */
    @Test
    fun `groups are ordered by instant, not by string comparison`() {
        val groups = BackupGrouping.group(
            listOf(
                row("module_a_1_20261008_120000.zip", "2026-10-09T09:31:07.100Z"),
                row("module_b_1_20261008_120001.zip", "2026-10-09T09:31:08Z"),
            ),
            label,
        )
        assertEquals("T(2026-10-09T09:31:08Z)", groups.first().label)
    }

    /** 两侧各有一份同名归档时是两组：来源不同就是两次不同的备份。 */
    @Test
    fun `the same session on two origins stays two groups`() {
        val groups = BackupGrouping.group(
            listOf(
                row("module_a_1_20261009_093107.zip", "2026-10-09T09:31:07Z"),
                row("module_a_1_20261009_093107.zip", "2026-10-09T09:31:07Z", origin = BackupOrigin.CLOUD),
            ),
            label,
        )
        assertEquals(2, groups.size)
        assertEquals(setOf(BackupOrigin.LOCAL, BackupOrigin.CLOUD), groups.map { it.origin }.toSet())
        assertEquals(2, groups.map { it.id }.toSet().size)
        // 本地在前：界面按这个顺序分段，云端段在下面。
        assertEquals(listOf(BackupOrigin.LOCAL, BackupOrigin.CLOUD), groups.map { it.origin })
    }

    /** 分组顺序是"先来源、再时间倒序"，界面就是靠它切段的。 */
    @Test
    fun `origins come before recency in the group order`() {
        val groups = BackupGrouping.group(
            listOf(
                row("module_a_1_20261009_093107.zip", "2026-10-09T09:31:07Z", origin = BackupOrigin.CLOUD),
                row("module_b_1_20261008_120000.zip", "2026-10-08T12:00:00Z", origin = BackupOrigin.LOCAL),
            ),
            label,
        )
        assertEquals(listOf(BackupOrigin.LOCAL, BackupOrigin.CLOUD), groups.map { it.origin })
    }

    /** 一次备份里模块和 boot 是两条结果，各成一组：恢复它们的后果完全不同。 */
    @Test
    fun `modules and boot are separate groups even in the same session`() {
        val groups = BackupGrouping.group(
            listOf(
                row("module_a_1_20261009_093107.zip", "2026-10-09T09:31:07Z"),
                row("boot_a1b2c3d4e5f6_20261009_093107.img", "2026-10-09T09:31:07Z", kind = BackupKind.BOOT),
            ),
            label,
        )
        assertEquals(2, groups.size)
        assertEquals(setOf(BackupKind.MODULE, BackupKind.BOOT), groups.map { it.kind }.toSet())
    }
}
