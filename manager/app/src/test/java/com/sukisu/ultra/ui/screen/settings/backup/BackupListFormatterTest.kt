package com.sukisu.ultra.ui.screen.settings.backup

import com.sukisu.ultra.data.backup.BackupEntry
import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin
import com.sukisu.ultra.data.backup.ModuleBackupMeta
import com.sukisu.ultra.data.backup.OriginEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    /** 分区快照：entryId 就是分区名，恢复时按它决定刷哪儿。 */
    private val partitionEntry = BackupEntry(
        BackupKind.PARTITION, "boot_a", "partition_boot_a_20261008_120000.img.gz",
        "partition_boot_a_20261008_120000.img.gz.meta.json", 86_400_000L, "sha",
        "2026-10-08T12:00:00Z"
    )

    /**
     * 文案与时间格式化由 ViewModel 提供（这一层是纯 JVM 的，拿不到 Context），所以测试里给固定值：
     * 这里要盯的是"哪段文案被用在哪"，不是翻译本身。
     */
    private val labels = BackupRowLabels(
        partitionTitle = "Partition snapshot",
        compressed = "compressed",
        uncompressed = "uncompressed",
        repoConfigTitle = "Repo sources",
        disabled = "Disabled",
        originLabel = { origin -> if (origin == BackupOrigin.CLOUD) "Cloud" else "This device" },
    )

    private fun rowsOf(
        vararg entries: BackupEntry,
        origin: BackupOrigin = BackupOrigin.LOCAL,
        metas: Map<String, ModuleBackupMeta> = emptyMap(),
    ): List<BackupRow> = BackupListFormatter.rows(
        entries.map { OriginEntry(origin, it) },
        metas,
        labels,
    )

    @Test
    fun `row title prefers the human readable name from meta`() {
        val row = rowsOf(entry, metas = mapOf("m.meta.json" to meta)).single()
        assertEquals("Zygisk Assistant", row.title)
        assertTrue(row.subtitle.contains("2.1.3"))
        assertTrue(row.subtitle.contains("1.7 MB"))
    }

    @Test
    fun `row falls back to the entry id when meta is missing`() {
        assertEquals("zygisk-assistant", rowsOf(entry).single().title)
    }

    /**
     * 行里不带时间：条目按"哪一次备份"分组，同一组里每行的时间都一样，时刻已经写在组标题上。
     * 但行要留着原始时刻——分组就是靠它算标题和排序的。
     */
    @Test
    fun `rows keep the raw timestamp for grouping but do not print it`() {
        val row = rowsOf(entry).single()
        assertEquals("2026-10-08T12:00:00Z", row.createdAt)
        assertFalse(row.subtitle, row.subtitle.contains("2026-10-08"))
    }

    @Test
    fun `a disabled module is labelled in the caller's language`() {
        val row = rowsOf(entry, metas = mapOf("m.meta.json" to meta.copy(disabled = true))).single()
        assertTrue(row.subtitle, row.subtitle.contains("Disabled"))
    }

    @Test
    fun `partition rows are named after the partition and say whether they are compressed`() {
        val row = rowsOf(partitionEntry).single()
        // 分区名就是这一行的身份：刷回哪一块全靠它，不能藏进详情页。
        assertEquals("Partition snapshot boot_a", row.title)
        assertTrue(row.subtitle, row.subtitle.contains("82.4 MB"))
        assertTrue(row.subtitle, row.subtitle.contains("compressed"))
    }

    @Test
    fun `an uncompressed partition row says uncompressed instead of compressed`() {
        val plain = partitionEntry.copy(fileName = "partition_boot_a_20261008_120000.img")
        val row = rowsOf(plain).single()
        // 断言完整的那一段而不是 contains("compressed")："uncompressed" 是 "compressed" 的超串，
        // 用 contains 两种情况下都为真，分不出压缩与否。
        assertTrue(row.subtitle, row.subtitle.contains(BackupListFormatter.SEPARATOR + "uncompressed"))
        assertFalse(row.subtitle, row.subtitle.contains(BackupListFormatter.SEPARATOR + "compressed"))
    }

    @Test
    fun `human size formats across units`() {
        assertEquals("512 B", BackupListFormatter.humanSize(512))
        assertEquals("1.5 KB", BackupListFormatter.humanSize(1536))
        assertEquals("1.7 MB", BackupListFormatter.humanSize(1834021))
    }

    /** 恢复分区快照会往块设备写镜像，界面靠行自己的类型决定要不要弹确认框。 */
    @Test
    fun `each row carries the kind it was built from`() {
        assertEquals(
            listOf(BackupKind.MODULE, BackupKind.PARTITION),
            rowsOf(entry, partitionEntry).map { it.kind },
        )
    }

    @Test
    fun `each row carries the origin it was listed from`() {
        assertEquals(BackupOrigin.CLOUD, rowsOf(entry, origin = BackupOrigin.CLOUD).single().origin)
        assertEquals("Cloud", rowsOf(entry, origin = BackupOrigin.CLOUD).single().originLabel)
    }

    /**
     * 两侧合起来列时，同一个模块在本地和云端各有一条、文件名一模一样。行的 id 不带来源的话
     * LazyColumn 会因为重复键直接崩。
     */
    @Test
    fun `the same file name in two origins yields two distinct row ids`() {
        val rows = BackupListFormatter.rows(
            listOf(OriginEntry(BackupOrigin.LOCAL, entry), OriginEntry(BackupOrigin.CLOUD, entry)),
            emptyMap(),
            labels,
        )
        assertEquals(2, rows.size)
        assertNotEquals(rows[0].id, rows[1].id)
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

    private fun resultRow(origin: BackupOrigin, kind: BackupKind) =
        BackupResultRow(origin = origin, kind = kind, outcome = "")

    @Test
    fun `the result table lists the local side first whatever the input order was`() {
        // 结果表的表头（本地 / 云端）是按分组顺序写的，而分组顺序要是跟着输入走，同一张表
        // 两次跑出来的排法可能相反。本机在前是这一页其余地方一致的排法（列表、位置选择）。
        val groups = resultGroups(
            listOf(
                resultRow(BackupOrigin.CLOUD, BackupKind.MODULE),
                resultRow(BackupOrigin.LOCAL, BackupKind.MODULE),
                resultRow(BackupOrigin.CLOUD, BackupKind.REPO_CONFIG),
                resultRow(BackupOrigin.LOCAL, BackupKind.REPO_CONFIG),
            ),
        )

        assertEquals(2, groups.size)
        assertEquals(listOf(BackupOrigin.LOCAL, BackupOrigin.CLOUD), groups.map { it.first().origin })
        // 同一侧里原本的顺序不动：内容是用户勾的顺序。
        assertEquals(listOf(BackupKind.MODULE, BackupKind.REPO_CONFIG), groups.first().map { it.kind })
    }

    @Test
    fun `a run that only wrote one side is one group`() {
        val groups = resultGroups(
            listOf(
                resultRow(BackupOrigin.LOCAL, BackupKind.MODULE),
                resultRow(BackupOrigin.LOCAL, BackupKind.REPO_CONFIG),
            ),
        )

        assertEquals(1, groups.size)
        assertEquals(2, groups.single().size)
    }

    /**
     * 换机型恢复必须提醒：模块备份里是**旧机器解包出来的文件**（含当时选定的架构变体、
     * 安装脚本写进去的机型配置），而恢复不会再跑一遍安装脚本。
     */
    @Test
    fun `a backup packed on another device is reported by name`() {
        val rows = rowsOf(entry, metas = mapOf("m.meta.json" to meta.copy(sourceDevice = "Xiaomi 14")))

        assertEquals(listOf("Xiaomi 14"), restoreSourceDevices(rows, "Redmi K60 Ultra"))
    }

    @Test
    fun `the same device or an unknown one raises nothing`() {
        val same = rowsOf(entry, metas = mapOf("m.meta.json" to meta.copy(sourceDevice = "Redmi K60 Ultra")))
        assertTrue(restoreSourceDevices(same, "Redmi K60 Ultra").isEmpty())

        // 旧备份没有这个字段（空串），或者 Build.MODEL 取不到（unknown / 空）：两边都无从判断。
        // 拿不可靠的名字去弹窗，用户下次就会习惯性点「继续」，闸门等于没有。
        val legacy = rowsOf(entry, metas = mapOf("m.meta.json" to meta.copy(sourceDevice = "")))
        assertTrue(restoreSourceDevices(legacy, "Redmi K60 Ultra").isEmpty())
        assertTrue(restoreSourceDevices(same, "unknown").isEmpty())
        assertTrue(restoreSourceDevices(same, "").isEmpty())
    }

    @Test
    fun `partition rows are not part of the device warning`() {
        // 分区快照自己还有大小与 KMI 两道硬闸；这里既不该重复报，也不能因为分区行没有
        // sourceDevice 就把同一批里的模块行一起漏掉。
        val rows = rowsOf(
            partitionEntry,
            entry,
            metas = mapOf("m.meta.json" to meta.copy(sourceDevice = "Xiaomi 14")),
        )

        assertEquals(listOf("Xiaomi 14"), restoreSourceDevices(rows, "Redmi K60 Ultra"))
    }

    private companion object {
        val SEP = BackupListFormatter.SEPARATOR
    }
}
