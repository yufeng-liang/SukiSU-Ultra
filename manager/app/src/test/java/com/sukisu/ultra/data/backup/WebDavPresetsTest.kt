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

    @Test
    fun `match finds the preset whose template is in the box`() {
        // 重开页面时靠它恢复高亮：存下来的地址正好等于某个模板，那个预设就该是亮的。
        WebDavPresets.ALL.forEach { preset ->
            assertEquals(preset, WebDavPresets.match(preset.urlTemplate))
        }
    }

    @Test
    fun `match tolerates surrounding whitespace`() {
        assertEquals(WebDavPresets.ALL.first(), WebDavPresets.match("  ${WebDavPresets.ALL.first().urlTemplate} "))
    }

    @Test
    fun `match returns null for an edited or empty address`() {
        // 用户改过地址（Nextcloud 的模板带 USERNAME，必须改）之后就不是模板了：
        // 不高亮比错高亮一个更诚实。
        assertEquals(null, WebDavPresets.match("https://dav.jianguoyun.com/dav/My-Own-Folder"))
        assertEquals(null, WebDavPresets.match(""))
        assertEquals(null, WebDavPresets.match("   "))
    }
}
