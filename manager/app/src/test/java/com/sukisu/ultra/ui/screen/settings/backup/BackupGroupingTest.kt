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

    /**
     * 同一次备份写到两侧仍是**一行**：那是同一份东西的两个副本，不是两次备份。卡片上用两块来源
     * 牌子说明写到哪儿；每一条条目自己带着来源，恢复/删除照旧动对那一侧。
     */
    @Test
    fun `the same session on two origins is one group`() {
        val groups = BackupGrouping.group(
            listOf(
                row("module_a_1_20261009_093107.zip", "2026-10-09T09:31:07Z"),
                row("module_a_1_20261009_093107.zip", "2026-10-09T09:31:07Z", origin = BackupOrigin.CLOUD),
            ),
            label,
        )
        assertEquals(1, groups.size)
        assertEquals(listOf(BackupOrigin.LOCAL, BackupOrigin.CLOUD), groups.single().origins)
        assertEquals(2, groups.single().rows.size)
    }

    /** 列表是纯按时间倒序的一段：来源不再分段，两侧合起来排。 */
    @Test
    fun `groups are ordered by recency across origins`() {
        val groups = BackupGrouping.group(
            listOf(
                row("module_a_1_20261009_093107.zip", "2026-10-09T09:31:07Z", origin = BackupOrigin.CLOUD),
                row("module_b_1_20261008_120000.zip", "2026-10-08T12:00:00Z", origin = BackupOrigin.LOCAL),
            ),
            label,
        )
        assertEquals(
            listOf("T(2026-10-09T09:31:07Z)", "T(2026-10-08T12:00:00Z)"),
            groups.map { it.label },
        )
    }

    /**
     * 一次备份里勾了模块和分区快照，那也是**一行**：列表要回答的是"我最近备了什么"，不是"哪几类
     * 内容各占一行"。恢复后果不同由勾选项判定（分区快照的确认弹窗按 kind 触发），不靠分组区分。
     */
    @Test
    fun `modules and partitions of one session are one group`() {
        val groups = BackupGrouping.group(
            listOf(
                row("module_a_1_20261009_093107.zip", "2026-10-09T09:31:07Z"),
                row("partition_boot_a_20261009_093107.img", "2026-10-09T09:31:07Z", kind = BackupKind.PARTITION),
            ),
            label,
        )
        assertEquals(1, groups.size)
        assertEquals(listOf(BackupKind.MODULE, BackupKind.PARTITION), groups.single().kinds)
        assertEquals(2, groups.single().rows.size)
    }

    /** 类别在卡片上的顺序固定按 ordinal，不跟着归档在列表里的先后走。 */
    @Test
    fun `kinds are listed in a fixed order regardless of arrival`() {
        val groups = BackupGrouping.group(
            listOf(
                row("repo_config_20261009_093107.json", "2026-10-09T09:31:07Z", kind = BackupKind.REPO_CONFIG),
                row("partition_boot_a_20261009_093107.img", "2026-10-09T09:31:07Z", kind = BackupKind.PARTITION),
                row("module_a_1_20261009_093107.zip", "2026-10-09T09:31:07Z"),
            ),
            label,
        )
        assertEquals(1, groups.size)
        assertEquals(
            listOf(BackupKind.MODULE, BackupKind.PARTITION, BackupKind.REPO_CONFIG),
            groups.single().kinds,
        )
    }
}
