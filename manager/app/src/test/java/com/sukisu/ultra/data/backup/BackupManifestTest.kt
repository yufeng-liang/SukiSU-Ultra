package com.sukisu.ultra.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManifestTest {

    private val entry = BackupEntry(
        kind = BackupKind.MODULE,
        entryId = "zygisk-assistant",
        fileName = "module_zygisk-assistant_12_20261008_120000.zip",
        metaFileName = "module_zygisk-assistant_12_20261008_120000.zip.meta.json",
        sizeBytes = 1834021L,
        sha256 = "9f2c8a1b",
        createdAt = "2026-10-08T12:00:00Z",
    )

    @Test
    fun `entries survive a round trip`() {
        assertEquals(listOf(entry), BackupManifest.parseEntries(BackupManifest.renderEntries(listOf(entry))))
    }

    @Test
    fun `manifest carries schema and version`() {
        val json = BackupManifest.renderEntries(listOf(entry))
        assertTrue(json.contains(ArchiveNaming.MANIFEST_SCHEMA))
        assertTrue(json.contains("\"version\": ${ArchiveNaming.MANIFEST_VERSION}"))
    }

    @Test
    fun `unparsable manifest yields empty list`() {
        assertEquals(emptyList<BackupEntry>(), BackupManifest.parseEntries("not json"))
    }

    @Test
    fun `module meta survives a round trip`() {
        val meta = ModuleBackupMeta(
            entryId = "zygisk-assistant",
            name = "Zygisk Assistant",
            versionName = "2.1.3",
            versionCode = 12L,
            author = "snake-4",
            metamodule = false,
            disabled = true,
            sourceDevice = "Xiaomi 14 Pro",
            createdAt = "2026-10-08T12:00:00Z",
        )
        assertEquals(meta, BackupManifest.parseModuleMeta(BackupManifest.renderModuleMeta(meta)))
    }

    @Test
    fun `a manifest from an unknown version is refused`() {
        // 认不出的版本往往意味着有字段我们读不懂，照旧解析会把读不懂的条目当成不存在，
        // 下一次整体回写就从索引里抹掉（归档还在盘上/远端，列表、去重、保留策略全看不见）。
        // 宁可整次报"索引读不出来"，让用户保留现场。
        val json = """
            {"schema":"${ArchiveNaming.MANIFEST_SCHEMA}","version":99,"entries":[
              {"kind":"module","entryId":"a","fileName":"module_a_1_20261008_120000.zip","metaFileName":null,"sizeBytes":1,"sha256":"s","createdAt":"2026-10-08T12:00:00Z"}
            ]}
        """.trimIndent()

        val error = assertThrows(BackupReasonException::class.java) { BackupManifest.parseEntriesOrThrow(json) }

        assertTrue("expected IndexUnreadable but got ${error.reason}", error.reason is BackupReason.IndexUnreadable)
        // 宽松的那条路（列表页用）跟着退化成空表，而不是"看起来解析成功、其实少了几条"。
        assertEquals(emptyList<BackupEntry>(), BackupManifest.parseEntries(json))
    }

    @Test
    fun `an entry kind this build does not know is refused`() {
        val json = """
            {"schema":"${ArchiveNaming.MANIFEST_SCHEMA}","version":${ArchiveNaming.MANIFEST_VERSION},"entries":[
              {"kind":"firmware","entryId":"a","fileName":"firmware_a.bin","sizeBytes":1,"sha256":"s","createdAt":"2026-10-08T12:00:00Z"}
            ]}
        """.trimIndent()

        val error = assertThrows(BackupReasonException::class.java) { BackupManifest.parseEntriesOrThrow(json) }

        assertTrue("expected IndexUnreadable but got ${error.reason}", error.reason is BackupReason.IndexUnreadable)
    }

    @Test
    fun `an entry without a file name is refused`() {
        val json = """
            {"schema":"${ArchiveNaming.MANIFEST_SCHEMA}","version":${ArchiveNaming.MANIFEST_VERSION},"entries":[
              {"kind":"module","entryId":"a","sizeBytes":1,"sha256":"s","createdAt":"2026-10-08T12:00:00Z"}
            ]}
        """.trimIndent()

        val error = assertThrows(BackupReasonException::class.java) { BackupManifest.parseEntriesOrThrow(json) }

        assertTrue("expected IndexUnreadable but got ${error.reason}", error.reason is BackupReason.IndexUnreadable)
    }

    @Test
    fun `a manifest without a version field is refused`() {
        // 手工拼出来的、或者从更老的版本留下来的清单没有版本号：同样按"读不懂"处理。
        val json = """{"schema":"${ArchiveNaming.MANIFEST_SCHEMA}","entries":[]}"""

        assertThrows(BackupReasonException::class.java) { BackupManifest.parseEntriesOrThrow(json) }
    }

    @Test
    fun `an empty entries array is a readable index`() {
        assertEquals(
            emptyList<BackupEntry>(),
            BackupManifest.parseEntriesOrThrow(BackupManifest.renderEntries(emptyList())),
        )
    }
}
