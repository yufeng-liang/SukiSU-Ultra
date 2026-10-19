package com.sukisu.ultra.ui.component

import com.sukisu.ultra.ui.util.CoolapkUidLookup
import com.sukisu.ultra.ui.util.ModulePropInfo
import com.sukisu.ultra.ui.util.SubmitResult
import com.sukisu.ultra.ui.util.proofPrefixFor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.coroutines.CoroutineContext

/**
 * [ModuleContributionDialogState] 的**接线**测试。
 *
 * 为什么单独一份：`showProofRow` / `proofWasAlreadyUsed` 都是纯函数，纯函数跑绿只证明判据本身对，
 * 不证明**有人把真包的哈希喂进表单**，也不证明**提交失败时真的按那条判据重掷了 nonce**。
 * 把 `ModuleContributionDialog.kt` 里 open() 那段 `scope.launch { form = form.copy(zipSha256 = …) }`
 * 整块删掉，或删掉 submit() 里 `nonce = if (…) makeNonce() else snapshot.nonce` 那一行，
 * 27 条规则测试和 401 条全量单测一条都不会红 —— 补的就是这个洞
 * （仓库规则「新装的闸要当场证明自己会咬人」）。
 *
 * 怎么在纯 JVM 里驱动一个 Compose 状态宿主：构造函数是 internal（单测模块可见），
 * `scope` / `submit` / `lookupUid` / `submittingText` / `hashDispatcher` 全是注入的，
 * 所以既不需要 Robolectric，也不需要 Compose 的测试框架 —— 只构造状态宿主本身，界面一行都不碰。
 * scope 与算哈希的调度器都用下面那个 [SteppingScope]（手动挡），**不是** runBlocking，原因写在它的文档里。
 */
class ModuleContributionDialogStateTest {

    private val dir: File = Files.createTempDirectory("proof-wiring").toFile()

    private fun zipNamed(name: String, content: String): File =
        File(dir, name).apply {
            // UTF-8 写入，两个内容都是纯 ASCII —— 字节串与 `printf … | sha256sum` 的输入逐字节相同。
            // 这里不需要真的 zip 结构：整包哈希算的就是文件字节。
            writeText(content)
            deleteOnExit()
        }

    private val meta = ModulePropInfo("wiring_demo", "Wiring Demo", "1.0", 1L, "someone", "desc")

    // ── 外部独立算出的真值（node:crypto 一遍、coreutils sha256sum 一遍，两边同值）──────
    // sha256("SUkISU-WIRING-A") / sha256("SUkISU-WIRING-B")
    private val shaA = "03a01ebe048153f7a7448c3ffefe34070e780fdd3a822a3d5ed1fd2f2d864f26"
    private val shaB = "530265e91275b25fbf599c1c4f88efd5bb0df3bf58631de87574b8af71a2d5c2"
    // sha256("wiring_demo:<上面那个哈希>") 的前 8 位，也就是 Worker 侧会算出来的口令前缀
    private val prefixA = "788ef91b"
    private val prefixB = "1409c739"

    private val proofUsedMessage = "这个口令已经用过，请重新生成后再提交（服务端返回 HTTP 403）"

    // ── open()：整包哈希必须真的落进表单 ────────────────────────────────────

    @Test
    fun `open writes the hash of the file it was given into the form`() {
        val zip = zipNamed("a.zip", "SUkISU-WIRING-A")
        val harness = Harness(submit = { _, _, _, _, _, _, _ -> SubmitResult(true, null, "投稿已提交") })
        val state = harness.state

        // 打开之前还没有包体哈希：口令行不显示（判据本身在规则测试里钉着）。
        assertEquals("", state.form.zipSha256)
        assertFalse(ModuleContributionRules.showProofRow(meta.id, state.form.zipSha256))

        harness.openAndLandZipHash(zip)

        // 这一条就是 open() 里那段 `scope.launch { form = form.copy(zipSha256 = …) }` 的复验点：
        // 整块删掉，表单里的 zipSha256 永远是空串、口令行永远不出现，而纯函数测试全绿。
        assertEquals(shaA, state.form.zipSha256)
        assertEquals(zip, state.file)
        assertTrue(ModuleContributionRules.showProofRow(state.form.meta?.id, state.form.zipSha256))
        // 端到端：界面此刻显示的那串口令，前缀必须等于「模块 id + 这个文件的真实字节」的外部黄金值。
        // 落进表单的若是别的东西（id、文件名、另一个包的哈希）都会在这里红。
        val proof = state.form.proofText(meta.id, state.form.zipSha256)
        assertTrue("口令前缀不对：$proof", proof.startsWith("$prefixA-"))
        assertEquals(15, proof.length)
        harness.close()
    }

    @Test
    fun `a second open discards the hash computed for the previous package`() {
        val zipA = zipNamed("a.zip", "SUkISU-WIRING-A")
        val zipB = zipNamed("b.zip", "SUkISU-WIRING-B")
        val harness = Harness(submit = { _, _, _, _, _, _, _ -> SubmitResult(true, null, "投稿已提交") })
        val state = harness.state

        // 第 1 次 open：协程开跑 → 哈希任务排进队列 → 手动跑掉它，于是"A 的写回"这枚续体
        // 明确地排在队列里、**尚未执行**。这样后面才能让"陈旧的那一次"确定性地在最后一次写回之后跑。
        state.open(zipA, meta)
        harness.scope.runNext() // A 的 launch 体开跑，把哈希任务派进队列
        harness.scope.runNext() // 哈希算完，A 的写回续体入队（先按住不跑）

        // 对话框没关就换了另一个包（列表里点了第二个模块）。
        state.open(zipB, meta)
        // 取队尾而不是队首：队首现在是 A 那一次的陈旧写回，故意留着最后才跑。
        harness.scope.runLatest() // B 的 launch 体开跑，B 的哈希任务入队
        harness.scope.runLatest() // B 的哈希算完，B 的写回续体入队
        harness.scope.runLatest() // B 的写回：file === zipB → 落进表单
        assertEquals(shaB, state.form.zipSha256)

        // 现在才让 A 那一次**陈旧**的续体跑起来。
        // 删掉 open() 里 `|| file !== openedZip` 那半个闸门，这一步就会把 A 的哈希写进已经是 B 的表单：
        // 屏幕上的口令前缀变成 prefixA，而提交时网络层实算的是 B —— 用户照屏幕上那串改签名，
        // 服务端整批 403。下面两条就是那一幕的红。
        harness.scope.runNext()
        assertEquals(shaB, state.form.zipSha256)
        assertEquals(prefixB, proofPrefixFor(meta.id, state.form.zipSha256))
        harness.close()
    }

    // ── submit()：nonce 轮换的判据真的被读了一遍 ────────────────────────────

    @Test
    fun `the used-passphrase failure rolls a new nonce and submits the one on screen`() {
        val seenNonces = ArrayList<String>()
        val harness = Harness(submit = { _, _, _, _, _, nonce, _ ->
            seenNonces += nonce
            SubmitResult(false, null, proofUsedMessage)
        })
        val state = harness.state
        harness.fillIn(zipNamed("a.zip", "SUkISU-WIRING-A"))
        val before = state.form.nonce

        state.submit()
        harness.scope.drainAll() // 假 submit 不挂起：一个续体就跑完整个提交协程

        // 交给网络层的必须是屏幕上那串 —— 用户复制走、填进酷安签名的就是它。
        assertEquals(listOf(before), seenNonces)
        val after = state.form.nonce
        assertNotEquals("服务端明说这串已经用过了还不重掷，用户就永远卡在同一个作废口令上", before, after)
        assertTrue("重掷出来的必须还是合法形状：$after", Regex("^[a-z0-9]{6}$").matches(after))
        assertEquals(ContributionSubmitState.Failed(proofUsedMessage), state.form.submitState)
        harness.close()
    }

    @Test
    fun `a timeout keeps the nonce the user already copied`() {
        val harness = Harness(submit = { _, _, _, _, _, _, _ ->
            SubmitResult(false, null, "投稿超时（20 秒），请稍后再试")
        })
        val state = harness.state
        harness.fillIn(zipNamed("a.zip", "SUkISU-WIRING-A"))
        val before = state.form.nonce

        state.submit()
        harness.scope.drainAll()

        // 超时不该作废用户的口令：他照旧串改好了签名，换一串等于让他永远追不上口令。
        // 删掉 submit() 里那行 nonce 判据、或把它写成"失败就重掷"，这里红。
        assertEquals(before, state.form.nonce)
        assertEquals(ContributionSubmitState.Failed("投稿超时（20 秒），请稍后再试"), state.form.submitState)
        harness.close()
    }

    @Test
    fun `a 403 with a different reason keeps the same nonce`() {
        // HTTP 码相同、原因不同，判据看的是原因不是码：这条挡住"一见 403 就重掷"那种偷懒写法。
        // （反方向的漏掷 —— 非 2xx 把 body 里的原因丢了、判据永远拿不到依据 —— 钉在
        // ModuleContributionTest 的那两条网络层测试里，两边合起来才是完整的一条管道。）
        val reason = "这个 UID 的酷安个性签名里没有本次口令（788ef91b-abc123）—— 填好后再提交"
        val harness = Harness(submit = { _, _, _, _, _, _, _ ->
            SubmitResult(false, null, "$reason（服务端返回 HTTP 403）")
        })
        val state = harness.state
        harness.fillIn(zipNamed("a.zip", "SUkISU-WIRING-A"))
        val before = state.form.nonce

        state.submit()
        harness.scope.drainAll()

        assertEquals(before, state.form.nonce)
        harness.close()
    }

    @Test
    fun `the machine code alone rolls a new nonce even when the copy is unrecognisable`() {
        // 码优先判据的接线复验：文案换成任何话，只要 body.code == "proof_used" 就重掷。
        // 光有 RulesTest 的纯函数断言不够 —— 把 submit() 里传 result.code 的那半个实参删掉，
        // 这条红而纯函数测试全绿（正是"接线"测试要堵的那类洞）。
        val harness = Harness(submit = { _, _, _, _, _, _, _ ->
            SubmitResult(false, null, "后端把那句话改得亲妈都不认识了（服务端返回 HTTP 403）", "proof_used")
        })
        val state = harness.state
        harness.fillIn(zipNamed("a.zip", "SUkISU-WIRING-A"))
        val before = state.form.nonce

        state.submit()
        harness.scope.drainAll()

        assertNotEquals("码说明口令已烧，必须重掷", before, state.form.nonce)
        harness.close()
    }

    @Test
    fun `submit hands the on-screen package hash down to the network layer`() {
        // open() 存进表单的那份整包哈希（= 屏幕上口令绑定的字节）必须原样传下去，
        // 网络层才有东西可比。删掉 submit() 里 snapshot.zipSha256 那个实参，这条红。
        var seenZipSha: String? = null
        val harness = Harness(submit = { _, _, _, _, _, _, formZipSha ->
            seenZipSha = formZipSha
            SubmitResult(true, null, "投稿已提交")
        })
        val state = harness.state
        harness.fillIn(zipNamed("a.zip", "SUkISU-WIRING-A"))

        state.submit()
        harness.scope.drainAll()

        assertEquals("传给网络层的必须是屏幕上那串口令绑定的哈希", shaA, seenZipSha)
        harness.close()
    }

    // ── 驱动用的假 scope ────────────────────────────────────────────────────

    /** 把 [ModuleContributionDialogState] 和驱动它的 [SteppingScope] 绑在一起，省掉成对传参。 */
    private inner class Harness(
        submit: suspend (File, ModulePropInfo?, String, String, String, String, String) -> SubmitResult,
        lookupUid: suspend (String) -> CoolapkUidLookup = { CoolapkUidLookup.Verified("酷友") },
    ) {
        val scope = SteppingScope()
        // 算哈希用**另一个**调度器实例（但共用同一条队列）。必须是不同实例：
        // withContext 见到新旧 interceptor 相同会走 undispatched 快路径，哈希就地跑完，
        // 队列里就少了"哈希回来"这一步，陈旧写回那条断言也就没法把它按住。
        val state = ModuleContributionDialogState(scope, submit, lookupUid, { "正在提交…" }, scope.hashDispatcher)

        /** 开一次对话框，并把后台算出来的整包哈希写回表单（跑到静默）。 */
        fun openAndLandZipHash(zip: File = zipNamed("a.zip", "SUkISU-WIRING-A")) {
            state.open(zip, meta)
            scope.runNext() // launch 体开跑，哈希任务派进队列
            scope.runNext() // 哈希算完，写回续体入队
            scope.runNext() // 写回表单
        }

        /** 走到"该填的都填了、UID 核实通过、确认已勾"，即 canSubmit 为真。 */
        fun fillIn(zip: File) {
            openAndLandZipHash(zip)
            state.updateSource("https://a.example")
            state.updateConsent(true)
            // updateUid 带 500ms 防抖；retryUidLookup 是同一份核实逻辑的"立刻再查一次"入口，
            // 并且它顺手把那条防抖任务取消了 —— 于是这里不用真等半秒，等的也不是时间。
            state.updateUid("10086001")
            state.retryUidLookup()
            scope.drainAll()
            assertTrue(
                "前置条件：UID 得先核实通过，否则 canSubmit 为假，submit() 会直接 return",
                state.form.canSubmit,
            )
        }

        fun close() = scope.close()
    }

    /**
     * 手动挡的调度器兼 [CoroutineScope]：`scope.launch { … }` 与 `withContext(this)` 都不立刻跑，
     * 而是排进队列，由测试决定什么时候让哪一个续体执行。
     *
     * 为什么不用 runBlocking / Dispatchers.Unconfined：那两种都会让 open() 的后台哈希**并发**跑完，
     * 两次 open 的写回谁先落定取决于 IO 线程 —— 「陈旧写入被丢弃」那条断言就变成碰运气，
     * 而且顺序不巧时（陈旧那次先写、新那次后写覆盖掉它），把闸门删掉测试照样绿。
     * 这里由测试亲手把陈旧的那一次续体留在队列里，等最后一次正常写回落定之后再跑它。
     *
     * 同一个队列既承载 scope 的续体、也承载算整包哈希那一步，
     * 于是**没有任何一步需要等真实时间**：读盘也是队列里的一枚续体。
     * 早先那版把哈希留给 `Dispatchers.IO` 真线程池、用 5 秒上限等它回来，在 CI 的 2 核
     * runner 上跑到第 577 条测试时就会真的超时（Build Manager #73 两条失败正是这么来的）——
     * 「测试不依赖墙钟」不是洁癖，是这份测试能在慢机器上活下来的前提。
     *
     * 这个 scope 之所以要自己实现：`testImplementation` 只有 junit 与 org.json
     * （`manager/app/build.gradle.kts`），没有 kotlinx-coroutines-test 的 TestScheduler，
     * 而这个任务不许动 build 文件。
     */
    private class SteppingScope : CoroutineDispatcher(), CoroutineScope, AutoCloseable {
        private val tasks = ConcurrentLinkedDeque<Runnable>()
        private val job = SupervisorJob()

        override val coroutineContext: CoroutineContext = this + job

        /**
         * 交给 `withContext` 的调度器。必须是**另一个实例**：`withContext` 见到新旧
         * interceptor 是同一个对象时会走 undispatched 快路径，哈希就地跑完，
         * 队列里就少了「哈希回来」这一步，陈旧写回那条断言也就没法把它按住。
         */
        val hashDispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

            override fun dispatch(context: CoroutineContext, block: Runnable) {
                tasks.add(block)
            }
        }

        // 永远走 dispatch：于是一次 launch 的启动、一次 withContext 的进出都是可控的续体。
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.add(block)
        }

        /** 跑队首那枚续体（最早排进来的那一次「回到 scope」）。 */
        fun runNext() = runTask(tasks.pollFirst() ?: throw AssertionError("队列已空：这里没有可跑的续体"))

        /** 跑队尾那枚续体（最后排进来的）—— 用来让后发起的那一次先写回。 */
        fun runLatest() = runTask(tasks.pollLast() ?: throw AssertionError("队列已空：这里没有可跑的续体"))

        /** 把队列里排着的全跑完，走到静默（一次提交、一串 UID 核实这种多任务的入口用得上）。 */
        fun drainAll(bound: Int = 100) {
            var guard = 0
            while (true) {
                val task = tasks.pollFirst() ?: return
                if (++guard > bound) fail("续体跑不完（$bound 轮）：有协程在自己给自己派发")
                task.run()
            }
        }

        private fun runTask(block: Runnable) = block.run()

        override fun close() {
            job.cancel()
        }
    }
}
