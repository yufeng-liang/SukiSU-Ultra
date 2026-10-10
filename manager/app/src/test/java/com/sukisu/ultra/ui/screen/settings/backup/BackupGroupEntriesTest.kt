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
        id = "LOCAL/MODULE/20261009_093556",
        origin = BackupOrigin.LOCAL,
        kind = BackupKind.MODULE,
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
        assertTrue(stateOf(group("a", "b"), setOf("a", "b")).allGroupEntriesSelected)
    }

    @Test
    fun `a leftover id from another group does not count as all selected`() {
        // 两条行、两个 id：数量对上了，但其中一个是别组留下的，而屏幕上明明有一条没勾。
        assertFalse(stateOf(group("a", "b"), setOf("a", "gone")).allGroupEntriesSelected)
    }

    @Test
    fun `an empty group is never all selected`() {
        assertFalse(stateOf(group(), emptySet()).allGroupEntriesSelected)
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
