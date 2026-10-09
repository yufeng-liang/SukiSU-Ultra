package com.sukisu.ultra.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `a colliding name gets a numbered suffix before the extension`() {
        assertEquals("a.zip", ArchiveNaming.uniqueName("a.zip", emptySet()))
        assertEquals("a_2.zip", ArchiveNaming.uniqueName("a.zip", setOf("a.zip")))
        assertEquals("a_3.zip", ArchiveNaming.uniqueName("a.zip", setOf("a.zip", "a_2.zip")))
        assertEquals("noext_2", ArchiveNaming.uniqueName("noext", setOf("noext")))
    }
}
