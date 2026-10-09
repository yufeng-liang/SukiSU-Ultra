package com.sukisu.ultra.data.backup

import org.json.JSONArray
import org.json.JSONObject

object BackupManifest {

    fun renderEntries(entries: List<BackupEntry>): String {
        val root = JSONObject()
        root.put("schema", ArchiveNaming.MANIFEST_SCHEMA)
        root.put("version", ArchiveNaming.MANIFEST_VERSION)
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("kind", entry.kind.wireName)
                    put("entryId", entry.entryId)
                    put("fileName", entry.fileName)
                    put("metaFileName", entry.metaFileName ?: JSONObject.NULL)
                    put("sizeBytes", entry.sizeBytes)
                    put("sha256", entry.sha256)
                    put("createdAt", entry.createdAt)
                }
            )
        }
        root.put("entries", array)
        return root.toString(2)
    }

    /** 任何解析失败都返回空列表：坏掉的 manifest 不应让备份列表整页崩掉。 */
    fun parseEntries(json: String): List<BackupEntry> =
        runCatching { parseEntriesOrThrow(json) }.getOrDefault(emptyList())

    /**
     * 严格版本：结构不对就抛。
     *
     * 引擎必须能把"解析不了"和"还没有索引"分开——把坏掉的索引当成空索引整体写回去，
     * 等于把用户的备份列表清空（归档还在盘上，但列表、去重、保留策略都看不到它们）。
     */
    fun parseEntriesOrThrow(json: String): List<BackupEntry> {
        val root = runCatching { JSONObject(json) }
            .getOrElse { error -> throw BackupReasonException(BackupReason.IndexUnreadable(error.message)) }
        val array = root.optJSONArray("entries")
            ?: throw BackupReasonException(BackupReason.IndexUnreadable(null))
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val kind = BackupKind.entries.firstOrNull { it.wireName == item.optString("kind") }
                ?: return@mapNotNull null
            BackupEntry(
                kind = kind,
                entryId = item.optString("entryId"),
                fileName = item.optString("fileName"),
                metaFileName = item.optString("metaFileName").takeIf { it.isNotBlank() && it != "null" },
                sizeBytes = item.optLong("sizeBytes"),
                sha256 = item.optString("sha256"),
                createdAt = item.optString("createdAt"),
            )
        }
    }

    fun renderModuleMeta(meta: ModuleBackupMeta): String = JSONObject().apply {
        put("schema", ArchiveNaming.MODULE_SCHEMA)
        put("version", ArchiveNaming.MANIFEST_VERSION)
        put("entryId", meta.entryId)
        put("name", meta.name)
        put("versionName", meta.versionName)
        put("versionCode", meta.versionCode)
        put("author", meta.author)
        put("metamodule", meta.metamodule)
        put("disabled", meta.disabled)
        put("sourceDevice", meta.sourceDevice)
        put("createdAt", meta.createdAt)
    }.toString(2)

    fun parseModuleMeta(json: String): ModuleBackupMeta {
        val item = JSONObject(json)
        return ModuleBackupMeta(
            entryId = item.optString("entryId"),
            name = item.optString("name"),
            versionName = item.optString("versionName"),
            versionCode = item.optLong("versionCode"),
            author = item.optString("author"),
            metamodule = item.optBoolean("metamodule"),
            disabled = item.optBoolean("disabled"),
            sourceDevice = item.optString("sourceDevice"),
            createdAt = item.optString("createdAt"),
        )
    }
}
