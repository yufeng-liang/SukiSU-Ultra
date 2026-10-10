package com.sukisu.ultra.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.Instant
import org.junit.Test

class ArchiveNamingTest {

    @Test
    fun `timestamp is UTC and filesystem safe`() {
        assertEquals("20261008_120000", ArchiveNaming.timestamp(Instant.parse("2026-10-08T12:00:00Z")))
    }

    @Test
    fun `module archive name carries id version code and timestamp`() {
        assertEquals(
            "module_zygisk-assistant_12_20261008_120000.zip",
            ArchiveNaming.moduleArchiveName("zygisk-assistant", 12L, "20261008_120000")
        )
    }

    @Test
    fun `unsafe characters in module id are replaced`() {
        assertEquals(
            "module_bad_id_1_1_20261008_120000.zip",
            ArchiveNaming.moduleArchiveName("bad/id 1", 1L, "20261008_120000")
        )
    }

    @Test
    fun `boot archive name keeps the first twelve sha1 characters`() {
        assertEquals(
            "boot_9f2c8a1b4e6d_20261008_120000.img",
            ArchiveNaming.bootArchiveName("9f2c8a1b4e6d00112233445566778899aabbccdd", "20261008_120000")
        )
    }

    @Test
    fun `meta file name appends a suffix`() {
        assertEquals("a.zip.meta.json", ArchiveNaming.metaFileNameFor("a.zip"))
    }

    @Test
    fun `kind is read back from the archive name`() {
        assertEquals(BackupKind.MODULE, ArchiveNaming.kindOf("module_x_1_20261008_120000.zip"))
        assertEquals(BackupKind.BOOT, ArchiveNaming.kindOf("boot_9f2c8a1b4e6d_20261008_120000.img"))
        assertNull(ArchiveNaming.kindOf("index.json"))
    }

    @Test
    fun `an external file name is flattened to a single safe segment`() {
        assertEquals("evil.txt", ArchiveNaming.safeFileName("../../evil.txt"))
        assertEquals("x.zip", ArchiveNaming.safeFileName("primary:Download/x.zip"))
        assertEquals("imported.bin", ArchiveNaming.safeFileName(".."))
        assertEquals("imported.bin", ArchiveNaming.safeFileName("   "))
        // 这个字符串会被拼进以 root 身份执行的 shell 命令，引号与分号不能留下。
        assertEquals("__rm_-rf__.txt", ArchiveNaming.safeFileName("';rm -rf '.txt"))
    }

    @Test
    fun `reserved characters in an imported name are normalized away`() {
        // WebDAV 上 `#`、`?`、`%` 是保留字符/转义引导，空格与 `+` 会被当成别的东西：导入进来的
        // 名字统一压成 [A-Za-z0-9._-]，之后拼进 URL 或 root shell 命令都不会改变意思。
        assertEquals("a_b_c_d_e.zip", ArchiveNaming.safeFileName("a#b?c%d e.zip"))
        assertEquals("100_.zip", ArchiveNaming.safeFileName("100%.zip"))
        // `+` 不在白名单里，同样被归一成下划线（不要以为它是安全字符）。
        assertEquals("a_b.zip", ArchiveNaming.safeFileName("a+b.zip"))
        // 幂等：已经洗过的名字再过一遍必须原样，不然改名会一轮一轮往下变。
        val once = ArchiveNaming.safeFileName("a#b?c%d e.zip")
        assertEquals(once, ArchiveNaming.safeFileName(once))
    }

    @Test
    fun `a colliding name gets a numbered suffix before the extension`() {
        assertEquals("a.zip", ArchiveNaming.uniqueName("a.zip", emptySet()))
        assertEquals("a_2.zip", ArchiveNaming.uniqueName("a.zip", setOf("a.zip")))
        assertEquals("a_3.zip", ArchiveNaming.uniqueName("a.zip", setOf("a.zip", "a_2.zip")))
        assertEquals("noext_2", ArchiveNaming.uniqueName("noext", setOf("noext")))
    }

    @Test
    fun `a module id is only usable when it needs no escaping`() {
        // 这个串会拼进 `ksud module enable|disable <id>`，那是以 root 身份跑的 shell 命令，
        // 而 id 来自外部可写的索引。规则因此是"要么原样能用、要么拒绝"，不做转义。
        assertTrue(ArchiveNaming.isValidModuleId("zygisk-assistant"))
        assertTrue(ArchiveNaming.isValidModuleId("a.b_c-1"))
        assertTrue(ArchiveNaming.isValidModuleId("A".repeat(128)))
        assertFalse(ArchiveNaming.isValidModuleId(""))
        assertFalse(ArchiveNaming.isValidModuleId("a b"))
        assertFalse(ArchiveNaming.isValidModuleId("A".repeat(129)))
        assertFalse(ArchiveNaming.isValidModuleId("a; rm -rf /"))
        assertFalse(ArchiveNaming.isValidModuleId("a\nrm -rf /"))
        assertFalse(ArchiveNaming.isValidModuleId("`id`"))
        assertFalse(ArchiveNaming.isValidModuleId("a/b"))
        assertFalse(ArchiveNaming.isValidModuleId("../a"))
    }
}
