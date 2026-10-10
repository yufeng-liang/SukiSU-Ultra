package com.sukisu.ultra.data.repository

import android.content.SharedPreferences
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

private const val SOURCES_KEY = "repo_sources"

/**
 * 添加一个源要跨网络探测候选地址（最长 35 秒）。落盘时必须按**当时**的列表合并：用探测前读到
 * 的快照写回去，会把等待期间用户做的删除、改名、启停一起回滚——看起来只是"加了个源"。
 */
class RepoSourceRepositoryTest {

    private lateinit var server: HttpServer

    private val prefs = FakeSharedPreferences()

    /** 在探测请求**正在被处理**时跑，也就是恰好落在"读列表"和"写回列表"之间。 */
    private var whileProbing: (() -> Unit)? = null

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serve("/index.json", "[]", probeHook = true)
        serve("/not-a-repo.json", """{"hello": "world"}""")
        serve("/broken.json", """[{"moduleId": "broken""")
        serve("/empty-mmrl.json", """{"modules": []}""")
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    /** 一个总是以 200 返回 [body] 的地址；[probeHook] 用于探测请求正在被处理的那一刻。 */
    private fun serve(path: String, body: String, probeHook: Boolean = false) {
        server.createContext(path) { exchange ->
            if (probeHook) whileProbing?.invoke()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun indexUrl() = url("/index.json")

    private fun repository() = RepoSourceRepositoryImpl(prefs = prefs, httpClient = OkHttpClient())

    private fun seed(vararg sources: RepoSource) {
        prefs.put(SOURCES_KEY, render(sources.toList()))
    }

    private fun stored() = repository().loadSources()

    @Test
    fun `a source removed while the address was being probed stays removed`() = runBlocking {
        val kept = RepoSource("kept", "Kept", "https://example.com/kept.json")
        val doomed = RepoSource("doomed", "Doomed", "https://example.com/doomed.json")
        seed(kept, doomed)
        whileProbing = { seed(kept) }

        val added = repository().addSource(indexUrl(), name = "Local").getOrThrow()

        assertEquals(listOf("kept", added.id), stored().map { it.id })
        assertEquals(listOf("Kept", "Local"), stored().map { it.name })
    }

    @Test
    fun `a source added while probing keeps the one that landed first`() = runBlocking {
        seed()
        whileProbing = { seed(RepoSource("first", "First", indexUrl())) }

        val added = repository().addSource(indexUrl(), name = "Local").getOrThrow()

        assertEquals("first", added.id)
        assertEquals(listOf("first"), stored().map { it.id })
    }

    @Test
    fun `the probed address is the one that gets saved`() = runBlocking {
        seed()

        val added = repository().addSource(indexUrl(), name = "Local").getOrThrow()

        assertEquals(indexUrl(), added.url)
        assertEquals(listOf(added), stored())
    }

    @Test
    fun `saved sources keep url name enabled and order across a load`() = runBlocking {
        seed()

        val first = repository().addSource(indexUrl(), name = "First").getOrThrow()
        val second = repository().addSource(url("/empty-mmrl.json"), name = "Second").getOrThrow()
        repository().setSourceEnabled(first.id, false)
        repository().renameSource(second.id, "Renamed")

        // 每个字段都要活着回来：名字、地址、启停和先后顺序，少一样列表页就会自己改用户的配置。
        assertEquals(
            listOf(first.copy(enabled = false), second.copy(name = "Renamed")),
            stored(),
        )
    }

    @Test
    fun `a malformed index is refused and never persisted`() = runBlocking {
        // 以 `[` 开头，所以只看首字符的嗅探会把它当仓库收下——落到列表里却一条也解析不出来。
        val result = repository().addSource(url("/broken.json"), name = "Broken")

        assertTrue(result.isFailure)
        assertFalse(prefs.contains(SOURCES_KEY))
    }

    @Test
    fun `a JSON document that is not a module index is refused`() = runBlocking {
        val result = repository().addSource(url("/not-a-repo.json"), name = "Foreign")

        assertTrue(result.isFailure)
        assertFalse(prefs.contains(SOURCES_KEY))
    }

    @Test
    fun `a repository with no modules is still a valid repository`() = runBlocking {
        // 索引为空是合法的（只是还没有模块），不能拿"列表非空"当判据把人家的仓库拒掉。
        seed()

        val added = repository().addSource(url("/empty-mmrl.json"), name = "Empty").getOrThrow()

        assertEquals(listOf(added), stored())
    }

    @Test
    fun `an index larger than the cap is refused with a readable reason`() {
        // 上限的一个字节之差：整 8 MiB 照收，多一个字节就以"超限"失败，而不是先读进内存。
        val atLimit = responseWithBody("x".repeat(MAX_INDEX_RESPONSE_BYTES.toInt()))
        assertEquals(MAX_INDEX_RESPONSE_BYTES.toInt(), atLimit.readIndexBody().length)

        val overLimit = responseWithBody("x".repeat((MAX_INDEX_RESPONSE_BYTES + 1).toInt()))
        val error = assertThrows(IndexResponseTooLargeException::class.java) { overLimit.readIndexBody() }

        assertTrue(error.message.orEmpty().contains("8 MiB"))
    }
}

private fun responseWithBody(body: String) = Response.Builder()
    .request(Request.Builder().url("http://127.0.0.1/index.json").build())
    .protocol(Protocol.HTTP_1_1)
    .code(200)
    .message("OK")
    .body(body.toResponseBody())
    .build()

private fun render(sources: List<RepoSource>): String {
    val array = JSONArray()
    sources.forEach { source ->
        array.put(
            JSONObject()
                .put("id", source.id)
                .put("name", source.name)
                .put("url", source.url)
                .put("enabled", source.enabled),
        )
    }
    return array.toString()
}

/** 只在内存里的 SharedPreferences：`edit {}` 立刻生效，够跑仓库的读-改-写。 */
private class FakeSharedPreferences : SharedPreferences {

    private val values = mutableMapOf<String, Any?>()

    fun put(key: String, value: String) {
        values[key] = value
    }

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = apply { values[key!!] = value }

        override fun putStringSet(key: String?, set: MutableSet<String>?) = apply { values[key!!] = set }

        override fun putInt(key: String?, value: Int) = apply { values[key!!] = value }

        override fun putLong(key: String?, value: Long) = apply { values[key!!] = value }

        override fun putFloat(key: String?, value: Float) = apply { values[key!!] = value }

        override fun putBoolean(key: String?, value: Boolean) = apply { values[key!!] = value }

        override fun remove(key: String?) = apply { values.remove(key!!) }

        override fun clear() = apply { values.clear() }

        override fun commit(): Boolean = true

        override fun apply() = Unit
    }
}
