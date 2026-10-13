package com.sukisu.ultra.ui.screen.settings.backup

import com.sukisu.ultra.data.backup.BackupKind
import com.sukisu.ultra.data.backup.BackupOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详情页（打开的那一份备份）里那几条判定。
 *
 * 这一页一进来就是全选，而「恢复」会把选中的模块按备份里的版本重装一遍——是"哪些行真被勾上了"
 * 决定了会拿什么盖掉现在装着的版本。勾选存的是行 id，切组、刷新之后集合里可能留着别组的 id，
 * 所以"数量刚好相等"不等于"都勾上了"。
 */
class BackupGroupEntriesTest {

    private fun row(id: String) = BackupRow(
        id = id,
        title = id,
        subtitle = "",
        fileName = "module_${id}_1_2026-10-09T09:35:56Z.zip",
        origin = BackupOrigin.LOCAL,
        originLabel = "",
        kind = BackupKind.MODULE,
        createdAt = "2026-10-09T09:35:56Z",
    )

    private fun group(vararg ids: String) = BackupGroup(
        id = "20261009_093556",
        origins = listOf(BackupOrigin.LOCAL),
        kinds = listOf(BackupKind.MODULE),
        session = "20261009_093556",
        label = "Oct 9",
        rows = ids.map { row(it) },
    )

    private fun stateOf(group: BackupGroup, selected: Set<String>) = BackupUiState(
        groups = listOf(group),
        openGroupId = group.id,
        openGroupSelected = selected,
    )

    @Test
    fun `every row ticked leaves the select all box on`() {
        assertTrue(stateOf(group("a", "b"), setOf("a", "b")).picksOf(BackupOrigin.LOCAL).allSelected)
    }

    @Test
    fun `a leftover id from another group does not count as all selected`() {
        // 两条行、两个 id：数量对上了，但其中一个是别组留下的，而屏幕上明明有一条没勾。
        assertFalse(stateOf(group("a", "b"), setOf("a", "gone")).picksOf(BackupOrigin.LOCAL).allSelected)
    }

    @Test
    fun `an empty group is never all selected`() {
        assertFalse(stateOf(group(), emptySet()).picksOf(BackupOrigin.LOCAL).allSelected)
    }

    @Test
    fun `a partly ticked side shows as partial`() {
        // 收起来之后这一行的勾选框就是"我挑过"的唯一线索：一条勾、一条没勾时要画成半选，
        // 否则看着和"一条都没勾"一模一样，刚挑的那几个像没被记住。
        val picks = stateOf(group("a", "b"), setOf("a")).picksOf(BackupOrigin.LOCAL)

        assertEquals(1, picks.picked)
        assertEquals(2, picks.total)
        assertTrue(picks.partial)
        assertFalse(picks.allSelected)
    }

    @Test
    fun `each side counts only its own rows`() {
        // 两侧分开成两段之后，分母是这一侧的行数：本机 1 条、云端 2 条、只勾了本机那条时，
        // 本机那一段是"1/1 全选"，不是"整组 1/3"。
        val dual = BackupGroup(
            id = "20261009_093556",
            origins = listOf(BackupOrigin.LOCAL, BackupOrigin.CLOUD),
            kinds = listOf(BackupKind.MODULE),
            session = "20261009_093556",
            label = "Oct 9",
            rows = listOf(
                row("a"),
                row("b").copy(origin = BackupOrigin.CLOUD),
                row("c").copy(origin = BackupOrigin.CLOUD),
            ),
        )
        val state = stateOf(dual, setOf("a"))

        assertTrue(state.picksOf(BackupOrigin.LOCAL).allSelected)
        assertEquals(1, state.picksOf(BackupOrigin.LOCAL).total)
        assertFalse(state.picksOf(BackupOrigin.CLOUD).allSelected)
        assertEquals(2, state.picksOf(BackupOrigin.CLOUD).total)
        assertEquals(0, state.picksOf(BackupOrigin.CLOUD).picked)
    }

    @Test
    fun `only the rows of the open group come back as selected`() {
        val selected = stateOf(group("a", "b"), setOf("b", "gone")).openGroupSelection

        assertEquals(listOf("b"), selected.map { it.id })
    }

    @Test
    fun `the restore confirmation counts the rows that will really be restored`() {
        // 界面按这个条数决定要不要弹「恢复 N 项」：只勾了一条时不该弹，别组留下的 id 也不能凑数。
        assertEquals(1, stateOf(group("a", "b"), setOf("a", "gone")).openGroupSelection.size)
    }
}
