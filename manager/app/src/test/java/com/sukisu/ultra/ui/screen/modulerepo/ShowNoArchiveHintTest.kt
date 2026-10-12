package com.sukisu.ultra.ui.screen.modulerepo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowNoArchiveHintTest {

    private fun release(withAsset: Boolean) = ReleaseArg(
        tagName = "v1",
        name = "v1",
        publishedAt = "",
        assets = if (withAsset) {
            listOf(ReleaseAssetArg(name = "m.zip", downloadUrl = "https://example.com/m.zip", size = 1, downloadCount = 0))
        } else {
            emptyList()
        },
    )

    private fun state(
        releases: List<ReleaseArg> = emptyList(),
        detailReleases: List<ReleaseArg> = emptyList(),
        readmeLoaded: Boolean = true,
    ) = ModuleRepoDetailUiState(
        module = RepoModuleArg(
            moduleId = "m",
            moduleName = "Module",
            authors = "",
            authorsList = emptyList(),
            latestRelease = "",
            latestReleaseTime = "",
            releases = releases,
        ),
        readmeHtml = null,
        readmeLoaded = readmeLoaded,
        detailReleases = detailReleases,
        webUrl = "",
        sourceUrl = "",
    )

    @Test
    fun `a module whose index carried an archive never gets the hint`() {
        assertFalse(state(releases = listOf(release(withAsset = true))).showNoArchiveHint())
    }

    @Test
    fun `a registry module is judged on the releases that arrived with its document`() {
        // 注册表索引不带下载地址，releases 一直是空的；只看它就会给每个注册表模块挂一句错话。
        val registry = state(releases = emptyList(), detailReleases = listOf(release(withAsset = true)))
        assertFalse(registry.showNoArchiveHint())
    }

    @Test
    fun `the hint is withheld until the detail has loaded`() {
        // 抓取期间两个来源都可能是空的，那只是"还不知道"，不是"没有"。
        assertFalse(state(readmeLoaded = false).showNoArchiveHint())
    }

    @Test
    fun `a module with nothing installable gets the hint`() {
        assertTrue(state(releases = listOf(release(withAsset = false))).showNoArchiveHint())
        assertTrue(state(releases = emptyList(), detailReleases = listOf(release(withAsset = false))).showNoArchiveHint())
        assertTrue(state().showNoArchiveHint())
    }
}
