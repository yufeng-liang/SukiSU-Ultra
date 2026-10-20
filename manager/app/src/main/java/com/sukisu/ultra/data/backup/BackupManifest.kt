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
        // 版本认不出就整个拒绝，不做"尽力解析"：这个索引是"读全量 → 改 → 整体回写"的，版本不认识
        // 通常意味着有些字段我们读不懂，照旧解析会把读不懂的条目当成不存在，回写时从索引里抹掉
        // （归档还在盘上/远端，但列表、去重、保留策略都再也看不到它们）。静默删数据远比报错难查。
        val version = root.optInt("version", -1)
        if (version != ArchiveNaming.MANIFEST_VERSION) {
            throw BackupReasonException(
                BackupReason.IndexUnreadable(
                    "manifest version $version is not ${ArchiveNaming.MANIFEST_VERSION}",
                ),
            )
        }
        val array = root.optJSONArray("entries")
            ?: throw BackupReasonException(BackupReason.IndexUnreadable(null))
        return (0 until array.length()).map { index -> parseEntryOrThrow(array, index) }
    }

    /**
     * 单条也严格：老写法用 `mapNotNull` 把认不出的条目静默跳过，整体回写时那几条就被从索引里
     * 抹掉了。宁可整次拒绝，让用户看到"索引读不出来"，也不要看起来一切正常地少掉几条记录。
     */
    private fun parseEntryOrThrow(array: JSONArray, index: Int): BackupEntry {
        val item = array.optJSONObject(index)
            ?: throw BackupReasonException(BackupReason.IndexUnreadable("entry $index is not an object"))
        val rawKind = item.optString("kind")
        val kind = BackupKind.entries.firstOrNull { it.wireName == rawKind }
            ?: throw BackupReasonException(BackupReason.IndexUnreadable("entry $index has unknown kind '$rawKind'"))
        val fileName = item.optString("fileName")
        if (fileName.isBlank()) {
            throw BackupReasonException(BackupReason.IndexUnreadable("entry $index has no fileName"))
        }
        return BackupEntry(
            kind = kind,
            entryId = item.optString("entryId"),
            fileName = fileName,
            metaFileName = optStringOrNull(item, "metaFileName"),
            sizeBytes = item.optLong("sizeBytes"),
            sha256 = item.optString("sha256"),
            createdAt = item.optString("createdAt"),
        )
    }

    /**
     * 读一个「可有可无」的字符串字段。
     *
     * [JSONObject.optString] 的两个坑都在这里拦：键不存在返回空串；键**存在但值是 JSON null**
     * 返回字面量 `"null"`——手写或别处写出的 meta 里这是合法内容。两处都归一成 Kotlin null。
     */
    private fun optStringOrNull(item: JSONObject, key: String): String? =
        item.optString(key).takeIf { it.isNotBlank() && it != "null" }

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

    fun renderPartitionMeta(meta: PartitionSnapshotMeta): String = JSONObject().apply {
        put("schema", ArchiveNaming.PARTITION_SCHEMA)
        put("version", ArchiveNaming.MANIFEST_VERSION)
        put("entryId", meta.entryId)
        // partition 是恢复时唯一的分区来源，必须写全（含槽位）。
        put("partition", meta.partition)
        put("sourceDevice", meta.sourceDevice)
        put("partitionSizeBytes", meta.partitionSizeBytes)
        put("compressed", meta.compressed)
        put("createdAt", meta.createdAt)
        // 拍不到（shell 失败）就不写这个键：缺字段和空串是两回事——解析侧以「有没有」区分
        // 新旧快照，写个空串进去反而让旧 meta 看起来带了版本。
        meta.kernelRelease?.takeIf { it.isNotBlank() }?.let { put("kernelRelease", it) }
    }.toString(2)

    /**
     * 解析分区快照的边车 meta。
     *
     * 与模块 meta 不同，这里**不做容错**：分区名缺失时调用方必须拿到 null 去拒绝恢复。
     * 给一个默认值（比如 "boot"）意味着蒙着眼睛往某个分区刷镜像。
     *
     * 但**形状校验不在这里做**：分区名不合法（比如被人塞进 `boot_a; reboot`）是一个明确的
     * "这个名字不能用"，恢复链要能报出 [BackupReason.PartitionNameUnusable]；若在这里就返回
     * null，上层只能看到"没有 meta"，提示会误导用户去重新备份，而真正的问题是 meta 被人改过。
     */
    fun parsePartitionMeta(json: String): PartitionSnapshotMeta? = runCatching {
        val item = JSONObject(json)
        // 认不出的 schema/version 一律拒绝：optXxx 的默认值（0 / false）会把"结构变了"
        // 悄悄解释成"压缩没开"，进而拿压缩数据去比长度。与"不做容错"的原则保持一致。
        if (item.optString("schema") != ArchiveNaming.PARTITION_SCHEMA) return null
        if (item.optInt("version") != ArchiveNaming.MANIFEST_VERSION) return null
        val partition = item.optString("partition")
        if (partition.isBlank()) return null
        PartitionSnapshotMeta(
            entryId = item.optString("entryId"),
            partition = partition,
            sourceDevice = item.optString("sourceDevice"),
            partitionSizeBytes = item.optLong("partitionSizeBytes"),
            compressed = item.optBoolean("compressed"),
            createdAt = item.optString("createdAt"),
            // 旧快照没有这个键、或显式写了 null——optString 的两种坑由 [optStringOrNull]
            // 一并拦下：KMI 未知时恢复确认走普通文案，不弹警告。
            kernelRelease = optStringOrNull(item, "kernelRelease"),
        )
    }.getOrNull()
}
