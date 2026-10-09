package com.sukisu.ultra.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactionTest {

    @Test
    fun `credentials embedded in a url are masked`() {
        assertEquals(
            "https://***:***@dav.example.com/dav/a.zip",
            Redaction.redactUrl("https://user:app-pass@dav.example.com/dav/a.zip")
        )
    }

    @Test
    fun `a url without credentials is untouched`() {
        assertEquals(
            "https://dav.example.com/dav/a.zip",
            Redaction.redactUrl("https://dav.example.com/dav/a.zip")
        )
    }

    @Test
    fun `credentials are masked inside a longer message`() {
        val message = "PUT failed for https://bob:secret@nas.local/dav/x -> HTTP 401"
        val redacted = Redaction.redactMessage(message)
        assertFalse(redacted.contains("secret"))
        assertTrue(redacted.contains("***:***@nas.local"))
        assertTrue(redacted.contains("HTTP 401"))
    }

    @Test
    fun `a user name without a password is masked too`() {
        assertEquals(
            "https://***@dav.example.com/dav/a.zip",
            Redaction.redactUrl("https://alice@dav.example.com/dav/a.zip")
        )
    }

    @Test
    fun `an at sign inside the path is not treated as userinfo`() {
        assertEquals(
            "https://dav.example.com/dav/a@b.zip",
            Redaction.redactUrl("https://dav.example.com/dav/a@b.zip")
        )
    }

    @Test
    fun `an at sign in a query without a path is not treated as userinfo`() {
        assertEquals(
            "https://dav.example.com?user=x@y",
            Redaction.redactUrl("https://dav.example.com?user=x@y")
        )
    }

    @Test
    fun `a port is not treated as a password`() {
        assertEquals(
            "https://dav.example.com:8443/dav/a.zip",
            Redaction.redactUrl("https://dav.example.com:8443/dav/a.zip")
        )
    }

    @Test
    fun `a token used as the password with no user name is masked`() {
        assertEquals(
            "https://***:***@dav.example.com/dav/a.zip",
            Redaction.redactUrl("https://:app-token@dav.example.com/dav/a.zip")
        )
    }

    @Test
    fun `every url in a message is masked`() {
        val redacted = Redaction.redactMessage("PUT https://a:b@h1/x failed, retried https://c@h2/y")
        assertFalse(redacted.contains("a:b"))
        assertFalse(redacted.contains("https://c@"))
        assertTrue(redacted.contains("https://***:***@h1"))
        assertTrue(redacted.contains("https://***@h2"))
    }

    @Test
    fun `null message becomes an empty string`() {
        assertEquals("", Redaction.redactMessage(null))
    }
}
