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
import java.net.URI
import java.util.Base64

class WebDavBackupStorageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val files = mutableMapOf<String, ByteArray>()
    private val collections = mutableSetOf<String>()
    private var requireAuth = true

    /** 期望的 Authorization 头；非 ASCII 密码那条测试会把它换成 UTF-8 编码的那一份。 */
    private var expectedAuth =
        "Basic " + Base64.getEncoder().encodeToString("user:app-pass".toByteArray(Charsets.UTF_8))

    /** 非 null 时 MKCOL 一律回 301 + 这个 Location，用来盯重定向的判定。 */
    private var redirectCollectionsTo: String? = null

    /** 有服务端禁用 MOVE；关掉它来验证上传会退回直接覆盖。 */
    private var moveSupported = true

    private val putPaths = mutableListOf<String>()
    private val moves = mutableListOf<Pair<String, String>>()

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
        if (requireAuth && auth != expectedAuth) {
            exchange.sendResponseHeaders(401, -1); exchange.close(); return
        }
        val path = exchange.requestURI.path.removePrefix("/dav")
        when (exchange.requestMethod) {
            "MKCOL" -> {
                collections += path
                val redirect = redirectCollectionsTo
                if (redirect == null) {
                    exchange.sendResponseHeaders(201, -1)
                } else {
                    exchange.responseHeaders.add("Location", redirect)
                    exchange.sendResponseHeaders(301, -1)
                }
            }
            "PUT" -> {
                putPaths += path
                files[path] = exchange.requestBody.readBytes()
                exchange.sendResponseHeaders(201, -1)
            }
            "MOVE" -> {
                if (!moveSupported) {
                    exchange.sendResponseHeaders(405, -1)
                } else {
                    val destination = exchange.requestHeaders.getFirst("Destination").orEmpty()
                    val target = URI.create(destination).path.removePrefix("/dav")
                    moves += path to target
                    val body = files.remove(path)
                    if (body == null) exchange.sendResponseHeaders(404, -1)
                    else {
                        files[target] = body
                        exchange.sendResponseHeaders(201, -1)
                    }
                }
            }
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

    private fun storage(
        followRedirects: Boolean = true,
        username: String = "user",
        password: String = "app-pass",
    ) = WebDavBackupStorage(
        client = OkHttpClient.Builder().followRedirects(followRedirects).build(),
        baseUrl = "http://127.0.0.1:${server.address.port}/dav/sukisu",
        username = username,
        password = password,
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
    fun `put reports the bytes it actually sends`() {
        runBlocking {
            val body = ByteArray(200 * 1024) { (it % 251).toByte() }
            val seen = mutableListOf<Long>()

            val result = storage().put(
                relativePath = "SukiSU/backup/big.zip",
                size = body.size.toLong(),
                open = { body.inputStream() },
                onProgress = { seen += it },
            )

            assertTrue(result.isSuccess)
            assertTrue("expected progress reports", seen.isNotEmpty())
            // 进度必须单调：往回跳的进度条比没有进度条更糟。
            assertEquals(seen.sorted(), seen)
            // 最后一次是整个文件的字节数——进度条要走到底，平均速度也按这个数算。
            assertEquals(body.size.toLong(), seen.last())
            // 引擎靠这个标志决定"要不要自己在源流上数"，报错了就会双份计数。
            assertTrue(storage().reportsTransferProgress)
        }
    }

    @Test
    fun `an upload replaces the target through a temporary object`() {
        runBlocking {
            val body = "index v2".toByteArray()
            assertTrue(storage().put("index.json", body.size.toLong()) { body.inputStream() }.isSuccess)

            // 索引指向全部备份：半截内容写进目标等于整个功能读不出东西，只能经临时对象换名。
            assertEquals("index v2", files.getValue("/sukisu/index.json").decodeToString())
            assertTrue("expected a PUT to a .part object, saw $putPaths", putPaths.any { it.endsWith(".part") })
            assertTrue(
                "expected a MOVE onto the target, saw $moves",
                moves.contains("/sukisu/index.json.part" to "/sukisu/index.json"),
            )
            assertTrue("the temporary object must not be left behind", files.keys.none { it.endsWith(".part") })
        }
    }

    @Test
    fun `a server that rejects MOVE still gets the upload`() {
        runBlocking {
            moveSupported = false
            val store = storage()

            val first = "index v3".toByteArray()
            assertTrue(store.put("index.json", first.size.toLong()) { first.inputStream() }.isSuccess)
            // 能写进去但不原子，好过根本写不进去。
            assertEquals("index v3", files.getValue("/sukisu/index.json").decodeToString())
            assertTrue("the refused temporary object must be cleaned up", files.keys.none { it.endsWith(".part") })

            // 只探测一次：支持与否是服务端的性质，不必每个文件都试。
            val second = "index v4".toByteArray()
            assertTrue(store.put("other.json", second.size.toLong()) { second.inputStream() }.isSuccess)
            assertEquals("index v4", files.getValue("/sukisu/other.json").decodeToString())
            assertEquals(1, putPaths.count { it.endsWith(".part") })
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

    @Test
    fun `credentials with non ascii characters are sent as utf-8`() {
        runBlocking {
            // 单参数的 Credentials.basic 按 ISO-8859-1 编码：非 ASCII 密码会被写成另一串字节，
            // 于是"密码明明是对的却 401"，用户只会以为是密码错。服务端比对的是 UTF-8 那一份。
            expectedAuth =
                "Basic " + Base64.getEncoder().encodeToString("user:pässwörd".toByteArray(Charsets.UTF_8))

            assertTrue(storage(password = "pässwörd").test().isSuccess)
        }
    }

    @Test
    fun `a collection that redirects to itself counts as already there`() {
        runBlocking {
            // nginx 对已存在的集合回 301 补尾斜杠，Location 指向的还是这个目录本身。
            // 关掉自动跟随重定向，确保判定发生在我们的代码里。
            redirectCollectionsTo = "http://127.0.0.1:${server.address.port}/dav/sukisu/"
            val body = "hello dav".toByteArray()

            val result = storage(followRedirects = false).put("a.txt", body.size.toLong()) { body.inputStream() }

            assertTrue("expected the self redirect to be taken as 'already there'", result.isSuccess)
            assertEquals("hello dav", files.getValue("/sukisu/a.txt").decodeToString())
        }
    }

    @Test
    fun `a redirection somewhere else is not taken for an existing collection`() {
        runBlocking {
            // 反代/门户把请求弹到登录页时也会回 301：那不是"集合已存在"，继续传只会把归档
            // 丢在别的地方（或者悄悄没传）。
            redirectCollectionsTo = "http://127.0.0.1:${server.address.port}/login"
            val body = "hello dav".toByteArray()

            val result = storage(followRedirects = false).put("a.txt", body.size.toLong()) { body.inputStream() }

            assertTrue(result.isFailure)
            val reason = reasonOf(result.exceptionOrNull()!!)
            assertEquals(
                BackupReason.HttpFailed(HttpOperation.CREATE_DIRECTORY, "/", 301),
                reason,
            )
        }
    }
}
