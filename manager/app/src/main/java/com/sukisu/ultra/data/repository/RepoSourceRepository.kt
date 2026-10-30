package com.sukisu.ultra.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.sukisu.ultra.data.model.RepoModule
import com.sukisu.ultra.ksuApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class RepoSource(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
)

interface RepoSourceRepository {
    fun loadSources(): List<RepoSource>

    /**
     * Probes [rawUrl] candidate endpoints, persists the first reachable module
     * index and returns it. Fails with a user-displayable message otherwise.
     */
    suspend fun addSource(rawUrl: String, name: String? = null): Result<RepoSource>

    fun removeSource(id: String)
    fun setSourceEnabled(id: String, enabled: Boolean)
    fun renameSource(id: String, name: String)

    /**
     * 用 [sources] 整批替换当前的仓库源列表（备份恢复用）。
     *
     * 语义是"替换"而不是"合并"：恢复的是一份完整快照，逐条合并会把已经删掉的源又长回来。
     * id 沿用备份里的值——它只是 prefs 里的主键，跨设备/跨安装都不需要换新。
     */
    fun replaceSources(sources: List<RepoSource>)

    /**
     * 把内置的「酷安社区模块」源放回列表（用户之前删掉了它）。
     *
     * 这个源只在首次读列表时种一次，删掉之后不会自己长回来（见 [RepoSourceRepositoryImpl]
     * 里的种子标记）。恢复时保持 `enabled = false`：商店页入口卡的说明与两次确认才是开启它的
     * 地方，这个入口只负责把源摆回列表，不替用户做「开启」的决定。
     *
     * 列表里已经有这一条时什么都不做——重复恢复不该写出第二份。
     */
    fun restoreCoolapkSource()
}

class RepoSourceRepositoryImpl(
    private val prefs: SharedPreferences = ksuApp.getSharedPreferences("settings", Context.MODE_PRIVATE),
    private val httpClient: OkHttpClient = ksuApp.okhttpClient,
) : RepoSourceRepository {

    companion object {
        private const val KEY_SOURCES = "repo_sources"
        /** 酷安社区模块源是否已经种过：种过一次就不再生，用户删掉它不会自己长回来。 */
        private const val KEY_COOLAPK_SEEDED = "repo_coolapk_source_seeded"
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 20L
    }

    /**
     * 一个源都没配过时（prefs 里没有这个键）给的就是官方注册表。
     *
     * 这个位置原本是空的，理由是老 `modules.kernelsu.org` 随组织被封一起消失了，预置它只会让
     * 每一次首次启动都报一句抓取失败。它 2026-10-10 由新组织重新上线、换了这套管理器读得懂的
     * 格式（见 [RepoFormat.KERNELSU_V1]），理由也就没了：官方源在列表里什么都不做的话，用户
     * 第一次进模块仓库看到的是一片空白，还得自己去候选列表里找。
     *
     * 只在"从没配过"时给。用户把它删掉之后 prefs 里存的是空数组（键在），所以不会长回来；
     * 用户自己的那份配置也永远不会被它盖住。
     */
    override fun loadSources(): List<RepoSource> {
        val raw = prefs.getString(KEY_SOURCES, null)
        val sources = if (raw == null) {
            listOf(builtInSource)
        } else {
            runCatching { decode(raw) }.getOrNull() ?: emptyList()
        }
        return seedCoolapkCommunitySource(sources)
    }

    /**
     * 第一次读到源列表时把「酷安社区模块」种进去，默认关闭。
     *
     * 只种一次（写一个标记）：用户后来手动把它删掉，列表里就不该再长回来——长回来会让
     * 「删除」看起来没生效。默认关闭是刻意的：这份索引里的模块由酷安用户自发上传，没有人工
     * 安全审核，要用户在商店页读过免责说明、自己点开才可见（见 [coolapkCommunitySource]）。
     */
    private fun seedCoolapkCommunitySource(sources: List<RepoSource>): List<RepoSource> {
        if (prefs.getBoolean(KEY_COOLAPK_SEEDED, false)) return sources
        prefs.edit().putBoolean(KEY_COOLAPK_SEEDED, true).apply()
        if (sources.any { it.id == coolapkCommunitySource.id }) return sources
        val seeded = sources + coolapkCommunitySource
        save(seeded)
        return seeded
    }

    override suspend fun addSource(rawUrl: String, name: String?): Result<RepoSource> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = rawUrl.trim()
            require(trimmed.isNotEmpty()) { ksuApp.getString(com.sukisu.ultra.R.string.module_repo_source_bad_url) }
            val candidates = candidateSourceUrls(trimmed)
            require(candidates.isNotEmpty()) { ksuApp.getString(com.sukisu.ultra.R.string.module_repo_source_bad_url) }

            val existing = loadSources()
            require(existing.none { source -> isSameSource(source.url, trimmed) }) {
                ksuApp.getString(com.sukisu.ultra.R.string.module_repo_source_duplicate)
            }

            // connect/read 超时只管单次连接和单次读：一个每 19 秒吐一个字节的源永远不会触发
            // readTimeout，探测（以及后面的列表刷新）就会被它无限期占住。callTimeout 给整个
            // 调用封顶，慢源到点即失败。
            val client = httpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .readTimeout(java.time.Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                .callTimeout(java.time.Duration.ofSeconds(INDEX_CALL_TIMEOUT_SECONDS))
                .build()

            var resolvedUrl: String? = null
            var lastError: String = ""
            for (candidate in candidates) {
                try {
                    // 只嗅首字符不算数：畸形 JSON、凑巧带 "modules" 的任意 JSON 都会被当成可用
                    // 仓库落盘，而列表里一条也解析不出来。这里真解析一遍——复用列表用的同一个
                    // 入口，判决才和用户看到的结果一致。解析通过就算仓库，空索引（[]、
                    // modules: []）也是合法仓库。注册表索引只指向模块清单、本身没有模块，所以
                    // 由这个入口替它走完那一跳，否则指向 modules.kernelsu.org 的地址会因为
                    // "解析不出模块"被当成坏地址。
                    fetchIndexModules(
                        client = client,
                        indexUrl = candidate,
                        sourceId = "",
                        sourceName = "",
                    )
                    resolvedUrl = candidate
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                }
                if (resolvedUrl != null) break
            }

            val url = resolvedUrl
            if (url == null) {
                throw IllegalStateException(
                    ksuApp.getString(com.sukisu.ultra.R.string.module_repo_source_unreachable) + if (lastError.isEmpty()) "" else " ($lastError)"
                )
            }

            val source = RepoSource(
                id = UUID.randomUUID().toString(),
                name = (name?.takeIf { it.isNotBlank() } ?: defaultSourceName(url)),
                url = url,
            )
            // 上面这一串探测最长能等 35 秒，用户完全可能在等的时候删掉一个源、或者改个名字。
            // 用探测前读到的快照落盘，就等于把窗口里的每一次改动抹掉——那不是"加了个源"，
            // 是把用户刚做的事回滚了。所以落盘时重读一次最新列表，只把自己并进去。
            var persisted = source
            mutate { latest ->
                val already = latest.firstOrNull { existing -> isSameSource(existing.url, url) }
                if (already != null) {
                    // 窗口里正好也有人加了同一个源：留着最早那条，别写第二份。
                    persisted = already
                    latest
                } else {
                    latest + source
                }
            }
            persisted
        }
    }

    override fun removeSource(id: String) = mutate { it.filterNot { s -> s.id == id } }
    override fun setSourceEnabled(id: String, enabled: Boolean) =
        mutate { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }

    override fun renameSource(id: String, name: String) =
        mutate { list -> list.map { if (it.id == id) it.copy(name = name) else it } }

    override fun replaceSources(sources: List<RepoSource>) = save(sources)

    override fun restoreCoolapkSource() = mutate { sources ->
        // 用固定 id 的 [coolapkCommunitySource] 原样追加，不走 addSource：那条路会现探网络、
        // 生成随机 id、并把 enabled 留成 true——随机 id 找不回入口卡，enabled = true 又等于
        // 替用户点了「开启」。
        if (sources.any { it.id == COOLAPK_COMMUNITY_SOURCE_ID }) {
            sources
        } else {
            sources + coolapkCommunitySource
        }
    }

    private fun mutate(transform: (List<RepoSource>) -> List<RepoSource>) {
        save(transform(loadSources()))
    }

    private fun save(sources: List<RepoSource>) {
        val array = JSONArray()
        sources.forEach { source ->
            array.put(
                JSONObject()
                    .put("id", source.id)
                    .put("name", source.name)
                    .put("url", source.url)
                    .put("enabled", source.enabled)
            )
        }
        prefs.edit().putString(KEY_SOURCES, array.toString()).apply()
    }

    private fun decode(raw: String): List<RepoSource> {
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { idx ->
            val item = array.optJSONObject(idx) ?: return@mapNotNull null
            val url = item.optString("url", "").trim()
            val id = item.optString("id", "").ifBlank { UUID.randomUUID().toString() }
            if (url.isEmpty()) return@mapNotNull null
            RepoSource(
                id = id,
                name = item.optString("name", "").ifBlank { defaultSourceName(url) },
                url = url,
                enabled = item.optBoolean("enabled", true),
            )
        }
    }
}

/**
 * Whether [inputUrl] and [existingUrl] address the same module index, so that the same repository
 * cannot be configured twice.
 *
 * Two addresses are the same when one of them resolves to the other's candidates, and — for
 * GitHub-hosted indexes — when they are the same file of the same repository reached by a
 * different route: the Pages address, the raw address and the jsDelivr mirror of one repository
 * are one source, not three.
 */
internal fun isSameSource(existingUrl: String, inputUrl: String): Boolean {
    val existing = (candidateSourceUrls(existingUrl) + existingUrl).map { it.normalizedSourceUrl() }
    val input = (candidateSourceUrls(inputUrl) + inputUrl).map { it.normalizedSourceUrl() }.toSet()
    if (existing.any { it in input }) return true

    val inputIdentity = indexIdentity(inputUrl)
    return inputIdentity != null && inputIdentity == indexIdentity(existingUrl)
}

private fun String.normalizedSourceUrl(): String = trim().trimEnd('/').lowercase()

/**
 * The identity of a GitHub-hosted index file: `github:<owner>/<repo>/<path>`, with the host and
 * the branch left out, so that the same file reached through GitHub Pages, the raw host or
 * jsDelivr compares equal. Null when the address is not a GitHub index file, where no such
 * equivalence is known.
 */
internal fun indexIdentity(url: String): String? {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    val segments = uri.path.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }

    val slug: String
    val path: List<String>
    when {
        host == "raw.githubusercontent.com" && segments.size >= 3 -> {
            // owner/repo/<ref>/<path>
            slug = "${segments[0]}/${segments[1]}"
            path = segments.drop(3)
        }

        host == "github.com" && segments.size >= 2 -> {
            slug = "${segments[0]}/${segments[1].removeSuffix(".git")}"
            path = if (segments.size >= 4 && segments[2] in REF_SEGMENTS) segments.drop(4) else segments.drop(2)
        }

        host.endsWith(".github.io") && segments.size >= 2 -> {
            slug = "${host.removeSuffix(".github.io")}/${segments[0]}"
            path = segments.drop(1)
        }

        host == "cdn.jsdelivr.net" && segments.size >= 3 && segments[0] == "gh" -> {
            slug = "${segments[1]}/${segments[2].substringBefore('@')}"
            path = segments.drop(3)
        }

        else -> return null
    }

    if (path.isEmpty() || !path.last().endsWith(".json")) return null
    return "github:${slug.lowercase()}/${path.joinToString("/").lowercase()}"
}

/**
 * Builds the candidate module index URLs for a user-provided address:
 *  - a GitHub repository address is tried on its GitHub Pages site first, because that is where
 *    such a repository publishes both its index and its module zips, then inside the repository
 *    itself through the raw file host, and last through the jsDelivr mirror, for networks that
 *    cannot reach raw.githubusercontent.com
 *  - a URL already pointing at a .json file is used as-is
 *  - anything else tries the MMRL convention (<base>/json/modules.json) first, then the KernelSU
 *    convention (<base>/modules.json), then the registry convention
 *    (<base>/api/v1/index.json, and the module list beside it), so that pasting the address of a
 *    registry site resolves to the document the manager can read
 *
 * `HEAD` is GitHub's alias for the repository's default branch, so neither `main` nor `master` has
 * to be guessed.
 */
fun candidateSourceUrls(raw: String): List<String> {
    val trimmed = raw.trim().trimEnd('/')
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return emptyList()

    // A repository page is recognised before the .json rule below: the address of a file on
    // github.com is an HTML page, not the file, so it has to be resolved through the raw host.
    val repo = githubRepoAddress(trimmed)
        ?: return if (trimmed.endsWith(".json")) {
            listOf(trimmed)
        } else {
            listOf(
                "$trimmed/json/modules.json",
                "$trimmed/modules.json",
                "$trimmed/api/v1/index.json",
                "$trimmed/api/v1/modules.json",
            )
        }

    val ref = repo.ref ?: "HEAD"
    val slug = "${repo.owner}/${repo.repo}"
    val pages = "https://${repo.owner.lowercase()}.github.io/${repo.repo}"
    val pagesCandidates = listOf("$pages/json/modules.json", "$pages/modules.json")
    val rawCandidates = listOf(
        "https://raw.githubusercontent.com/$slug/$ref/json/modules.json",
        "https://raw.githubusercontent.com/$slug/$ref/modules.json",
    )
    val mirrorCandidates = listOf(
        "https://cdn.jsdelivr.net/gh/$slug@$ref/json/modules.json",
        "https://cdn.jsdelivr.net/gh/$slug@$ref/modules.json",
    )
    // GitHub Pages publishes the default branch only, so an address that names a branch has to be
    // read from the raw host to get the branch it asked for.
    return if (repo.ref == null) {
        pagesCandidates + rawCandidates + mirrorCandidates
    } else {
        rawCandidates + pagesCandidates + mirrorCandidates
    }
}

private data class GithubRepoAddress(val owner: String, val repo: String, val ref: String?)

/**
 * Reads a github.com address as owner/repository plus the branch it names, or null when the
 * address is not a repository page — a release asset, an issue list and the like stay untouched.
 * A clone suffix and a `tree`/`blob`/`raw` page are understood; anything deeper is ignored,
 * because the index is looked up by the repository's own layout rather than by the file the
 * address happens to point at.
 */
private fun githubRepoAddress(url: String): GithubRepoAddress? {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    if (uri.host?.lowercase() != "github.com") return null
    if (!uri.query.isNullOrEmpty() || !uri.fragment.isNullOrEmpty()) return null

    val segments = uri.path.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }
    if (segments.size < 2) return null
    val owner = segments[0]
    val repo = segments[1].removeSuffix(".git")
    if (owner.isEmpty() || repo.isEmpty()) return null

    return when {
        segments.size == 2 -> GithubRepoAddress(owner, repo, null)
        segments[2] in REF_SEGMENTS && segments.size >= 4 -> GithubRepoAddress(owner, repo, segments[3])
        else -> null
    }
}

private val REF_SEGMENTS = listOf("tree", "blob", "raw")

private val PAGES_SUFFIXES = listOf(".github.io", ".gitlab.io", ".gitee.io", ".codeberg.page")
private val FORGE_HOSTS =
    listOf("github.com", "raw.githubusercontent.com", "gitlab.com", "gitee.com", "codeberg.org", "bitbucket.org")

/**
 * A readable name for a source the user did not name. Module indexes are published out of a
 * repository, so "owner/repo" identifies the source far better than the host it happens to be
 * served from: `uonou.github.io/mmrl-repo` is really just `uonou/mmrl-repo`. Anything that is
 * not a known forge or Pages site falls back to the bare host.
 */
internal fun defaultSourceName(url: String): String {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return url
    val host = uri.host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotEmpty() } ?: return url
    val segments = uri.path.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }

    val pages = PAGES_SUFFIXES.firstOrNull { host.endsWith(it) }
    if (pages != null && segments.isNotEmpty()) {
        return "${host.removeSuffix(pages)}/${segments.first()}"
    }
    if (host in FORGE_HOSTS && segments.size >= 2) {
        return "${segments[0]}/${segments[1].removeSuffix(".git")}"
    }
    return host
}

/**
 * 索引是模块元数据，正常只有几百 KB。没有上限的话，一个坏地址（或一个被塞满的仓库）就能让
 * body.string() 把一个几百 MB 的响应整读进内存，那是一次稳妥的 OOM。8 MiB 留了几十倍的余量。
 */
internal const val MAX_INDEX_RESPONSE_BYTES = 8L * 1024 * 1024

/**
 * 整次索引请求（连接 + 重定向 + 读体）的上限。连接/读超时是单项超时，一个每十几秒吐一个
 * 字节的源永远碰不到它们，探测和列表刷新就被无限期占住；只有整次调用的封顶拦得住。
 */
internal const val INDEX_CALL_TIMEOUT_SECONDS = 20L

/** 响应超限。带可读原因，失败时会原样拼进给用户看的提示里。 */
internal class IndexResponseTooLargeException(limitBytes: Long) :
    Exception("index response exceeds ${limitBytes / (1024 * 1024)} MiB")

/**
 * 内置的默认源：官方注册表。
 *
 * id 必须固定。每次读都新生成一个 UUID 的话，界面拿着 A 去删，下一次读出来的是 B——删不掉，
 * 改名和启停也落不到同一条上。它也只是 prefs 里的主键，不需要跟着安装走。
 */
internal val builtInSource = RepoSource(
    id = "builtin-kernelsu-modules",
    name = "KernelSU Modules",
    url = KERNELSU_REGISTRY_INDEX_URL,
)

/** 酷安社区模块源在 prefs 与界面里的固定 id。 */
internal const val COOLAPK_COMMUNITY_SOURCE_ID = "coolapk-community-modules"

/**
 * 酷安社区模块索引的地址。
 *
 * 走 jsDelivr 而不是 `raw.githubusercontent.com`：后者在国内常见网络下连不上，而这份索引
 * 恰恰是面向酷安用户的。内容是 `SukiSU-Community/modules-repo` 仓的 `json/modules.json`，
 * 每次发布由 `publish-from-main.mjs` 重建。
 */
internal const val COOLAPK_COMMUNITY_INDEX_URL =
    "https://cdn.jsdelivr.net/gh/SukiSU-Community/modules-repo@main/json/modules.json"

/**
 * 「酷安社区模块」源：默认关闭，由用户读完免责说明后自己开启。
 *
 * 名字写死成中文而不是走 stringResource——[RepoSource.name] 是要落盘的展示名，用户可以改、
 * 也会被备份带走；界面在这个 id 上另有本地化标题。
 */
internal val coolapkCommunitySource = RepoSource(
    id = COOLAPK_COMMUNITY_SOURCE_ID,
    name = "酷安社区模块",
    url = COOLAPK_COMMUNITY_INDEX_URL,
    enabled = false,
)

/**
 * 读索引响应体，最多读 [limitBytes] 字节：peekBody 只多取 1 字节用来判断有没有超限，
 * 超了就带着理由失败，绝不把整个响应读进内存。索引读取处处都该走这里，免得漏一处就漏一个 OOM。
 */
internal fun Response.readIndexBody(limitBytes: Long = MAX_INDEX_RESPONSE_BYTES): String {
    val bytes = peekBody(limitBytes + 1).bytes()
    if (bytes.size > limitBytes) throw IndexResponseTooLargeException(limitBytes)
    return String(bytes, Charsets.UTF_8)
}

/**
 * Reads the modules a repository publishes at [indexUrl], following one hop when the document is a
 * registry index.
 *
 * The registry at `modules.kernelsu.org` serves `api/v1/index.json` as a small pointer that names
 * the file holding the modules; the modules themselves are never in the document a user pastes.
 * Throws on a non-2xx response, an unreadable document or an unparseable format, which is how
 * `addSource` tells a usable address from an unusable one.
 */
internal fun fetchIndexModules(
    client: OkHttpClient,
    indexUrl: String,
    sourceId: String = "",
    sourceName: String = "",
): List<RepoModule> {
    val body = readIndexDocument(client, indexUrl)
    val pointer = RepoModuleParser.registryIndexPointer(body) ?: return RepoModuleParser.parse(
        body = body,
        sourceId = sourceId,
        sourceName = sourceName,
        indexUrl = indexUrl,
    )

    val baseUrl = registryBaseUrl(indexUrl)
        ?: throw IllegalArgumentException("Registry index has no site root: $indexUrl")
    val target = registryDocumentUrl(baseUrl, pointer)
    val targetBody = readIndexDocument(client, target)
    // 解析用的是清单自己的地址：注册表里的路径都相对站点根，而 sameOriginAssetFallback 之类
    // 的判断要的是"这份文档从哪来"。
    return RepoModuleParser.parse(
        body = targetBody,
        sourceId = sourceId,
        sourceName = sourceName,
        indexUrl = target,
    )
}

private fun readIndexDocument(client: OkHttpClient, url: String): String {
    val request = Request.Builder().url(url).build()
    return client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
        response.readIndexBody()
    }
}
