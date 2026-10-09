package com.sukisu.ultra.data.backup

import org.junit.Assert.assertEquals
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
}
