package com.sukisu.ultra.data.backup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private class FakeRootFiles : RootFiles {
    val files = mutableMapOf<String, ByteArray>()

    override fun mkdirs(path: String) = true
    override fun copyTo(from: File, toPath: String): Boolean {
        files[toPath] = from.readBytes(); return true
    }
    override fun copyFrom(path: String, to: File): Boolean {
        val bytes = files[path] ?: return false
        to.writeBytes(bytes); return true
    }
    override fun rename(fromPath: String, toPath: String): Boolean {
        val bytes = files.remove(fromPath) ?: return false
        files[toPath] = bytes; return true
    }
    override fun list(dir: String) = files.keys.filter { it.startsWith("$dir/") }
        .map { RootFileEntry(it, files.getValue(it).size.toLong(), 0L) }
    override fun delete(path: String) = files.remove(path) != null
    override fun exists(path: String) = files.containsKey(path)
}

class LocalBackupStorageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var staging: StagingArea

    private fun storage(root: RootFiles): LocalBackupStorage {
        staging = StagingArea(temp.newFolder("staging"))
        return LocalBackupStorage("/sdcard/Download/SukiSU-Backup", staging, root)
    }

    @Test
    fun `put writes through the root shell and get reads back`() {
        runBlocking {
            val root = FakeRootFiles()
            val store = storage(root)
            val payload = "hello".toByteArray()

            assertTrue(store.put("a.txt", payload.size.toLong()) { payload.inputStream() }.isSuccess)
            assertEquals("/sdcard/Download/SukiSU-Backup/a.txt", root.files.keys.single())
            assertEquals("hello", store.get("a.txt").getOrThrow().use { it.readBytes().decodeToString() })
        }
    }

    @Test
    fun `text round trip`() {
        runBlocking {
            val store = storage(FakeRootFiles())
            assertTrue(store.writeText("index.json", "{\"a\":1}").isSuccess)
            assertEquals("{\"a\":1}", store.readText("index.json").getOrThrow())
        }
    }

    @Test
    fun `readText on a missing file is empty text rather than a failure`() {
        runBlocking {
            // 引擎靠这个把"还没有索引"和"索引读不到"分开：后者绝不能触发整体回写。
            assertEquals("", storage(FakeRootFiles()).readText("index.json").getOrThrow())
        }
    }

    @Test
    fun `get on a missing file fails instead of throwing`() {
        runBlocking {
            val store = storage(FakeRootFiles())
            assertTrue(store.get("nope.bin").isFailure)
        }
    }

    @Test
    fun `delete removes the file`() {
        runBlocking {
            val root = FakeRootFiles()
            val store = storage(root)
            store.writeText("a.txt", "x")
            assertTrue(store.delete("a.txt").isSuccess)
            assertTrue(root.files.isEmpty())
        }
    }

    @Test
    fun `the staged copy is gone once the returned stream is closed`() {
        runBlocking {
            val store = storage(FakeRootFiles())
            store.writeText("a.txt", "hello")

            store.get("a.txt").getOrThrow().use { it.readBytes() }

            assertTrue(
                "reads must not leave a copy of every archive behind in cache",
                staging.root.listFiles().orEmpty().isEmpty(),
            )
        }
    }

    @Test
    fun `a path that tries to escape the backup directory is flattened`() {
        runBlocking {
            val root = FakeRootFiles()
            storage(root).writeText("../../evil.txt", "x")
            assertEquals("/sdcard/Download/SukiSU-Backup/evil.txt", root.files.keys.single())
        }
    }

    @Test
    fun `test reports failure when the backup directory is not writable`() {
        runBlocking {
            val broken = object : RootFiles by FakeRootFiles() {
                override fun mkdirs(path: String) = false
            }
            assertTrue(storage(broken).test().isFailure)
        }
    }

    @Test
    fun `a write lands under the target name and leaves no temporary file behind`() {
        runBlocking {
            val root = FakeRootFiles()
            assertTrue(storage(root).writeText("index.json", "{}").isSuccess)
            assertEquals(listOf("/sdcard/Download/SukiSU-Backup/index.json"), root.files.keys.toList())
        }
    }

    @Test
    fun `an oversized index is refused instead of pulled into memory`() {
        runBlocking {
            // 索引被换成一个几十 MB 的大文件时，先把整份读进内存会 OOM——OOM 连"拒绝回写"
            // 这一步都做不到，那才是真的丢索引。超限直接报"索引读不出来"，文件原样留着。
            val root = FakeRootFiles()
            val store = storage(root)
            root.files["/sdcard/Download/SukiSU-Backup/index.json"] =
                ByteArray(BackupLimits.MAX_TEXT_BYTES.toInt() + 1)

            val result = store.readText(ArchiveNaming.INDEX_FILE)

            assertTrue(result.isFailure)
            val reason = reasonOf(result.exceptionOrNull()!!)
            assertTrue("expected IndexUnreadable but got $reason", reason is BackupReason.IndexUnreadable)
        }
    }

    @Test
    fun `when the rename fails the previous content survives untouched`() {
        runBlocking {
            // 这一条守的是"索引不会被自己的写入写坏"：改名失败（或写到一半断电）时，目标文件必须
            // 还是上一次那份完整内容，而不是被截断的半截——引擎读不出索引就拒绝回写，等于整份
            // 备份列表消失。
            val root = FakeRootFiles()
            storage(root).writeText("index.json", "old")
            val noRename = object : RootFiles by root {
                override fun rename(fromPath: String, toPath: String) = false
            }
            val broken = LocalBackupStorage("/sdcard/Download/SukiSU-Backup", staging, noRename)

            assertTrue(broken.writeText("index.json", "new-and-much-longer").isFailure)

            assertEquals("old", root.files.getValue("/sdcard/Download/SukiSU-Backup/index.json").decodeToString())
            assertTrue(root.files.keys.single(), root.files.keys.none { it.endsWith(".tmp") })
        }
    }
}
