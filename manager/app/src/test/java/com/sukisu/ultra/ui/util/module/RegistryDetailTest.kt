package com.sukisu.ultra.ui.util.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class RegistryDetailTest {

    private val base = "https://modules.kernelsu.org/"

    /** Shaped like the document `api/v1/modules/CertBridge.json` actually serves. */
    private val certBridge = """
        {
          "id": "CertBridge",
          "name": "证书桥",
          "summary": "把 PEM 注入系统信任库",
          "stable": {
            "version": "v5.1.2",
            "versionCode": 50107,
            "publishedAt": "2026-10-11T16:18:27Z"
          },
          "homepage": "https://eikeitsu.github.io/CertBridge/",
          "support": "https://github.com/Eikeitsu/CertBridge/issues",
          "source": {
            "host": "github",
            "fullName": "Eikeitsu/CertBridge",
            "url": "https://github.com/Eikeitsu/CertBridge",
            "commit": "5487b58937039a8e9910525b397188222d8acdb0"
          },
          "readme": "api/v1/modules/CertBridge/readme",
          "releases": [
            {
              "tag": "v5.1.2",
              "name": "KSU Module",
              "version": "v5.1.2",
              "versionCode": 50107,
              "prerelease": false,
              "publishedAt": "2026-10-11T16:18:27Z",
              "changelogHtml": "<h2>更新说明</h2>",
              "zip": {
                "url": "https://github.com/Eikeitsu/CertBridge/releases/download/v5.1.2/CertBridge_v5.1.2_arm64.zip",
                "name": "CertBridge_v5.1.2_arm64.zip",
                "size": 5606814,
                "sha256": "f7d338bd25d57cf2310464fdf7b1fa2619ff071e5e2630d3ad8da520e76d5d87"
              },
              "immutable": true
            },
            {
              "tag": "v5.1.1",
              "name": "KSU Module",
              "version": "v5.1.1",
              "versionCode": 50106,
              "publishedAt": "2026-10-10T12:00:00Z",
              "changelogHtml": "",
              "zip": {
                "url": "https://github.com/Eikeitsu/CertBridge/releases/download/v5.1.1/CertBridge_v5.1.1_arm64.zip",
                "name": "CertBridge_v5.1.1_arm64.zip",
                "size": 5606000
              }
            }
          ]
        }
    """.trimIndent()

    private fun <T> withLocale(locale: Locale, block: () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        return try {
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `readme path is resolved against the site root`() {
        val parsed = parseRegistryDetail(certBridge, base, "CertBridge")
        assertEquals("https://modules.kernelsu.org/api/v1/modules/CertBridge/readme", parsed.readmeUrl)
        // The readme is a second request, so it is not read here.
        assertEquals("", parsed.detail.readme)
        assertEquals("", parsed.detail.readmeHtml)
    }

    @Test
    fun `every release becomes one with the zip it publishes`() {
        val detail = parseRegistryDetail(certBridge, base, "CertBridge").detail
        assertEquals(2, detail.releases.size)

        val newest = detail.releases[0]
        assertEquals("v5.1.2", newest.tagName)
        assertEquals("KSU Module", newest.name)
        assertEquals("2026-10-11T16:18:27Z", newest.publishedAt)
        assertEquals("<h2>更新说明</h2>", newest.descriptionHTML)

        val asset = newest.assets.single()
        assertEquals("CertBridge_v5.1.2_arm64.zip", asset.name)
        assertEquals(
            "https://github.com/Eikeitsu/CertBridge/releases/download/v5.1.2/CertBridge_v5.1.2_arm64.zip",
            asset.downloadUrl,
        )
        assertEquals(5606814L, asset.size)
    }

    @Test
    fun `the newest release is what the list's install button gets`() {
        val detail = parseRegistryDetail(certBridge, base, "CertBridge").detail
        assertEquals("v5.1.2", detail.latestTag)
        assertEquals("2026-10-11T16:18:27Z", detail.latestTime)
        assertEquals("CertBridge_v5.1.2_arm64.zip", detail.latestAssetName)
        assertTrue(detail.latestAssetUrl!!.endsWith("CertBridge_v5.1.2_arm64.zip"))
    }

    @Test
    fun `links point at the module's page and its own repository`() {
        val detail = parseRegistryDetail(certBridge, base, "CertBridge").detail
        assertEquals("https://eikeitsu.github.io/CertBridge/", detail.homepageUrl)
        assertEquals("https://github.com/Eikeitsu/CertBridge", detail.sourceUrl)

        assertEquals(
            "https://modules.kernelsu.org/zh-CN/modules/CertBridge/",
            withLocale(Locale.SIMPLIFIED_CHINESE) {
                parseRegistryDetail(certBridge, base, "CertBridge").detail.url
            },
        )
        assertEquals(
            "https://modules.kernelsu.org/en/modules/CertBridge/",
            withLocale(Locale.US) {
                parseRegistryDetail(certBridge, base, "CertBridge").detail.url
            },
        )
    }

    @Test
    fun `a release without a zip carries no assets rather than a broken one`() {
        val json = """
            {"releases": [{"tag": "v1", "name": "v1", "version": "v1"}]}
        """.trimIndent()
        val detail = parseRegistryDetail(json, base, "some_module").detail
        assertEquals(1, detail.releases.size)
        assertTrue(detail.releases.single().assets.isEmpty())
        assertNull(detail.latestAssetUrl)
        assertNull(detail.latestAssetName)
    }

    @Test
    fun `a document without releases yields none instead of failing`() {
        val parsed = parseRegistryDetail("""{"id": "x", "stable": {"version": "v2"}}""", base, "x")
        assertTrue(parsed.detail.releases.isEmpty())
        assertNull(parsed.readmeUrl)
        // The list's version is still worth showing when the detail document has no releases yet.
        assertEquals("v2", parsed.detail.latestTag)
        assertEquals("", parsed.detail.homepageUrl)
        assertEquals("", parsed.detail.sourceUrl)
    }

    @Test
    fun `site-relative images and links are made absolute`() {
        val html = """
            <img src="/assets/logo.png" alt="logo">
            <a href="/zh-CN/modules/CertBridge/">page</a>
        """.trimIndent()

        assertEquals(
            """
            <img src="https://modules.kernelsu.org/assets/logo.png" alt="logo">
            <a href="https://modules.kernelsu.org/zh-CN/modules/CertBridge/">page</a>
            """.trimIndent(),
            absolutizeSitePaths(html, base),
        )
    }

    @Test
    fun `absolute, protocol-relative and anchor addresses are left alone`() {
        val html = """
            <img src="https://cdn.example.com/a.png">
            <img src="//cdn.example.com/b.png">
            <a href="#usage">usage</a>
            <a href="readme.md">readme</a>
        """.trimIndent()

        assertEquals(html, absolutizeSitePaths(html, base))
        assertEquals("", absolutizeSitePaths("", base))
    }

    @Test
    fun `the changelog of a registry release is absolutized too`() {
        val json = """
            {"releases": [{"tag": "v1", "changelogHtml": "<img src=\"/assets/x.png\">"}]}
        """.trimIndent()

        assertEquals(
            """<img src="https://modules.kernelsu.org/assets/x.png">""",
            parseRegistryDetail(json, base, "m").detail.releases.single().descriptionHTML,
        )
    }
}
