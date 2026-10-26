package com.sukisu.ultra.data.modulezip

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModuleZipArchive] 的行为约定。
 *
 * 这层是「安装时留一份原始包」的落盘侧：装成功的 zip 复制进来、按 id 一条、超上限淘汰最旧。
 * 用真实文件系统（临时目录）而不是 mock：这里要验证的正是改名、覆盖、删除这些文件操作，
 * mock 掉就等于什么都没测。
 */
class ModuleZipArchiveTest {

    private fun root(): File = createTempDirectory("module-zips-").toFile()

    private fun prop(id: String, versionCode: Long = 1L): String = """
        id=$id
        name=$id
        version=v$versionCode
        versionCode=$versionCode
        author=Someone
        description=Test module
    """.trimIndent()

    /** 造一个 ksud 能认的模块包（zip 根下有 module.prop）。 */
    private fun zip(id: String, versionCode: Long = 1L, filler: String = ""): File {
        val dir = createTempDirectory("module-src-").toFile()
        val file = File(dir, "$id.zip")
        ZipOutputStream(file.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry("module.prop"))
            out.write(prop(id, versionCode).toByteArray())
            out.closeEntry()
            if (filler.isNotEmpty()) {
                out.putNextEntry(ZipEntry("payload.bin"))
                out.write(filler.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `stored zip is found by id and keeps its module prop`() {
        val archive = ModuleZipArchive(root())
        val stored = archive.store(zip("awesome_module"))

        assertNotNull(stored)
        assertEquals("awesome_module", stored!!.id)
        assertEquals("v1", stored.meta.version)
        assertTrue(archive.has("awesome_module"))

        val entry = archive.entry("awesome_module")
        assertNotNull(entry)
        assertEquals(stored.file.absolutePath, entry!!.file.absolutePath)
        assertEquals(stored.sizeBytes, entry.sizeBytes)
    }

    @Test
    fun `unknown id reports nothing`() {
        val archive = ModuleZipArchive(root())

        assertFalse(archive.has("nope"))
        assertNull(archive.entry("nope"))
    }

    @Test
    fun `storing the same id twice replaces the older copy`() {
        val archive = ModuleZipArchive(root())
        archive.store(zip("awesome_module", versionCode = 1))
        val second = archive.store(zip("awesome_module", versionCode = 2))

        assertNotNull(second)
        assertEquals(2L, second!!.meta.versionCode)
        // 一个 id 只留一条：目录里不该出现第二份。
        assertEquals(1, archive.root.listFiles()!!.count { it.isFile })
        assertEquals(2L, archive.entry("awesome_module")!!.meta.versionCode)
    }

    @Test
    fun `remove drops the stored copy`() {
        val archive = ModuleZipArchive(root())
        archive.store(zip("awesome_module"))

        assertTrue(archive.remove("awesome_module"))
        assertFalse(archive.has("awesome_module"))
    }

    @Test
    fun `a corrupted stored zip is discarded instead of being offered`() {
        val root = root()
        val archive = ModuleZipArchive(root)
        // 名字对、内容不是 zip：读不出 module.prop，就不该被当成可用的原始包。
        File(root, ModuleZipArchive.fileNameFor("broken")).writeText("not a zip")

        assertNull(archive.entry("broken"))
        assertFalse(File(root, ModuleZipArchive.fileNameFor("broken")).exists())
    }

    @Test
    fun `too many entries evicts the oldest one`() {
        val root = root()
        val archive = ModuleZipArchive(root, ModuleZipArchive.Limits(maxEntries = 2))
        val base = 1_700_000_000_000L
        archive.store(zip("first"), now = base)
        archive.store(zip("second"), now = base + 1_000)
        archive.store(zip("third"), now = base + 2_000)

        assertFalse(archive.has("first"))
        assertTrue(archive.has("second"))
        assertTrue(archive.has("third"))
    }

    @Test
    fun `total bytes cap evicts the oldest one`() {
        val root = root()
        // 先造两份同样大小的包，再把上限卡在「只装得下一份半」：
        // zip 是 deflate 过的，直接猜字节数不可靠，所以按实测大小算上限。
        val first = zip("first", filler = "x".repeat(2_000))
        val perEntry = first.length()
        assertTrue("包太小，测试前提不成立", perEntry > 100L)
        val archive = ModuleZipArchive(
            root,
            ModuleZipArchive.Limits(maxEntries = 99, maxTotalBytes = perEntry * 3 / 2),
        )
        val base = 1_700_000_000_000L
        archive.store(first, now = base)
        archive.store(zip("second", filler = "x".repeat(2_000)), now = base + 1_000)

        assertFalse(archive.has("first"))
        assertTrue(archive.has("second"))
    }

    @Test
    fun `clear empties the archive directory`() {
        val archive = ModuleZipArchive(root())
        archive.store(zip("one"))
        archive.store(zip("two"))

        archive.clear()

        assertFalse(archive.has("one"))
        assertFalse(archive.has("two"))
    }

    @Test
    fun `an id with path separators cannot escape the archive directory`() {
        val root = root()
        val archive = ModuleZipArchive(root)
        // 防御性检查：万一 id 放宽到能带 ../，文件名也必须落在 root 里。
        // 注意 `.` 本身是合法字符（id 里允许），所以 `..` 会留下来——真正要保证的是
        // 不含路径分隔符、且拼出来的文件父目录仍是 root。
        val name = ModuleZipArchive.fileNameFor("../../evil")

        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertEquals(root.absolutePath, File(root, name).parentFile!!.absolutePath)
        assertTrue(name.endsWith(".zip"))
        assertNull(archive.entry("../../evil"))
    }

    @Test
    fun `ids reports the sanitized form the caller must compare against`() {
        val archive = ModuleZipArchive(root())
        archive.store(zip("normal_module"))

        // ids() 报的是文件名形态；上传页拿 module id 比对时必须先过 safeId，
        // 这条用例把那个约定钉住，免得将来有人直接拿原始 id 去 contains。
        assertEquals(setOf("normal_module"), archive.ids())
        assertTrue(ModuleZipArchive.safeId("normal_module") in archive.ids())
        // 常规 id 里全是合法字符，safeId 是恒等变换。
        assertEquals("normal_module", ModuleZipArchive.safeId("normal_module"))
        assertEquals("a_b", ModuleZipArchive.safeId("a/b"))
    }

    @Test
    fun `a non zip source is not stored`() {
        val archive = ModuleZipArchive(root())
        val bogus = File(createTempDirectory("module-bogus-").toFile(), "bogus.zip")
        bogus.writeText("definitely not a zip")

        assertNull(archive.store(bogus))
        assertTrue(archive.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `consuming the source moves it instead of copying`() {
        val root = root()
        val archive = ModuleZipArchive(root)
        val source = zip("moved_module")

        val stored = archive.store(source, consumeSource = true)

        assertNotNull(stored)
        assertEquals("moved_module", stored!!.id)
        // 源被搬走（不是复制）：flashModule 的临时副本本来就该消失，省掉一次几十 MB 的读写。
        assertFalse("源文件应已被搬走", source.exists())
        assertTrue(archive.has("moved_module"))
        // 目录里只应剩下落地的那一份，不能残留 incoming-* 临时名。
        assertEquals(1, root.listFiles()!!.count { it.isFile })
    }

    @Test
    fun `consuming the source still overwrites the previous copy of the same id`() {
        val archive = ModuleZipArchive(root())
        archive.store(zip("same_id", versionCode = 1))

        val second = archive.store(zip("same_id", versionCode = 2), consumeSource = true)

        assertNotNull(second)
        assertEquals(2L, archive.entry("same_id")!!.meta.versionCode)
    }

    @Test
    fun `a failing store keeps the source when it is not consumed`() {
        val archive = ModuleZipArchive(root())
        val bogus = File(createTempDirectory("module-bogus-").toFile(), "bogus.zip")
        bogus.writeText("not a zip")

        assertNull(archive.store(bogus, consumeSource = true))
        // 读不出 module.prop 就不该动源文件：调用方可能还要拿它做别的事（比如报错原文）。
        assertTrue(bogus.exists())
    }
}
