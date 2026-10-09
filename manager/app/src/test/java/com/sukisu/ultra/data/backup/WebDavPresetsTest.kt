package com.sukisu.ultra.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavPresetsTest {

    @Test
    fun `every preset has an https template and a non blank hint`() {
        WebDavPresets.ALL.forEach { preset ->
            assertTrue(preset.label, preset.urlTemplate.startsWith("https://"))
            assertTrue(preset.label, preset.hint.isNotBlank())
        }
    }

    @Test
    fun `labels are unique`() {
        assertEquals(WebDavPresets.ALL.size, WebDavPresets.ALL.map { it.label }.toSet().size)
    }
}
