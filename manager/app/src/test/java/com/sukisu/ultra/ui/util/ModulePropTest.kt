package com.sukisu.ultra.ui.util

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModulePropTest {

    private val prop = """
        id=awesome_module
        name=Awesome Module
        version=v1.2.3
        versionCode=123
        author=Someone
        description=Does awesome things
    """.trimIndent()

    private fun zip(vararg entries: Pair<String, String>): File {
        val dir = createTempDirectory("module-prop-").toFile()
        val file = File(dir, "module.zip")
        ZipOutputStream(file.outputStream().buffered()).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `a complete module prop parses into every field`() {
        val info = ModuleProp.parseModuleProp(zip("module.prop" to prop))

        assertEquals(
            ModulePropInfo(
                id = "awesome_module",
                name = "Awesome Module",
                version = "v1.2.3",
                versionCode = 123L,
                author = "Someone",
                description = "Does awesome things",
            ),
            info,
        )
    }

    @Test
    fun `missing fields fall back to empty text`() {
        val info = ModuleProp.parseModuleProp(
            zip("module.prop" to "id=awesome_module\nname=Awesome Module\n")
        )

        assertEquals("", info?.version)
        assertEquals("", info?.author)
        assertEquals("", info?.description)
        assertEquals(0L, info?.versionCode)
    }

    @Test
    fun `a non numeric versionCode becomes zero`() {
        val info = ModuleProp.parseModuleProp(
            zip("module.prop" to "id=awesome_module\nname=Awesome Module\nversionCode=not-a-number\n")
        )

        assertEquals(0L, info?.versionCode)
        assertEquals("awesome_module", info?.id)
    }

    @Test
    fun `a zip without module prop yields nothing`() {
        val info = ModuleProp.parseModuleProp(zip("system/etc/hosts" to "127.0.0.1 localhost"))

        assertNull(info)
    }

    @Test
    fun `a module prop in a subdirectory does not count`() {
        // ksud 只认 zip 根下那份，子目录里的同名文件装不上——解析它等于报出一个装不上的模块。
        val info = ModuleProp.parseModuleProp(zip("some_dir/module.prop" to prop))

        assertNull(info)
    }

    @Test
    fun `an empty id or name makes the whole prop unusable`() {
        assertNull(ModuleProp.parseModuleProp(zip("module.prop" to "name=Awesome Module\n")))
        assertNull(ModuleProp.parseModuleProp(zip("module.prop" to "id=awesome_module\n")))
        assertNull(ModuleProp.parseModuleProp(zip("module.prop" to "id=\nname=Awesome Module\n")))
    }

    @Test
    fun `comments blank lines and CRLF line endings are tolerated`() {
        val info = ModuleProp.parseModuleProp(
            zip(
                "module.prop" to
                    "# a comment\r\n" +
                    "\r\n" +
                    "id=awesome_module\r\n" +
                    "name=Awesome Module\r\n" +
                    "description=a=b\r\n"
            )
        )

        assertEquals("awesome_module", info?.id)
        assertEquals("Awesome Module", info?.name)
        // 只按第一个 = 切，值里的 = 是内容的一部分。
        assertEquals("a=b", info?.description)
    }

    @Test
    fun `zip sha256 is the lowercase hex digest of the whole file`() {
        val file = zip("module.prop" to prop)

        val digest = ModuleProp.zipSha256(file)

        assertTrue(digest != null)
        assertEquals(64, digest?.length)
        assertEquals(digest?.lowercase(), digest)
        assertEquals(
            java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) },
            digest,
        )
    }

    @Test
    fun `an unreadable file yields nothing instead of throwing`() {
        val missing = File(createTempDirectory("module-prop-").toFile(), "nope.zip")

        assertNull(ModuleProp.parseModuleProp(missing))
        assertNull(ModuleProp.zipSha256(missing))
        // 探测安装器也要能容错：文件没了就当"没有"，而不是把异常抛给挑包流程。
        assertTrue(!ModuleProp.hasInstallerEntry(missing))
    }

    @Test
    fun `any of the three installer forms counts as a distributable package`() {
        // 三种都要认：KSU 新模板认 customize.sh，老模板认 install.sh，
        // Magisk/recovery 认 META-INF 里的 update-binary。只认一种会把作者原包误判成重建包。
        assertTrue(ModuleProp.hasInstallerEntry(zip("module.prop" to prop, "customize.sh" to "#!/system/bin/sh")))
        assertTrue(ModuleProp.hasInstallerEntry(zip("module.prop" to prop, "install.sh" to "#!/system/bin/sh")))
        assertTrue(
            ModuleProp.hasInstallerEntry(
                zip("module.prop" to prop, "META-INF/com/google/android/update-binary" to "#MAGISK"),
            ),
        )
    }

    @Test
    fun `a package rebuilt from an installed module carries no installer`() {
        // 这就是「用已安装的模块生成」打出来的样子：只有 module.prop 和模块自己的文件。
        // ksud 装完会删 customize.sh、也从不解压 META-INF，所以这棵树里不可能有安装器。
        assertTrue(!ModuleProp.hasInstallerEntry(zip("module.prop" to prop, "system/bin/tool" to "")))

        // 名字像但位置不对（子目录里、或只是个目录条目）也不算：ksud 只读 zip 根下的那一个。
        assertTrue(!ModuleProp.hasInstallerEntry(zip("module.prop" to prop, "sub/customize.sh" to "")))
    }
}
