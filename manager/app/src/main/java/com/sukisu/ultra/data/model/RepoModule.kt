package com.sukisu.ultra.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class Author(
    val name: String,
    val link: String,
)

@Immutable
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long
)

@Immutable
data class MmrlVersion(
    val version: String,
    val versionCode: Long,
    val timestamp: Long,
    /** ISO-8601 UTC rendering of [timestamp], empty when unknown. */
    val time: String,
    val zipUrl: String,
    /** Same path on the repository's own host, for indexes whose asset host has moved. */
    val zipUrlFallback: String?,
    val changelogUrl: String?,
    val size: Long,
)

/** Extra data carried by modules from MMRL-format repositories. */
@Immutable
data class MmrlModuleInfo(
    val readmeUrl: String?,
    val supportUrl: String?,
    val versions: List<MmrlVersion>,
)

/**
 * Extra data carried by modules from a KernelSU v1 registry (`modules.kernelsu.org`).
 *
 * Such an index lists only the newest version and no download address at all: the releases live in
 * a document per module, which is read when the module is opened rather than for every module on
 * every refresh.
 */
@Immutable
data class RegistryModuleInfo(
    /** Site root the module's paths are relative to, ending with a slash. */
    val baseUrl: String,
    /** Absolute address of the module's detail document, which carries its releases. */
    val detailUrl: String,
)

@Immutable
data class RepoModule(
    val moduleId: String,
    val moduleName: String,
    val authors: String,
    val authorList: List<Author>,
    val summary: String,
    val metamodule: Boolean,
    val zygisk: Boolean = false,
    val stargazerCount: Int,
    val updatedAt: String,
    val createdAt: String,
    val latestRelease: String,
    val latestReleaseTime: String,
    val latestVersionCode: Long,
    val latestAsset: ReleaseAsset?,
    val sourceId: String = "",
    val sourceName: String = "",
    /**
     * 酷安社区索引里记的出处：上传者当时贴的帖子地址、昵称、UID 与提交时间。
     *
     * 其它来源的索引没有这四个键，一律留空——详情页据此判断要不要画「出处」那一套。
     */
    val source: String = "",
    val uploader: String = "",
    val coolapkUid: String = "",
    /** 提交时间，epoch 毫秒或秒都由索引给；0 表示索引没带这个字段。 */
    val submittedAt: Long = 0L,
    /**
     * Names of the other repositories that publish this same module and lost the merge, so a
     * deduplicated list can still say where else the module is available.
     */
    val alternateSourceNames: List<String> = emptyList(),
    val mmrl: MmrlModuleInfo? = null,
    val registry: RegistryModuleInfo? = null,
)
