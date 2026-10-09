package com.sukisu.ultra.ui.screen.settings.backup

import com.sukisu.ultra.data.model.Module
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModuleOptionTextTest {

    private fun module(
        id: String = "a",
        name: String = "Name",
        version: String = "1.0",
        enabled: Boolean = true,
    ) = Module(
        id = id,
        name = name,
        author = "author",
        version = version,
        versionCode = 1L,
        description = "desc",
        enabled = enabled,
        update = false,
        remove = false,
        updateJson = "",
        hasWebUi = false,
        hasActionScript = false,
        metamodule = false,
        actionIconPath = null,
        webUiIconPath = null,
    )

    @Test
    fun `a blank name falls back to the module id`() {
        // module.prop 里没写 name 时列表会空出一行，等于这一项不存在。
        assertEquals("a", ModuleOptionText.title(module(name = "")))
        assertEquals("a", ModuleOptionText.title(module(name = "   ")))
        assertEquals("Name", ModuleOptionText.title(module()))
    }

    @Test
    fun `the summary is the version`() {
        assertEquals("1.0", ModuleOptionText.summary(module(), disabledLabel = "已禁用"))
    }

    @Test
    fun `a disabled module says so after its version`() {
        assertEquals(
            "1.0 · 已禁用",
            ModuleOptionText.summary(module(enabled = false), disabledLabel = "已禁用"),
        )
    }

    @Test
    fun `ksud's placeholder version is not shown as if it were a version`() {
        assertEquals(null, ModuleOptionText.summary(module(version = "Unknown"), disabledLabel = "已禁用"))
        assertEquals(
            "已禁用",
            ModuleOptionText.summary(module(version = "Unknown", enabled = false), disabledLabel = "已禁用"),
        )
    }

    @Test
    fun `nothing to show means no summary line at all`() {
        assertNull(ModuleOptionText.summary(module(version = ""), disabledLabel = "已禁用"))
    }
}
