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
            origin = origin,
            kind = BackupKind.MODULE,
            session = id,
            label = id,
            rows = (1..rows).map { row("$id-$it", origin) },
            isRollback = false,
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
}
