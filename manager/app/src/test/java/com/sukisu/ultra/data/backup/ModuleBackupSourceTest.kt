package com.sukisu.ultra.data.backup

import com.sukisu.ultra.data.model.Module
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

private fun module(id: String, versionCode: Long = 1L, enabled: Boolean = true) = Module(
    id = id,
    name = "Name $id",
    author = "author",
    version = "1.0",
    versionCode = versionCode,
    description = "desc",
    enabled = enabled,
    update = false,
    remove = false,
    updateJson = "",
    hasWebUi = false,
    hasActionScript = false,
    metamodule = false,
    actionIconPath = null,
    webUiIconPath = null,
)

class ModuleBackupSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val now = Instant.parse("2026-10-08T12:00:00Z")

    private fun source(
        modules: List<Module>,
        restorer: ModuleRestorer = NoopRestorer,
        archive: (Module) -> Result<ArchivedModule> = { m ->
            val file = File(temp.newFolder(), "${m.id}.zip").apply { writeText("zip-${m.id}") }
            Result.success(ArchivedModule(file, file.length(), file.readBytes().sha256Hex()))
        },
    ) = ModuleBackupSource(
        lister = { modules },
        archiver = { archive(it) },
        restorer = restorer,
        clock = { now },
        deviceName = { "Test Device" },
        staging = StagingArea(temp.newFolder()),
    )

    @Test
    fun `export produces one artifact per module with meta`() {
        runBlocking {
            val artifacts = source(listOf(module("a", 12L), module("b", 3L))).export().getOrThrow().artifacts

            assertEquals(
                listOf("module_a_12_20261008_120000.zip", "module_b_3_20261008_120000.zip"),
                artifacts.map { it.fileName }
            )
            assertEquals("a", artifacts[0].entryId)
            assertEquals("zip-a".toByteArray().sha256Hex(), artifacts[0].sha256)
            assertEquals("Test Device", BackupManifest.parseModuleMeta(artifacts[0].metaJson!!).sourceDevice)
        }
    }

    @Test
    fun `export with a selection archives only the checked modules`() {
        runBlocking {
            val outcome = source(listOf(module("a"), module("b"), module("c")))
                .export(setOf("a", "c")).getOrThrow()

            assertEquals(listOf("a", "c"), outcome.artifacts.map { it.entryId })
            // 没勾的模块不是"失败"，也不该出现在失败列表里——用户就是不想备份它。
            assertTrue(outcome.failures.isEmpty())
        }
    }

    @Test
    fun `export with an empty selection archives nothing instead of everything`() {
        runBlocking {
            // 空集合和 null 必须是两件事：null 是"全部"，空是"一个都不要"。
            val outcome = source(listOf(module("a"), module("b"))).export(emptySet()).getOrThrow()
            assertTrue(outcome.artifacts.isEmpty())
        }
    }

    @Test
    fun `export ignores ids that are no longer installed`() {
        runBlocking {
            // 勾上之后模块被卸载：剩下的照常打包，卸载掉的那个不该让整次备份失败。
            val outcome = source(listOf(module("a"))).export(setOf("a", "gone")).getOrThrow()
            assertEquals(listOf("a"), outcome.artifacts.map { it.entryId })
        }
    }

    @Test
    fun `disabled state is recorded in meta but not in the archive name`() {
        runBlocking {
            val artifacts = source(listOf(module("a", enabled = false))).export().getOrThrow().artifacts
            assertTrue(BackupManifest.parseModuleMeta(artifacts[0].metaJson!!).disabled)
        }
    }

    @Test
    fun `a module that cannot be archived is skipped and reported`() {
        runBlocking {
            val outcome = source(
                listOf(module("a"), module("b")),
                archive = { m ->
                    if (m.id == "a") Result.failure(IllegalStateException("boom")) else {
                        val file = File(temp.newFolder(), "${m.id}.zip").apply { writeText("zip") }
                        Result.success(ArchivedModule(file, file.length(), file.readBytes().sha256Hex()))
                    }
                },
            ).export().getOrThrow()

            assertEquals(listOf("b"), outcome.artifacts.map { it.entryId })
            // 静默跳过会让用户看到"written 1"却不知道有模块根本没备上。
            assertEquals("a", outcome.failures.single().path)
            assertTrue(outcome.failures.single().reason is BackupReason.ArchiveFailed)
        }
    }

    @Test
    fun `the packaged zip is cleaned up after the artifact is consumed`() {
        runBlocking {
            val artifact = source(listOf(module("a"))).export().getOrThrow().artifacts.single()
            val packed = artifact.openContent().use { it.readBytes() }
            assertTrue(packed.isNotEmpty())

            artifact.cleanup?.invoke()

            assertTrue(
                "module zips must not pile up in cache",
                temp.root.walkTopDown().none { it.isFile && it.name == "a.zip" },
            )
        }
    }

    @Test
    fun `exportOne archives only the requested module`() {
        runBlocking {
            val artifact = source(listOf(module("a"), module("b"))).exportOne("b").getOrThrow()
            assertEquals("b", artifact?.entryId)
        }
    }

    @Test
    fun `exportOne returns null for an unknown module`() {
        runBlocking {
            assertNull(source(listOf(module("a"))).exportOne("nope").getOrThrow())
        }
    }

    @Test
    fun `restore installs the archive then applies the disabled flag`() {
        runBlocking {
            val calls = mutableListOf<String>()
            val restorer = object : ModuleRestorer {
                override suspend fun install(zip: File): Result<Unit> {
                    calls += "install:${zip.readText()}"; return Result.success(Unit)
                }

                override suspend fun setDisabled(id: String, disabled: Boolean): Result<Unit> {
                    calls += "disable:$id=$disabled"; return Result.success(Unit)
                }
            }
            val meta = ModuleBackupMeta("a", "Name a", "1.0", 12L, "author", false, true, "dev", "2026-10-08T12:00:00Z")
            val entry = BackupEntry(BackupKind.MODULE, "a", "module_a_12_20261008_120000.zip", null, 3L, "x", "2026-10-08T12:00:00Z")

            val outcome = source(emptyList(), restorer)
                .restore(entry, BackupManifest.renderModuleMeta(meta), "zip".byteInputStream())

            assertTrue(outcome.getOrThrow().success)
            assertEquals(listOf("install:zip", "disable:a=true"), calls)
        }
    }

    @Test
    fun `restore reports failure when install fails`() {
        runBlocking {
            val restorer = object : ModuleRestorer {
                override suspend fun install(zip: File) = Result.failure<Unit>(IllegalStateException("ksud said no"))
                override suspend fun setDisabled(id: String, disabled: Boolean) = Result.success(Unit)
            }
            val entry = BackupEntry(BackupKind.MODULE, "a", "module_a_12_20261008_120000.zip", null, 3L, "x", "2026-10-08T12:00:00Z")

            val outcome = source(emptyList(), restorer).restore(entry, null, "zip".byteInputStream()).getOrThrow()

            assertFalse(outcome.success)
            val reason = outcome.reason
            assertTrue(reason is BackupReason.ModuleInstallFailed)
            assertEquals("ksud said no", (reason as BackupReason.ModuleInstallFailed).external)
        }
    }

    private companion object {
        val NoopRestorer = object : ModuleRestorer {
            override suspend fun install(zip: File) = Result.success(Unit)
            override suspend fun setDisabled(id: String, disabled: Boolean) = Result.success(Unit)
        }
    }
}
