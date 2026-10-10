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
    }
}
