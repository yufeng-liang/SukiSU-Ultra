package com.sukisu.ultra.data.backup

import com.sukisu.ultra.data.repository.RepoSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant

fun interface RepoConfigReader {
    fun load(): List<RepoSource>
}

fun interface RepoConfigWriter {
    fun applyAll(sources: List<RepoSource>)
}

/**
 * 把「模块仓库源」配置（地址、名称、开关）备成一个 JSON 归档。
 *
 * 与模块/boot 不同，这里没有用户可勾的条目：所有源打包成**一份**归档，内容确定性
 * （不带时间戳）让引擎的去重规则自然生效——源没变时重复备份只记 skipped，不产生新条目。
 * 恢复走整批替换：归档里的列表就是恢复后的全部源，逐条合并会把已删除的源又长回来。
 */
class RepoConfigBackupSource(
    private val reader: RepoConfigReader,
    private val writer: RepoConfigWriter,
    private val clock: () -> Instant = { Instant.now() },
) : BackupSource {

    override val kind: BackupKind = BackupKind.REPO_CONFIG

    override suspend fun export(selected: Set<String>?): Result<ExportOutcome> = runCatchingCancellable {
        // selected 对这份源没有意义（没有可勾的条目），忽略——与 boot 同一约定。
        val sources = reader.load()
        // 一个源都没配过就没有"配置"可备份：交空结果，UI 按"没有新东西可存"处理，
        // 而不是给用户一条空 JSON 的备份记录。
        if (sources.isEmpty()) return@runCatchingCancellable ExportOutcome()
        val json = render(sources).toByteArray(Charsets.UTF_8)
        val fileName = ArchiveNaming.repoConfigArchiveName(ArchiveNaming.timestamp(clock()))
        ExportOutcome(
            listOf(
                BackupArtifact(
                    kind = kind,
                    entryId = ENTRY_ID,
                    fileName = fileName,
                    metaFileName = null,
                    sizeBytes = json.size.toLong(),
                    sha256 = ByteArrayInputStream(json).use { it.sha256Hex() },
                    metaJson = null,
                    openContent = { ByteArrayInputStream(json) },
                ),
            ),
        )
    }

    override suspend fun restore(
        entry: BackupEntry,
        metaJson: String?,
        content: InputStream,
    ): Result<RestoreOutcome> = runCatchingCancellable {
        val text = content.use { it.readBytes().toString(Charsets.UTF_8) }
        val sources = runCatching { parse(text) }.getOrElse { error ->
            return@runCatchingCancellable RestoreOutcome(false, BackupReason.RepoConfigInvalid(error.message))
        }
        // 一个 url 都没有的"配置"多半是损坏或被改过的归档：整批替换会把用户现有的源清空，
        // 这种归档宁可拒绝也不应用。
        if (sources.isEmpty() || sources.any { it.url.isBlank() }) {
            return@runCatchingCancellable RestoreOutcome(false, BackupReason.RepoConfigInvalid(null))
        }
        writer.applyAll(sources)
        RestoreOutcome(true)
    }

    companion object {
        const val ENTRY_ID = "repo-config"

        /**
         * 归档格式（与 prefs 里 `repo_sources` 的字段同名同义，方便互相对照）：
         * `{"schema":"sukisu.repo-config.backup","version":1,"sources":[{id,name,url,enabled}]}`
         *
         * 不写时间戳：内容确定才能靠 sha256 去重，源没变时重复备份不产生新条目。
         */
        fun render(sources: List<RepoSource>): String {
            val array = JSONArray()
            sources.forEach { source ->
                array.put(
                    JSONObject()
                        .put("id", source.id)
                        .put("name", source.name)
                        .put("url", source.url)
                        .put("enabled", source.enabled),
                )
            }
            return JSONObject()
                .put("schema", SCHEMA)
                .put("version", 1)
                .put("sources", array)
                .toString(2)
        }

        /** 结构不对就抛：恢复侧把异常折成 [BackupReason.RepoConfigInvalid]。 */
        fun parse(text: String): List<RepoSource> {
            val root = JSONObject(text)
            val array = root.getJSONArray("sources")
            return List(array.length()) { i ->
                val item = array.getJSONObject(i)
                RepoSource(
                    id = item.optString("id").ifBlank { "repo-$i" },
                    name = item.optString("name"),
                    url = item.getString("url"),
                    enabled = item.optBoolean("enabled", true),
                )
            }
        }

        private const val SCHEMA = "sukisu.repo-config.backup"
    }
}
