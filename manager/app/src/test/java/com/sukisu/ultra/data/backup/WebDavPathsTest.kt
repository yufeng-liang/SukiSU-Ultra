package com.sukisu.ultra.data.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavPathsTest {

    @Test
    fun `join keeps exactly one separator`() {
        assertEquals(
            "https://dav.example.com/remote.php/dav/sukisu/a.zip",
            WebDavPaths.joinBase("https://dav.example.com/remote.php/dav/sukisu/", "/a.zip")
        )
        assertEquals("sukisu/a.zip", WebDavPaths.joinBase("", "sukisu/a.zip"))
    }

    @Test
    fun `parent dirs are listed top down`() {
        assertEquals(listOf("a", "a/b", "a/b/c"), WebDavPaths.parentDirs("/a/b/c/"))
    }
}
