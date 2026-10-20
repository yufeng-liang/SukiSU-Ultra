package com.sukisu.ultra.ui.screen.settings.backup

import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多选模式那几条判定。
 *
 * 删除是"整份抹掉"，所以这里锁住的是"勾中的到底是哪几份、一共几条"：勾选存的是 id，而列表
 * 每刷新一次就换一批分组对象，一旦两者对不上（云端那份在别处被删、旧的一份被保留策略淘汰），
 * 顶部那句"已选 N 份"就会和屏幕上勾中的行数不一致——而用户正是照着这句话按下删除的。
 */
class BackupSelectionTest {

    private fun row(id: String, origin: BackupOrigin = BackupOrigin.LOCAL) = BackupRow(
        id = id,
        title = id,
        subtitle = "",
        fileName = "$id.zip",
        origin = origin,
        originLabel = "",
        kind = BackupKind.MODULE,
        createdAt = "2026-10-09T09:35:56Z",
    )

    private fun group(id: String, origin: BackupOrigin = BackupOrigin.LOCAL, rows: Int = 1) =
        BackupGroup(
            id = id,
            origins = listOf(origin),
            kinds = listOf(BackupKind.MODULE),
            session = id,
            label = id,
            rows = (1..rows).map { row("$id-$it", origin) },
        )

    @Test
    fun `selected groups come back in list order`() {
        val first = group("local/a")
        val second = group("local/b")
        val state = BackupUiState(
            groups = listOf(first, second),
            selectedGroupIds = setOf(second.id, first.id),
        )

        assertEquals(listOf(first.id, second.id), state.selectedGroups.map { it.id })
    }

    @Test
    fun `the count adds up the entries of every selected group`() {
        // 弹窗要说"3 份、共 5 项"：只说份数的话，用户没法判断自己是不是多勾了一份。
        val state = BackupUiState(
            groups = listOf(group("local/a", rows = 2), group("local/b", rows = 3), group("local/c", rows = 7)),
            selectedGroupIds = setOf("local/a", "local/b"),
        )

        assertEquals(2, state.selectedGroups.size)
        assertEquals(5, state.selectedEntryCount)
    }

    @Test
    fun `an id that is no longer listed counts for nothing`() {
        val state = BackupUiState(
            groups = listOf(group("local/a")),
            selectedGroupIds = setOf("local/a", "cloud/gone"),
        )

        assertEquals(listOf("local/a"), state.selectedGroups.map { it.id })
        assertEquals(1, state.selectedEntryCount)
    }

    @Test
    fun `refreshing drops ids the list no longer has`() {
        // 云端那一份在别处被删掉之后刷新：勾选里只剩还在列表里的那个，不是"已选 2 份"。
        val kept = group("local/a")
        val pruned = pruneSelection(setOf(kept.id, "cloud/gone"), listOf(kept))

        assertEquals(setOf(kept.id), pruned)
    }

    @Test
    fun `a selection with nothing left takes the mode with it`() {
        // 一个都不剩就没有"多选"这回事了：多选状态由勾选本身决定，界面据此退出，而不是留一条
        // "已选 0 份"的操作栏。
        val pruned = pruneSelection(setOf("cloud/gone"), listOf(group("local/a")))
        val state = BackupUiState(groups = listOf(group("local/a")), selectedGroupIds = pruned)

        assertTrue(pruned.isEmpty())
        assertFalse(state.selecting)
    }

    @Test
    fun `a group that is checked puts the list in selection mode`() {
        val state = BackupUiState(groups = listOf(group("local/a")), selectedGroupIds = setOf("local/a"))

        assertTrue(state.selecting)
    }

    private fun rowOf(
        fileName: String,
        origin: BackupOrigin,
        kind: BackupKind = BackupKind.MODULE,
    ) = BackupRow(
        id = "${origin.name}/$fileName",
        title = fileName,
        subtitle = "",
        fileName = fileName,
        origin = origin,
        originLabel = origin.name,
        kind = kind,
        createdAt = "2026-10-09T09:35:56Z",
    )

    /**
     * 同一份内容本机与云端各一条时只留一条，且留下的是本机那份。
     *
     * 恢复是逐条做的：默认全选两条就会把同一个模块装两遍、把同一张镜像刷两遍，而"已恢复 2 项"
     * 让用户以为动了两个东西。输入故意把云端放前面，证明留哪一条是按来源选的、不是按顺序。
     */
    @Test
    fun `both sides of the same content collapse into the local copy`() {
        val kept = oneCopyPerContent(
            listOf(
                rowOf("module_a_1_20261009_093107.zip", BackupOrigin.CLOUD),
                rowOf("module_a_1_20261009_093107.zip", BackupOrigin.LOCAL),
            ),
        )

        assertEquals(listOf(BackupOrigin.LOCAL), kept.map { it.origin })
    }

    /** 只写了一侧的内容原样留着——去重不该动它。 */
    @Test
    fun `content that only exists on one side is left alone`() {
        val rows = listOf(
            rowOf("module_a_1_20261009_093107.zip", BackupOrigin.LOCAL),
            rowOf("module_b_1_20261009_093107.zip", BackupOrigin.LOCAL),
        )

        assertEquals(rows, oneCopyPerContent(rows))
    }

    /** 两条不同的内容各自两侧都有：去重后是两条，不是一条、也不是四条。 */
    @Test
    fun `each distinct content keeps exactly one row`() {
        val kept = oneCopyPerContent(
            listOf(
                rowOf("module_a_1_20261009_093107.zip", BackupOrigin.LOCAL),
                rowOf("module_a_1_20261009_093107.zip", BackupOrigin.CLOUD),
                rowOf("module_b_1_20261009_093107.zip", BackupOrigin.LOCAL),
                rowOf("module_b_1_20261009_093107.zip", BackupOrigin.CLOUD),
            ),
        )

        assertEquals(
            listOf("module_a_1_20261009_093107.zip", "module_b_1_20261009_093107.zip"),
            kept.map { it.fileName },
        )
    }

    /** 同一侧内部的原有顺序不能被打乱：恢复是逐条走的，顺序变了用户看到的进度顺序就变了。 */
    @Test
    fun `rows on the same side keep their order`() {
        val kept = oneCopyPerContent(
            listOf(
                rowOf("module_a_1_20261009_093107.zip", BackupOrigin.CLOUD),
                rowOf("module_b_1_20261009_093107.zip", BackupOrigin.CLOUD),
                rowOf("module_a_1_20261009_093107.zip", BackupOrigin.LOCAL),
            ),
        )

        assertEquals(
            listOf(BackupOrigin.LOCAL, BackupOrigin.CLOUD),
            kept.map { it.origin },
        )
        assertEquals("module_b_1_20261009_093107.zip", kept.last().fileName)
    }

    /** 恢复一个模块只是重装那一个，不用拦。 */
    @Test
    fun `a single module needs no confirmation`() {
        val confirm = restoreConfirmFor(listOf(rowOf("module_a_1_20261009_093107.zip", BackupOrigin.LOCAL)))

        assertEquals(RestoreConfirm.NONE, confirm)
    }

    /** 恢复一整组会把它们按备份里的版本重装一遍，备份可能比现在旧——要问。 */
    @Test
    fun `two modules need one confirmation`() {
        val confirm = restoreConfirmFor(
            listOf(
                rowOf("module_a_1_20261009_093107.zip", BackupOrigin.LOCAL),
                rowOf("module_b_1_20261009_093107.zip", BackupOrigin.LOCAL),
            ),
        )

        assertEquals(RestoreConfirm.MANY, confirm)
    }

    /**
     * 本机与云端各一份的模块，默认全选也只动一个：不该弹"恢复这 2 项"。
     *
     * 这是那个被用户问出来的问题——详情页默认全选，标题报 2 项而实际只恢复 1 项，
     * 用户没法从标题判断自己到底要动几个东西。
     */
    @Test
    fun `the two sides of one module need no confirmation`() {
        val confirm = restoreConfirmFor(
            listOf(
                rowOf("module_a_1_20261009_093107.zip", BackupOrigin.LOCAL),
                rowOf("module_a_1_20261009_093107.zip", BackupOrigin.CLOUD),
            ),
        )

        assertEquals(RestoreConfirm.NONE, confirm)
    }

    /**
     * 仓库源两侧各一份时仍然要问。
     *
     * 去重把两条并成一条之后，"条数 > 1"这个条件不再成立；如果分支也跟着按行数放行，
     * 单条仓库源恢复（整批替换全部源）就会从确认框下面溜过去。
     */
    @Test
    fun `the two sides of a repo config still warn about the batch replace`() {
        val fileName = "repo-config_20261009_093107.zip"
        val confirm = restoreConfirmFor(
            listOf(
                rowOf(fileName, BackupOrigin.LOCAL, BackupKind.REPO_CONFIG),
                rowOf(fileName, BackupOrigin.CLOUD, BackupKind.REPO_CONFIG),
            ),
        )

        assertEquals(RestoreConfirm.REPO_CONFIG, confirm)
    }

    /** 仓库源与别的模块混选：条数已经 > 1，报的是"恢复好几条"那一句。 */
    @Test
    fun `a repo config mixed with a module counts as many`() {
        val confirm = restoreConfirmFor(
            listOf(
                rowOf("repo-config_20261009_093107.zip", BackupOrigin.LOCAL, BackupKind.REPO_CONFIG),
                rowOf("module_a_1_20261009_093107.zip", BackupOrigin.LOCAL),
            ),
        )

        assertEquals(RestoreConfirm.MANY, confirm)
    }

    /** 分区快照单独一条也要问：它是往块设备写镜像，写错就开不了机。 */
    @Test
    fun `a partition snapshot asks on its own`() {
        val confirm = restoreConfirmFor(
            listOf(rowOf("partition_boot_a_20261009_093107.img", BackupOrigin.LOCAL, BackupKind.PARTITION)),
        )

        assertEquals(RestoreConfirm.PARTITION, confirm)
    }

    /** 分区快照和别的条混在一起时问的仍然是它那一句：不可逆的那件事优先。 */
    @Test
    fun `a partition snapshot among other rows still asks about the partition`() {
        val confirm = restoreConfirmFor(
            listOf(
                rowOf("module_a_1_20261009_093107.zip", BackupOrigin.LOCAL),
                rowOf("module_b_1_20261009_093107.zip", BackupOrigin.LOCAL),
                rowOf("partition_init_boot_a_20261009_093107.img", BackupOrigin.LOCAL, BackupKind.PARTITION),
            ),
        )

        assertEquals(RestoreConfirm.PARTITION, confirm)
    }

    /** 一条都没勾：什么都不弹，别拿空列表去取 singleOrNull 的 kind。 */
    @Test
    fun `an empty selection needs no confirmation`() {
        assertEquals(RestoreConfirm.NONE, restoreConfirmFor(emptyList()))
    }

    // ---- 分区快照的组级联动（entryToggleSelection）----
    //
    // 一次快照是「内核 ↔ ksu.ko ↔ ramdisk」同刻的配对集，只刷回其中一两个会把配对拆散
    // （vermagic 对不上、root 失效）。所以分区行的 checkbox 是联动的：点任何一行，整组
    // 分区（跨本机/云端）一起动；模块行维持单选。

    private val partitionRows = listOf(
        rowOf("partition_boot_a_20261009_093107.img.gz", BackupOrigin.LOCAL, BackupKind.PARTITION),
        rowOf("partition_init_boot_a_20261009_093107.img.gz", BackupOrigin.LOCAL, BackupKind.PARTITION),
        rowOf("partition_boot_a_20261009_093107.img.gz", BackupOrigin.CLOUD, BackupKind.PARTITION),
    )

    /** 点一行没勾的分区：整组（含另一侧那份）全勾上。 */
    @Test
    fun `toggling one partition row selects the whole partition set`() {
        val next = entryToggleSelection(
            selected = setOf(partitionRows[0].id),
            rows = partitionRows,
            rowId = partitionRows[0].id,
        )

        assertEquals(partitionRows.mapTo(mutableSetOf()) { it.id }, next)
    }

    /** 已全勾时再点：整组一起取消，而不是只取消点到的那一行。 */
    @Test
    fun `toggling a fully selected partition set clears it`() {
        val all = partitionRows.mapTo(mutableSetOf()) { it.id }
        val next = entryToggleSelection(all, partitionRows, partitionRows[1].id)

        assertTrue(next.isEmpty())
    }

    /** 模块行照旧单选：全选状态下取消它，分区行的勾选一个都不能少。 */
    @Test
    fun `a module row is toggled on its own next to partitions`() {
        val module = rowOf("module_a_1_20261009_093107.zip", BackupOrigin.LOCAL)
        val rows = partitionRows + module
        val all = rows.mapTo(mutableSetOf()) { it.id }

        assertEquals(
            all - module.id,
            entryToggleSelection(all, rows, module.id),
        )
        // 反方向也一样：没勾时点模块只勾模块，不会顺手把分区拖进来。
        assertEquals(
            setOf(module.id),
            entryToggleSelection(emptySet(), rows, module.id),
        )
    }

    /** 两侧各一份的分区行一起动：联动按分区集合算，不看来源。 */
    @Test
    fun `partition rows on both sides move together`() {
        val next = entryToggleSelection(
            selected = setOf(partitionRows[2].id), // 只有云端那份勾着
            rows = partitionRows,
            rowId = partitionRows[2].id,
        )

        // 有一行没勾上就整组补齐，本机那两份也被带进来。
        assertEquals(partitionRows.mapTo(mutableSetOf()) { it.id }, next)
    }

    /** 勾选里已经不在列表里的 id：原样返回，别崩也别误动。 */
    @Test
    fun `an id that is not listed leaves the selection alone`() {
        val selected = setOf(partitionRows[0].id)

        assertEquals(
            selected,
            entryToggleSelection(selected, partitionRows, "cloud/gone"),
        )
    }
}
