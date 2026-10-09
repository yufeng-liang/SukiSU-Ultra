package com.sukisu.ultra.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavPresetsTest {

    @Test
    fun `every preset has an https template and a hint resource`() {
        WebDavPresets.ALL.forEach { preset ->
            assertTrue(preset.label, preset.urlTemplate.startsWith("https://"))
            // 提示现在是资源 id：界面靠它显示"去哪生成应用密码"，0 表示这条提示丢了。
            assertTrue(preset.label, preset.hintRes != 0)
        }
    }

    @Test
    fun `hint resources are distinct so no preset shows another provider's steps`() {
        assertEquals(WebDavPresets.ALL.size, WebDavPresets.ALL.map { it.hintRes }.toSet().size)
    }

    @Test
    fun `labels are unique`() {
        assertEquals(WebDavPresets.ALL.size, WebDavPresets.ALL.map { it.label }.toSet().size)
    }
}
