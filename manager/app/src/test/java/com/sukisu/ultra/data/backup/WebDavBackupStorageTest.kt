package com.sukisu.ultra.data.backup

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.Base64

class WebDavBackupStorageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val files = mutableMapOf<String, ByteArray>()
    private val collections = mutableSetOf<String>()
    private var requireAuth = true

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/dav") { exchange -> handle(exchange) }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        val auth = exchange.requestHeaders.getFirst("Authorization")
        val expected = "Basic " + Base64.getEncoder().encodeToString("user:app-pass".toByteArray())
        if (requireAuth && auth != expected) {
            exchange.sendResponseHeaders(401, -1); exchange.close(); return
        }
        val path = exchange.requestURI.path.removePrefix("/dav")
        when (exchange.requestMethod) {
            "MKCOL" -> { collections += path; exchange.sendResponseHeaders(201, -1) }
            "PUT" -> { files[path] = exchange.requestBody.readBytes(); exchange.sendResponseHeaders(201, -1) }
            "GET" -> {
                val body = files[path]
                if (body == null) exchange.sendResponseHeaders(404, -1)
                else {
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
            }
            "DELETE" -> { files.remove(path); exchange.sendResponseHeaders(204, -1) }
            else -> exchange.sendResponseHeaders(405, -1)
        }
        exchange.close()
    }

    private fun storage() = WebDavBackupStorage(
        client = OkHttpClient(),
        baseUrl = "http://127.0.0.1:${server.address.port}/dav/sukisu",
        username = "user",
        password = "app-pass",
    )

    @Test
    fun `test succeeds against a reachable authenticated server`() {
        runBlocking {
            assertTrue(storage().test().isSuccess)
        }
    }

    @Test
    fun `wrong password fails the connection test`() {
        runBlocking {
            val bad = WebDavBackupStorage(OkHttpClient(), "http://127.0.0.1:${server.address.port}/dav/sukisu", "user", "nope")
            assertTrue(bad.test().isFailure)
        }
    }

    @Test
    fun `an unauthorized response is a distinguishable auth failure`() {
        runBlocking {
            val bad = WebDavBackupStorage(OkHttpClient(), "http://127.0.0.1:${server.address.port}/dav/sukisu", "user", "nope")

            val error = bad.test().exceptionOrNull()

            // UI 靠这个类型提示"是不是该用应用密码"，而不是把 HTTP 401 原样丢给用户。
            assertTrue("expected BackupAuthException but got $error", error is BackupAuthException)
        }
    }

    @Test
    fun `put creates the collection and stores the body`() {
        runBlocking {
            val body = "hello dav".toByteArray()
            assertTrue(storage().put("sub/a.txt", body.size.toLong()) { body.inputStream() }.isSuccess)
            assertTrue(collections.contains("/sukisu"))
            assertTrue(collections.contains("/sukisu/sub"))
            assertEquals("hello dav", files.getValue("/sukisu/sub/a.txt").decodeToString())
        }
    }

    @Test
    fun `text round trip`() {
        runBlocking {
            val store = storage()
            assertTrue(store.writeText("index.json", "{\"a\":1}").isSuccess)
            assertEquals("{\"a\":1}", store.readText("index.json").getOrThrow())
        }
    }

    @Test
    fun `get on a missing path fails`() {
        runBlocking {
            assertTrue(storage().get("nope.txt").isFailure)
        }
    }

    @Test
    fun `readText on a missing object is empty text rather than a failure`() {
        runBlocking {
            // 引擎靠这个把"云端还没有索引"和"索引读不到"分开。
            assertEquals("", storage().readText("index.json").getOrThrow())
        }
    }

    @Test
    fun `a downloaded archive leaves no staged copy behind`() {
        runBlocking {
            val staging = StagingArea(temp.newFolder("staging"))
            val store = WebDavBackupStorage(
                client = OkHttpClient(),
                baseUrl = "http://127.0.0.1:${server.address.port}/dav/sukisu",
                username = "user",
                password = "app-pass",
                staging = staging,
            )
            val body = "hello dav".toByteArray()
            store.put("a.txt", body.size.toLong()) { body.inputStream() }

            store.get("a.txt").getOrThrow().use { it.readBytes() }

            assertTrue(
                "a downloaded archive must not stay in cache",
                staging.root.listFiles().orEmpty().isEmpty(),
            )
        }
    }

    @Test
    fun `delete removes the object`() {
        runBlocking {
            val store = storage()
            store.writeText("a.txt", "x")
            assertTrue(store.delete("a.txt").isSuccess)
            assertTrue(storage().get("a.txt").isFailure)
        }
    }
}
