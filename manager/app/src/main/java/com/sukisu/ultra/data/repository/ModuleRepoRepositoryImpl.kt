package com.sukisu.ultra.data.repository

import com.sukisu.ultra.data.model.RepoModule
import com.sukisu.ultra.ksuApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class ModuleRepoRepositoryImpl(
    private val sourceRepo: RepoSourceRepository = RepoSourceRepositoryImpl(),
) : ModuleRepoRepository {

    /**
     * 刷新用的是全局客户端，它只设了连接和读的超时：一个每十几秒吐一个字节的源可以让一次
     * 刷新拖到永远。这里给整次调用封顶（newBuilder 复制的是共享的连接池与线程池，不会额外
     * 建连接资源）。懒初始化是为了不在构造时碰 ksuApp。
     */
    private val indexClient: OkHttpClient by lazy {
        ksuApp.okhttpClient.newBuilder()
            .callTimeout(java.time.Duration.ofSeconds(INDEX_CALL_TIMEOUT_SECONDS))
            .build()
    }

    override suspend fun fetchModules(): Result<ModuleRepoFetchResult> = withContext(Dispatchers.IO) {
        try {
            val sources = sourceRepo.loadSources().filter { it.enabled }
            if (sources.isEmpty()) {
                return@withContext Result.success(ModuleRepoFetchResult(emptyList(), emptyMap()))
            }

            coroutineScope {
                val outcomes = sources.map { source ->
                    async { fetchSource(source) }
                }.awaitAll()
                Result.success(aggregateModuleSources(sources, outcomes))
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fetchSource(source: RepoSource): Result<List<RepoModule>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(source.url).build()
            indexClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}")
                }
                // 索引限个大小读：坏地址指向一个几百 MB 的文件时，整读进内存就是一次 OOM。
                val body = response.readIndexBody()
                Result.success(RepoModuleParser.parse(body, source.id, source.name, source.url))
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Merges per-source fetch outcomes into the page result.
 *
 * Community repositories mirror each other heavily, and they do not agree on how a module is
 * identified: the same module is published as `copg` and `COPG`, or as `trickystore` and
 * `tricky_store`. Entries are therefore deduplicated on both the module id and the module name,
 * compared after dropping case and punctuation, and the surviving entry is the installable one:
 * a module with a download address wins over one whose index carries none, and a newer version
 * wins over an older one. Repositories that lost the merge are recorded in
 * [RepoModule.alternateSourceNames] instead of being dropped, so the page can still show that the
 * module is published elsewhere. Failures are keyed by source name.
 */
fun aggregateModuleSources(
    sources: List<RepoSource>,
    outcomes: List<Result<List<RepoModule>>>,
): ModuleRepoFetchResult {
    val modules = LinkedHashMap<String, RepoModule>()
    val canonicalByKey = HashMap<String, String>()
    val errors = LinkedHashMap<String, String>()

    fun absorb(module: RepoModule) {
        val keys = dedupKeys(module)
        if (keys.isEmpty()) {
            modules["${module.sourceId}\u0000${module.moduleId}"] = module
            return
        }
        val existing = keys.mapNotNull { canonicalByKey[it] }.distinct()
        // The id of an incoming module may point at one entry while its name points at another;
        // collapse them onto the first, which also keeps the earliest position in the list.
        val target = existing.firstOrNull() ?: keys.first()
        var entry = modules[target] ?: module
        existing.drop(1).forEach { other ->
            modules.remove(other)?.let { entry = entry.mergedWith(it) }
            // 被折进来的那条已经从 modules 里消失，但它名下的别名键还指着它。下一次碰到只用
            // 该别名的模块时，这条陈旧映射会让人以为条目还在——合并过的模块会被当成新条目
            // 重新追到列表尾部，既重复又错位。所以删除的同时把指向它的键改指到留下的那条。
            canonicalByKey.entries.filter { it.value == other }.forEach { it.setValue(target) }
        }
        entry = entry.mergedWith(module)
        modules[target] = entry
        keys.forEach { canonicalByKey[it] = target }
    }

    sources.forEachIndexed { index, source ->
        outcomes.getOrNull(index)?.fold(
            onSuccess = { list -> list.forEach(::absorb) },
            onFailure = { e -> errors[source.name] = e.message ?: e.javaClass.simpleName },
        )
    }
    return ModuleRepoFetchResult(modules.values.toList(), errors)
}

/**
 * The identities a module can be recognised by across repositories: its name and its id, each
 * reduced to lowercase letters and digits. Names matter because sources disagree about ids, ids
 * because a source may rename a module; within one repository neither ever repeats, so a match on
 * either is a match on the module.
 */
internal fun dedupKeys(module: RepoModule): List<String> =
    listOf(module.moduleName, module.moduleId)
        .map { key -> key.lowercase().filter { it.isLetterOrDigit() } }
        .filter { it.isNotEmpty() }
        .distinct()

/**
 * Folds [other] into this entry, keeping whichever of the two is installable and remembering the
 * repository it came from.
 */
private fun RepoModule.mergedWith(other: RepoModule): RepoModule {
    if (other === this) return this
    val (winner, loser) = if (isBetterThan(other, this)) other to this else this to other
    val alternates = (winner.alternateSourceNames + loser.sourceName + loser.alternateSourceNames)
        .filter { it.isNotEmpty() && it != winner.sourceName }
        .distinct()
    return winner.copy(alternateSourceNames = alternates)
}

/**
 * Whether [candidate] should represent the merged module instead of [current]: an entry the user
 * can actually install beats one whose index carries no download address, and otherwise the newer
 * version beats the older one.
 */
private fun isBetterThan(candidate: RepoModule, current: RepoModule): Boolean {
    val candidateDownloadable = !candidate.latestAsset?.downloadUrl.isNullOrBlank()
    val currentDownloadable = !current.latestAsset?.downloadUrl.isNullOrBlank()
    if (candidateDownloadable != currentDownloadable) return candidateDownloadable
    return candidate.latestVersionCode > current.latestVersionCode
}
