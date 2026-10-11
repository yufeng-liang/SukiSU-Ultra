package com.sukisu.ultra.data.backup

import com.sukisu.ultra.data.repository.RepoSource
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant

/**
 * 仓库源配置备份源的往返语义。
 *
 * 这份归档的价值全在"整批替换"上：导出必须是**内容确定**的 JSON（不带时间戳，否则
 * sha256 去重永远不命中）；恢复必须整批替换、且对残缺/伪造的归档整体拒绝——半截列表
 * 写下去会把用户现存的源清掉。
 */
class RepoConfigBackupSourceTest {

    private class FakeWriter : RepoConfigWriter {
        var applied: List<RepoSource>? = null
        override fun applyAll(sources: List<RepoSource>) {
            applied = sources
        }
    }

    private fun source(
        sources: List<RepoSource>,
        writer: FakeWriter = FakeWriter(),
        now: Instant = Instant.parse("2026-10-12T00:00:00Z"),
    ) = RepoConfigBackupSource(reader = { sources }, writer = writer, clock = { now }) to writer

    private suspend fun exportOne(source: RepoConfigBackupSource): BackupArtifact =
        source.export().getOrThrow().artifacts.single()

    @Test
    fun `export with no sources produces nothing to store`() = runBlocking {
        val (src, _) = source(emptyList())
        assertTrue(src.export().getOrThrow().artifacts.isEmpty())
    }

    @Test
    fun `export yields one deterministic archive`() = runBlocking {
        val (src, _) = source(
            listOf(
                RepoSource("id-1", "官方", "https://example.com/a.json", enabled = true),
                RepoSource("id-2", "备用", "https://example.com/b.json", enabled = false),
            ),
        )
        val first = exportOne(src)
        val second = exportOne(src)

        assertEquals("repo-config", first.entryId)
        assertTrue(first.fileName.startsWith("repo_config_"))
        assertTrue(first.fileName.endsWith(".json"))
        assertEquals(first.sha256, second.sha256)
        assertEquals(first.sizeBytes, second.sizeBytes)
        // meta 是给模块/boot 用的（名称/版本、镜像 sha1），这份归档自己会说话。
        assertEquals(null, first.metaJson)
    }

    @Test
    fun `archive content carries schema and every source`() = runBlocking {
        val (src, _) = source(listOf(RepoSource("id-1", "官方", "https://example.com/a.json")))
        val json = exportOne(src).openContent().use { it.readBytes().toString(Charsets.UTF_8) }
        val root = JSONObject(json)

        assertEquals("sukisu.repo-config.backup", root.getString("schema"))
        val item = root.getJSONArray("sources").getJSONObject(0)
        assertEquals("id-1", item.getString("id"))
        assertEquals("官方", item.getString("name"))
        assertEquals("https://example.com/a.json", item.getString("url"))
        assertEquals(true, item.getBoolean("enabled"))
    }

    @Test
    fun `restore replaces the whole list`() = runBlocking {
        val writer = FakeWriter()
        val sources = listOf(RepoSource("id-1", "官方", "https://example.com/a.json"))
        val (src, _) = source(sources, writer = writer)
        val artifact = exportOne(src)

        val outcome = src.restore(entryOf(artifact), metaJson = null, content = artifact.openContent())

        assertTrue(outcome.getOrThrow().success)
        assertEquals(sources, writer.applied)
    }

    @Test
    fun `malformed archive is rejected and writes nothing`() = runBlocking {
        val writer = FakeWriter()
        val (src, _) = source(emptyList(), writer = writer)
        val bad = listOf("{ not json", "{\"sources\":[{\"name\":\"缺 url\"}]}", "[]")

        bad.forEach { text ->
            val outcome = src.restore(entryOf(), metaJson = null, content = ByteArrayInputStream(text.toByteArray()))
            assertTrue(outcome.getOrThrow().let { !it.success && it.reason is BackupReason.RepoConfigInvalid })
        }
        assertEquals(null, writer.applied)
    }

    @Test
    fun `restored sources without ids get stable generated ones`() = runBlocking {
        val writer = FakeWriter()
        val (src, _) = source(emptyList(), writer = writer)
        val text = "{\"schema\":\"sukisu.repo-config.backup\",\"version\":1," +
            "\"sources\":[{\"url\":\"https://example.com/a.json\",\"enabled\":false}]}"

        val outcome = src.restore(entryOf(), metaJson = null, content = ByteArrayInputStream(text.toByteArray()))

        assertTrue(outcome.getOrThrow().success)
        // 空名与 prefs 解码路径同一待遇：按地址取默认名，不让界面摆一行空标题。
        assertEquals(listOf(RepoSource("repo-0", "example.com", "https://example.com/a.json", enabled = false)), writer.applied)
    }

    @Test
    fun `archive with unknown schema or version is rejected`() = runBlocking {
        val writer = FakeWriter()
        val (src, _) = source(emptyList(), writer = writer)
        // 写下去的 schema/version 读取时也要认：别的产物的归档、未来版本的归档都不该被
        // 当成今天的样子静默解析。
        val good = "{\"schema\":\"sukisu.repo-config.backup\",\"version\":1," +
            "\"sources\":[{\"url\":\"https://example.com/a.json\"}]}"
        val badSchema = good.replace("sukisu.repo-config.backup", "someone.else")
        val badVersion = good.replace("\"version\":1", "\"version\":2")

        listOf(badSchema, badVersion).forEach { text ->
            val outcome = src.restore(entryOf(), metaJson = null, content = ByteArrayInputStream(text.toByteArray()))
            assertTrue(outcome.getOrThrow().let { !it.success && it.reason is BackupReason.RepoConfigInvalid })
        }
        assertEquals(null, writer.applied)
    }

    @Test
    fun `duplicate ids and same urls collapse into one source`() = runBlocking {
        val writer = FakeWriter()
        val (src, _) = source(emptyList(), writer = writer)
        // addSource 拦得住重复配置，整批替换没有那道闸：重复的 id（prefs 主键，重复会让
        // 删除/开关一次动到多条）和同址的来源（末尾斜杠算同一个）都只留最先出现的那条。
        val text = "{\"schema\":\"sukisu.repo-config.backup\",\"version\":1,\"sources\":[" +
            "{\"id\":\"a\",\"url\":\"https://example.com/a.json\"}," +
            "{\"id\":\"a\",\"name\":\"dup id\",\"url\":\"https://example.com/b.json\"}," +
            "{\"id\":\"b\",\"name\":\"dup url\",\"url\":\"https://example.com/a.json/\"}" +
            "]}"

        val outcome = src.restore(entryOf(), metaJson = null, content = ByteArrayInputStream(text.toByteArray()))

        assertTrue(outcome.getOrThrow().success)
        assertEquals(
            listOf(RepoSource("a", "example.com", "https://example.com/a.json", enabled = true)),
            writer.applied,
        )
    }

    private fun entryOf(artifact: BackupArtifact? = null) = BackupEntry(
        kind = BackupKind.REPO_CONFIG,
        entryId = artifact?.entryId ?: RepoConfigBackupSource.ENTRY_ID,
        fileName = artifact?.fileName ?: "repo_config_x.json",
        metaFileName = null,
        sizeBytes = artifact?.sizeBytes ?: 0,
        sha256 = artifact?.sha256 ?: "",
        createdAt = "2026-10-12T00:00:00Z",
    )
}
