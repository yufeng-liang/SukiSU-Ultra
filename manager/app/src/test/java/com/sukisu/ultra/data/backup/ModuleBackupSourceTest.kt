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

    /**
     * 一个「认得出来」的模块 zip。
     *
     * 恢复前要读包里的 `module.prop.id` 与条目对账（见 [ModuleBackupSource.restore]），
     * 所以不能再拿任意字节当输入——那种输入现在会被判为"不是这个模块的包"。
     */
    private fun moduleZip(id: String): ByteArray {
        val file = File(temp.newFolder(), "restore-$id.zip")
        java.util.zip.ZipOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("module.prop"))
            out.write("id=$id\nname=Name $id\nversion=1.0\nversionCode=1\n".toByteArray())
            out.closeEntry()
        }
        return file.readBytes()
    }

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
    fun `restore installs the archive then applies the disabled flag`() {
        runBlocking {
            val calls = mutableListOf<String>()
            val restorer = object : ModuleRestorer {
                override suspend fun install(zip: File): Result<Unit> {
                    calls += "install"; return Result.success(Unit)
                }

                override suspend fun setDisabled(id: String, disabled: Boolean): Result<Unit> {
                    calls += "disable:$id=$disabled"; return Result.success(Unit)
                }
            }
            val meta = ModuleBackupMeta("a", "Name a", "1.0", 12L, "author", false, true, "dev", "2026-10-08T12:00:00Z")
            val entry = BackupEntry(BackupKind.MODULE, "a", "module_a_12_20261008_120000.zip", null, 3L, "x", "2026-10-08T12:00:00Z")

            val outcome = source(emptyList(), restorer)
                .restore(entry, BackupManifest.renderModuleMeta(meta), moduleZip("a").inputStream())

            assertTrue(outcome.getOrThrow().success)
            assertEquals(listOf("install", "disable:a=true"), calls)
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

            val outcome = source(emptyList(), restorer).restore(entry, null, moduleZip("a").inputStream()).getOrThrow()

            assertFalse(outcome.success)
            val reason = outcome.reason
            assertTrue(reason is BackupReason.ModuleInstallFailed)
            assertEquals("ksud said no", (reason as BackupReason.ModuleInstallFailed).external)
        }
    }

    @Test
    fun `restore refuses an entry id that would end up inside a root command`() {
        runBlocking {
            // 索引 JSON 在共享存储 / WebDAV 上，别人改得动。没有这道闸门，下面这个 id 会拼成
            // `ksud module disable a; rm -rf /` 交给 root shell 去跑。
            val calls = mutableListOf<String>()
            val restorer = object : ModuleRestorer {
                override suspend fun install(zip: File): Result<Unit> {
                    calls += "install"; return Result.success(Unit)
                }

                override suspend fun setDisabled(id: String, disabled: Boolean): Result<Unit> {
                    calls += "disable:$id"; return Result.success(Unit)
                }
            }
            val meta = ModuleBackupMeta(
                "a; rm -rf /", "Name", "1.0", 12L, "author",
                /* disabled = */ true, true, "dev", "2026-10-08T12:00:00Z",
            )
            val entry = BackupEntry(
                BackupKind.MODULE, "a; rm -rf /", "module_a_12_20261008_120000.zip", null, 3L, "x",
                "2026-10-08T12:00:00Z",
            )

            val outcome = source(emptyList(), restorer)
                .restore(entry, BackupManifest.renderModuleMeta(meta), moduleZip("a").inputStream())
                .getOrThrow()

            assertFalse(outcome.success)
            assertTrue(outcome.reason is BackupReason.ModuleIdUnusable)
            // 一条命令都不许发出去，安装也不行：这个 id 不可信，先装进去再谈别的没意义。
            assertEquals(emptyList<String>(), calls)
        }
    }

    @Test
    fun `restore refuses a zip that belongs to another module`() {
        runBlocking {
            // 索引与归档都在可写位置，别人换一个同名 zip 进去就能让「恢复 A」实际装上 B。
            // 更糟的是紧随其后的 setDisabled 会照着 A 的 id 去执行。所以先认包，认不对就整条拒收。
            val calls = mutableListOf<String>()
            val restorer = object : ModuleRestorer {
                override suspend fun install(zip: File): Result<Unit> {
                    calls += "install"; return Result.success(Unit)
                }

                override suspend fun setDisabled(id: String, disabled: Boolean): Result<Unit> {
                    calls += "disable:$id"; return Result.success(Unit)
                }
            }
            val entry = BackupEntry(
                BackupKind.MODULE, "a", "module_a_12_20261008_120000.zip", null, 3L, "x",
                "2026-10-08T12:00:00Z",
            )

            val outcome = source(emptyList(), restorer)
                .restore(entry, null, moduleZip("b").inputStream())
                .getOrThrow()

            assertFalse(outcome.success)
            assertTrue(outcome.reason is BackupReason.ModuleZipUnusable)
            assertEquals(emptyList<String>(), calls)
        }
    }

    @Test
    fun `restore refuses bytes that are not a module zip at all`() {
        runBlocking {
            val calls = mutableListOf<String>()
            val restorer = object : ModuleRestorer {
                override suspend fun install(zip: File): Result<Unit> {
                    calls += "install"; return Result.success(Unit)
                }

                override suspend fun setDisabled(id: String, disabled: Boolean) = Result.success(Unit)
            }
            val entry = BackupEntry(
                BackupKind.MODULE, "a", "module_a_12_20261008_120000.zip", null, 3L, "x",
                "2026-10-08T12:00:00Z",
            )

            val outcome = source(emptyList(), restorer)
                .restore(entry, null, "not a zip".byteInputStream())
                .getOrThrow()

            // 让 ksud 去猜同样是失败，但它的报错是给开发者看的；这里先说清「这份包不是模块包」。
            assertFalse(outcome.success)
            assertTrue(outcome.reason is BackupReason.ModuleZipUnusable)
            assertEquals(emptyList<String>(), calls)
        }
    }

    @Test
    fun `export skips a module that is waiting to be removed`() {
        runBlocking {
            // 挂着 remove 的模块下次开机就没了。给它打包等于备份一个正在退场的状态：
            // 用户重启后看着它消失，之后从这份备份恢复它又回来了。
            val outcome = source(listOf(module("a").copy(remove = true), module("b")))
                .export().getOrThrow()

            assertEquals(listOf("b"), outcome.artifacts.map { it.entryId })
            val failure = outcome.failures.single()
            assertEquals("a", failure.path)
            assertTrue(failure.reason is BackupReason.ModulePendingRemoval)
        }
    }

    private companion object {
        val NoopRestorer = object : ModuleRestorer {
            override suspend fun install(zip: File) = Result.success(Unit)
            override suspend fun setDisabled(id: String, disabled: Boolean) = Result.success(Unit)
        }
    }
}
