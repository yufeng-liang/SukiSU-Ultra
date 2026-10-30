package com.sukisu.ultra.data.repository

import com.sukisu.ultra.data.model.Author
import com.sukisu.ultra.data.model.MmrlModuleInfo
import com.sukisu.ultra.data.model.MmrlVersion
import com.sukisu.ultra.data.model.RepoModule
import com.sukisu.ultra.data.model.RegistryModuleInfo
import com.sukisu.ultra.data.model.ReleaseAsset
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class RepoFormat {
    KERNELSU,
    KERNELSU_V1,
    MMRL,
}

/**
 * Parses the module repository index formats:
 *  - KERNELSU: a top-level JSON array of modules (legacy modules.kernelsu.org format)
 *  - KERNELSU_V1: a top-level JSON object with a "modules" array whose entries carry a `stable`
 *    version and a `detail` document (the registry at modules.kernelsu.org)
 *  - MMRL: a top-level JSON object with a "modules" array (MMRL repository format)
 */
object RepoModuleParser {

    private val ISO_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun sniffFormat(body: String): RepoFormat? {
        val trimmed = body.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return when {
            trimmed.startsWith("[") -> RepoFormat.KERNELSU
            !trimmed.startsWith("{") -> null
            !trimmed.contains("\"modules\"") -> null
            isRegistryV1(trimmed) -> RepoFormat.KERNELSU_V1
            else -> RepoFormat.MMRL
        }
    }

    /**
     * Whether an object that mentions `modules` is a registry index rather than an MMRL one.
     *
     * The two are told apart by the fields MMRL does not have: a `schemaVersion` on the document
     * (present on both the registry's list and its pointer document) or a `detail` path on the
     * entries. An MMRL entry carries its versions inline instead.
     */
    private fun isRegistryV1(trimmed: String): Boolean = runCatching {
        val root = JSONObject(trimmed)
        if (root.opt("schemaVersion") is Number) return@runCatching true
        val modules = root.optJSONArray("modules") ?: return@runCatching false
        val first = modules.optJSONObject(0) ?: return@runCatching false
        first.has("detail") || first.has("stable")
    }.getOrDefault(false)

    /**
     * The module list a registry index document points at, or null when [body] is not one.
     *
     * `api/v1/index.json` carries the registry's build stamp and the name of the file that holds
     * the modules instead of the modules themselves. The path is relative to the site root.
     */
    fun registryIndexPointer(body: String): String? = runCatching {
        val modules = JSONObject(body).opt("modules")
        // 只认字符串：MMRL 文档的 modules 是数组，optString 会把数组序列化成文本交回来。
        (modules as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun parse(
        body: String,
        sourceId: String,
        sourceName: String,
        indexUrl: String = "",
    ): List<RepoModule> {
        return when (sniffFormat(body)) {
            RepoFormat.KERNELSU -> parseKsu(body, sourceId, sourceName)
            RepoFormat.KERNELSU_V1 -> parseKsuV1(body, sourceId, sourceName, indexUrl)
            RepoFormat.MMRL -> parseMmrl(body, sourceId, sourceName, indexUrl)
            null -> throw IllegalArgumentException("Unrecognized repository format")
        }
    }

    fun parseKsu(body: String, sourceId: String = "", sourceName: String = ""): List<RepoModule> {
        val json = JSONArray(body)
        return (0 until json.length()).mapNotNull { idx ->
            val item = json.optJSONObject(idx) ?: return@mapNotNull null
            parseKsuModule(item, sourceId, sourceName)
        }
    }

    fun parseMmrl(
        body: String,
        sourceId: String,
        sourceName: String,
        indexUrl: String = "",
    ): List<RepoModule> {
        val root = JSONObject(body)
        val modules = root.optJSONArray("modules") ?: return emptyList()
        return (0 until modules.length()).mapNotNull { idx ->
            val item = modules.optJSONObject(idx) ?: return@mapNotNull null
            parseMmrlModule(item, sourceId, sourceName, indexUrl)
        }
    }

    fun parseKsuV1(
        body: String,
        sourceId: String = "",
        sourceName: String = "",
        indexUrl: String = "",
    ): List<RepoModule> {
        val root = JSONObject(body)
        val modules = root.optJSONArray("modules") ?: return emptyList()
        val baseUrl = registryBaseUrl(indexUrl)
        return (0 until modules.length()).mapNotNull { idx ->
            val item = modules.optJSONObject(idx) ?: return@mapNotNull null
            parseKsuV1Module(item, sourceId, sourceName, baseUrl)
        }
    }

    /**
     * A registry entry carries its newest version and nothing else; its releases are read from
     * [RegistryModuleInfo.detailUrl] when the module is opened, which is why the asset is left
     * unset here.
     */
    private fun parseKsuV1Module(
        item: JSONObject,
        sourceId: String,
        sourceName: String,
        baseUrl: String?,
    ): RepoModule? {
        val moduleId = item.optString("id", "").trim()
        if (moduleId.isEmpty()) return null
        val authorsArray = item.optJSONArray("authors")
        val authorList = if (authorsArray != null) {
            (0 until authorsArray.length()).mapNotNull { idx ->
                val authorObj = authorsArray.optJSONObject(idx) ?: return@mapNotNull null
                val name = authorObj.optString("name", "").trim()
                if (name.isEmpty()) null else Author(name = name, link = authorObj.optString("url", "").trim())
            }
        } else {
            emptyList()
        }
        val stable = item.optJSONObject("stable")
        val detailPath = item.optString("detail", "").trim()
        // A registry's paths are relative to its site root, so without one there is nothing to
        // resolve the document against.
        val registry = if (detailPath.isNotEmpty() && baseUrl != null) {
            RegistryModuleInfo(baseUrl = baseUrl, detailUrl = registryDocumentUrl(baseUrl, detailPath))
        } else {
            null
        }

        return RepoModule(
            moduleId = moduleId,
            moduleName = item.optString("name", "").ifBlank { moduleId },
            authors = authorList.joinToString(", ") { it.name },
            authorList = authorList,
            summary = item.optString("summary", ""),
            metamodule = item.optBoolean("metamodule", false),
            stargazerCount = item.optInt("stars", 0),
            updatedAt = item.optString("updatedAt", ""),
            createdAt = "",
            latestRelease = stable?.optString("version", "").orEmpty(),
            latestReleaseTime = stable?.optString("publishedAt", "").orEmpty(),
            latestVersionCode = stable?.let { optLongValue(it, "versionCode") } ?: 0L,
            latestAsset = null,
            sourceId = sourceId,
            sourceName = sourceName,
            registry = registry,
        )
    }

    private fun parseKsuModule(item: JSONObject, sourceId: String, sourceName: String): RepoModule? {
        val moduleId = item.optString("moduleId", "")
        if (moduleId.isEmpty()) return null
        val moduleName = item.optString("moduleName", "")
        val authorsArray = item.optJSONArray("authors")
        val authorList = if (authorsArray != null) {
            (0 until authorsArray.length())
                .mapNotNull { idx ->
                    val authorObj = authorsArray.optJSONObject(idx) ?: return@mapNotNull null
                    val name = authorObj.optString("name", "").trim()
                    var link = authorObj.optString("link", "").trim()
                    if (link.startsWith("`") && link.endsWith("`") && link.length >= 2) {
                        link = link.substring(1, link.length - 1)
                    }
                    if (name.isEmpty()) null else Author(name = name, link = link)
                }
        } else {
            emptyList()
        }
        val authors = if (authorList.isNotEmpty()) authorList.joinToString(", ") { it.name } else item.optString("authors", "")
        val summary = item.optString("summary", "")
        val metamodule = item.optBoolean("metamodule", false)
        val zygisk = item.optBoolean("zygisk", false)
        val stargazerCount = item.optInt("stargazerCount", 0)
        val updatedAt = item.optString("updatedAt", "")
        val createdAt = item.optString("createdAt", "")

        var latestRelease = ""
        var latestReleaseTime = ""
        var latestVersionCode = 0L
        var latestAsset: ReleaseAsset? = null
        val lr = item.optJSONObject("latestRelease")
        if (lr != null) {
            val lrName = lr.optString("name", lr.optString("version", ""))
            val lrTime = lr.optString("time", "")
            val lrUrl = stripTicks(lr.optString("downloadUrl", "")).trim()

            latestVersionCode = optLongValue(lr, "versionCode")
            latestRelease = lrName
            latestReleaseTime = lrTime
            if (lrUrl.isNotEmpty()) {
                val fileName = lrUrl.substringAfterLast('/')
                latestAsset = ReleaseAsset(name = fileName, downloadUrl = lrUrl, size = 0L)
            }
        }

        return RepoModule(
            moduleId = moduleId,
            moduleName = moduleName,
            authors = authors,
            authorList = authorList,
            summary = summary,
            metamodule = metamodule,
            zygisk = zygisk,
            stargazerCount = stargazerCount,
            updatedAt = updatedAt,
            createdAt = createdAt,
            latestRelease = latestRelease,
            latestReleaseTime = latestReleaseTime,
            latestVersionCode = latestVersionCode,
            latestAsset = latestAsset,
            sourceId = sourceId,
            sourceName = sourceName,
        )
    }

    private fun parseMmrlModule(
        item: JSONObject,
        sourceId: String,
        sourceName: String,
        indexUrl: String,
    ): RepoModule? {
        val moduleId = item.optString("id", "").trim()
        if (moduleId.isEmpty()) return null
        val moduleName = item.optString("name", "")
        val summary = item.optString("description", "")
        val author = item.optString("author", "").trim()

        val versionsJson = item.optJSONArray("versions") ?: JSONArray()
        val versions = (0 until versionsJson.length()).mapNotNull { idx ->
            val v = versionsJson.optJSONObject(idx) ?: return@mapNotNull null
            val zipUrl = stripTicks(v.optString("zipUrl", "")).trim()
            if (zipUrl.isEmpty()) return@mapNotNull null
            val timestamp = optSecondsValue(v, "timestamp")
            MmrlVersion(
                version = v.optString("version", ""),
                versionCode = optLongValue(v, "versionCode"),
                timestamp = timestamp,
                time = epochToIso(timestamp),
                zipUrl = zipUrl,
                zipUrlFallback = sameOriginAssetFallback(zipUrl, indexUrl),
                changelogUrl = stripTicks(v.optString("changelog", "")).trim().ifEmpty { null },
                size = optLongValue(v, "size"),
            )
        }.sortedWith(compareByDescending<MmrlVersion> { it.versionCode }.thenByDescending { it.timestamp })

        val latest = versions.firstOrNull()
        val latestTime = epochToIso(latest?.timestamp ?: 0L).ifEmpty {
            epochToIso(optSecondsValue(item, "timestamp"))
        }

        return RepoModule(
            moduleId = moduleId,
            moduleName = moduleName,
            authors = author,
            authorList = if (author.isEmpty()) emptyList() else listOf(Author(name = author, link = "")),
            summary = summary,
            metamodule = false,
            zygisk = false,
            stargazerCount = 0,
            updatedAt = latestTime,
            createdAt = "",
            latestRelease = latest?.version ?: "",
            latestReleaseTime = latestTime,
            latestVersionCode = latest?.versionCode ?: 0L,
            latestAsset = latest?.let {
                ReleaseAsset(name = it.zipUrl.substringAfterLast('/'), downloadUrl = it.zipUrl, size = it.size)
            },
            sourceId = sourceId,
            sourceName = sourceName,
            // 酷安社区索引额外带的出处四字段；其它 MMRL 仓库没有这些键，读完就是空。
            source = stripTicks(item.optString("source", "")).trim(),
            uploader = stripTicks(item.optString("uploader", "")).trim(),
            coolapkUid = stripTicks(item.optString("coolapkUid", "")).trim(),
            // 索引可能给毫秒也可能给秒；这里只做数值化，单位由展示侧按数量级判断。
            submittedAt = optSecondsValue(item, "submittedAt"),
            mmrl = MmrlModuleInfo(
                readmeUrl = stripTicks(item.optString("readme", "")).trim().ifEmpty { null },
                supportUrl = stripTicks(item.optString("support", "")).trim().ifEmpty { null },
                versions = versions,
            ),
        )
    }

    private fun stripTicks(s: String): String {
        val t = s.trim()
        return if (t.startsWith("`") && t.endsWith("`") && t.length >= 2) t.substring(1, t.length - 1) else t
    }

    private fun optLongValue(obj: JSONObject, key: String): Long {
        val value = obj.opt(key) ?: return 0L
        return when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    private fun optSecondsValue(obj: JSONObject, key: String): Long {
        val value = obj.opt(key) ?: return 0L
        return when (value) {
            is Number -> value.toDouble().toLong()
            is String -> value.trim().toDoubleOrNull()?.toLong() ?: 0L
            else -> 0L
        }
    }

    private fun epochToIso(seconds: Long): String {
        if (seconds <= 0L) return ""
        return ISO_FORMAT.format(Instant.ofEpochSecond(seconds))
    }
}

/**
 * The site root of [url] — scheme, host and port, with a trailing slash — or null when it is not
 * an http(s) address with a host.
 *
 * A registry document names its own paths relative to the site root (`api/v1/modules/X.json`)
 * rather than to the document that named them, so the root has to be kept and not the directory.
 */
internal fun registryBaseUrl(url: String): String? {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    val scheme = uri.scheme?.takeIf { it.equals("https", true) || it.equals("http", true) } ?: return null
    val host = uri.host ?: return null
    val port = if (uri.port == -1) "" else ":${uri.port}"
    return "$scheme://$host$port/"
}

/** Resolves a site-root-relative [path] from a registry against its [baseUrl]. */
internal fun registryDocumentUrl(baseUrl: String, path: String): String =
    baseUrl + path.removePrefix("/")
