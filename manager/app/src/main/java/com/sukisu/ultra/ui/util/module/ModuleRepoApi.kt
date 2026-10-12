package com.sukisu.ultra.ui.util.module

import com.sukisu.ultra.data.repository.KERNELSU_REGISTRY_BASE
import com.sukisu.ultra.data.repository.registryDocumentUrl
import com.sukisu.ultra.ksuApp
import com.sukisu.ultra.ui.screen.modulerepo.RepoModuleArg
import com.sukisu.ultra.ui.util.isNetworkAvailable
import com.sukisu.ultra.ui.util.hasAnyNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class ModuleDetail(
    val readme: String,
    val readmeHtml: String,
    val latestTag: String,
    val latestTime: String,
    val latestAssetName: String?,
    val latestAssetUrl: String?,
    val releases: List<ReleaseInfo>,
    val homepageUrl: String,
    val sourceUrl: String,
    val url: String
)

data class ReleaseInfo(
    val name: String,
    val tagName: String,
    val publishedAt: String,
    val descriptionHTML: String,
    val assets: List<ReleaseAssetInfo>
)

data class ReleaseAssetInfo(
    val name: String,
    val downloadUrl: String,
    val size: Long,
    val downloadCount: Int,
    val downloadUrlFallback: String? = null
)

private const val MAX_CHANGELOG_FETCHES = 30

fun sanitizeVersionString(version: String): String {
    return version.replace(Regex("[^a-zA-Z0-9.\\-_]"), "_")
}

fun stripTicks(s: String): String {
    val t = s.trim()
    return if (t.startsWith("`") && t.endsWith("`") && t.length >= 2) t.substring(1, t.length - 1) else t
}

fun fetchReleaseDescriptionHtml(moduleId: String, latestTag: String): String? {
    if (!isNetworkAvailable(ksuApp)) return null
    val url = registryDocumentUrl(KERNELSU_REGISTRY_BASE, "api/v1/modules/$moduleId.json")
    return runCatching {
        ksuApp.okhttpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) null else {
                val body = resp.body.string()
                val obj = JSONObject(body)
                val releasesArray = obj.optJSONArray("releases") ?: return@use null
                var fallbackHtml: String? = null
                for (i in 0 until releasesArray.length()) {
                    val r = releasesArray.optJSONObject(i) ?: continue
                    val descHtml = r.optString("changelogHtml", "")
                    if (fallbackHtml == null && descHtml.isNotBlank()) {
                        fallbackHtml = descHtml
                    }
                    val rname = r.optString("tag", r.optString("name", r.optString("version", "")))
                    if (rname == latestTag && descHtml.isNotBlank()) {
                        return@use descHtml
                    }
                }
                fallbackHtml
            }
        }
    }.getOrNull()
}


suspend fun fetchModuleDetail(module: RepoModuleArg): ModuleDetail? {
    if (!hasAnyNetwork(ksuApp)) return null
    module.registryDetailUrl?.let { return fetchRegistryModuleDetail(module, it) }
    return if (module.isMmrl) {
        fetchMmrlModuleDetail(module)
    } else {
        fetchModuleDetailById(module.moduleId)
    }
}

/** MMRL modules carry all metadata in the repository index; only the markdown files are fetched here. */
private suspend fun fetchMmrlModuleDetail(module: RepoModuleArg): ModuleDetail? {
    val readme = module.readmeUrl?.let { fetchText(it) } ?: ""
    val releases = coroutineScope {
        module.releases.take(MAX_CHANGELOG_FETCHES).map { rel ->
            async {
                val changelog = rel.changelogUrl?.let { fetchText(it) } ?: ""
                ReleaseInfo(
                    name = rel.name,
                    tagName = rel.tagName,
                    publishedAt = rel.publishedAt,
                    descriptionHTML = changelog,
                    assets = rel.assets.map { a ->
                        ReleaseAssetInfo(a.name, a.downloadUrl, a.size, a.downloadCount, a.downloadUrlFallback)
                    }
                )
            }
        }.awaitAll()
    }
    return ModuleDetail(
        readme = readme,
        readmeHtml = readme,
        latestTag = module.latestRelease,
        latestTime = module.latestReleaseTime,
        latestAssetName = null,
        latestAssetUrl = null,
        releases = releases,
        homepageUrl = "",
        sourceUrl = module.webUrl ?: "",
        url = module.webUrl ?: "",
    )
}

/**
 * KernelSU registry detail document, keyed by module id.
 *
 * The old `/module/<id>.json` this used to read has been gone since the registry was rebuilt in
 * October 2026; the document now lives under `api/v1` and carries the same things under different
 * names.
 */
suspend fun fetchModuleDetailById(moduleId: String): ModuleDetail? {
    if (!isNetworkAvailable(ksuApp)) return null
    val url = registryDocumentUrl(KERNELSU_REGISTRY_BASE, "api/v1/modules/$moduleId.json")
    return fetchRegistryModuleDetail(
        detailUrl = url,
        baseUrl = KERNELSU_REGISTRY_BASE,
        moduleId = moduleId,
    )
}

/** A registry module opened from a repository source, whose detail address the index supplied. */
private suspend fun fetchRegistryModuleDetail(module: RepoModuleArg, detailUrl: String): ModuleDetail? =
    fetchRegistryModuleDetail(
        detailUrl = detailUrl,
        baseUrl = module.registryBaseUrl ?: KERNELSU_REGISTRY_BASE,
        moduleId = module.moduleId,
    )

private suspend fun fetchRegistryModuleDetail(
    detailUrl: String,
    baseUrl: String,
    moduleId: String,
): ModuleDetail? {
    val body = fetchText(detailUrl) ?: return null
    val parsed = runCatching { parseRegistryDetail(body, baseUrl, moduleId) }.getOrNull() ?: return null
    // The readme is a path on the registry, served as HTML, and only worth a request when the
    // document names one.
    val readme = parsed.readmeUrl?.let { fetchText(it) }?.let { absolutizeSitePaths(it, baseUrl) }.orEmpty()
    return parsed.detail.copy(readme = readme, readmeHtml = readme)
}

/**
 * 注册表的 readme 与更新日志里，图片和链接写的是**站点根相对**路径（`/assets/…`）。
 *
 * 这些内容在应用自己的 WebView 里渲染，而那里 `/assets/` 这个路径被资源加载器认领了（markdown
 * 渲染器自带的样式就走那条路）。资源加载器先比 authority 再比路径，所以只要把地址补成绝对的，
 * 请求的 authority 不再是它认的那个域名，WebView 才会真的去网上取——否则注册表的图片会全部被
 * 当成应用资源去找，一张都加载不出来。
 */
internal fun absolutizeSitePaths(html: String, baseUrl: String): String {
    if (html.isEmpty()) return html
    return SITE_RELATIVE_ATTRIBUTE.replace(html) { match -> match.groupValues[1] + baseUrl }
}

/** 只认真正的站点根相对路径：`//host/…` 这种协议相对地址不能碰。 */
private val SITE_RELATIVE_ATTRIBUTE = Regex("""((?:src|href)=")/(?!/)""")

/**
 * The detail document of a registry module, split from its readme so the network fetch of that
 * readme stays out of the parsing.
 */
internal data class RegistryDetail(
    val readmeUrl: String?,
    val detail: ModuleDetail,
)

/**
 * Reads a registry detail document. Every path in it — the readme, the module's page on the site —
 * is relative to the site root, which is why [baseUrl] is needed and the document's own address is
 * not.
 */
internal fun parseRegistryDetail(body: String, baseUrl: String, moduleId: String): RegistryDetail {
    val obj = JSONObject(body)
    val readmePath = obj.optString("readme", "").trim()
    val readmeUrl = readmePath.takeIf { it.isNotEmpty() }?.let { registryDocumentUrl(baseUrl, it) }

    val stable = obj.optJSONObject("stable")
    val releasesArray = obj.optJSONArray("releases") ?: JSONArray()
    val releases = (0 until releasesArray.length()).mapNotNull { rIdx ->
        val r = releasesArray.optJSONObject(rIdx) ?: return@mapNotNull null
        val zip = r.optJSONObject("zip")
        val zipUrl = stripTicks(zip?.optString("url", "") ?: "").trim()
        val assets = if (zip == null || zipUrl.isEmpty()) {
            emptyList()
        } else {
            listOf(
                ReleaseAssetInfo(
                    name = zip.optString("name", "").trim().ifEmpty { zipUrl.substringAfterLast('/') },
                    downloadUrl = zipUrl,
                    size = zip.optLong("size", 0L),
                    downloadCount = 0,
                )
            )
        }
        ReleaseInfo(
            name = r.optString("name", "").trim().ifEmpty { r.optString("tag", "") },
            tagName = r.optString("tag", "").trim().ifEmpty { r.optString("version", "") },
            publishedAt = r.optString("publishedAt", ""),
            // The registry renders the changelog to HTML for us, like the module page it sits on.
            descriptionHTML = absolutizeSitePaths(r.optString("changelogHtml", ""), baseUrl),
            assets = assets,
        )
    }

    val newest = releases.firstOrNull()
    val source = obj.optJSONObject("source")
    return RegistryDetail(
        readmeUrl = readmeUrl,
        detail = ModuleDetail(
            readme = "",
            readmeHtml = "",
            latestTag = stable?.optString("version", "").orEmpty().ifEmpty { newest?.tagName.orEmpty() },
            latestTime = stable?.optString("publishedAt", "").orEmpty().ifEmpty { newest?.publishedAt.orEmpty() },
            latestAssetName = newest?.assets?.firstOrNull()?.name,
            latestAssetUrl = newest?.assets?.firstOrNull()?.downloadUrl,
            releases = releases,
            homepageUrl = obj.optString("homepage", "").trim(),
            sourceUrl = source?.optString("url", "").orEmpty().trim(),
            url = registrySiteUrl(baseUrl, moduleId),
        ),
    )
}

/**
 * The module's page on the registry's own site, which is what the detail screen links to. The site
 * is published in English and Simplified Chinese; a Chinese device gets the latter unless the
 * region implies Traditional script, for which there is no page.
 */
private fun registrySiteUrl(baseUrl: String, moduleId: String): String {
    if (moduleId.isEmpty()) return ""
    val locale = Locale.getDefault()
    val traditional = locale.country in setOf("TW", "HK", "MO")
    val siteLocale = if (locale.language == "zh" && !traditional) "zh-CN" else "en"
    return "${baseUrl}$siteLocale/modules/$moduleId/"
}

private suspend fun fetchText(url: String): String? = withContext(Dispatchers.IO) {
    try {
        ksuApp.okhttpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) null else {
                val body = resp.body.string()
                if (body.length > MAX_TEXT_BYTES) body.take(MAX_TEXT_BYTES) else body
            }
        }
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

private const val MAX_TEXT_BYTES = 512 * 1024
