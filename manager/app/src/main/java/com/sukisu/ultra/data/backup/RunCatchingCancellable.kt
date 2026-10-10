package com.sukisu.ultra.data.backup

import kotlin.coroutines.cancellation.CancellationException

/**
 * 与 [runCatching] 一样把失败收进 [Result]，但**不吞协程取消**。
 *
 * 为什么不能直接用 runCatching：它连 [CancellationException] 也一起抓住，把这些 suspend + I/O
 * 路径上的取消降级成一次普通失败。后果不是"少一个错误提示"，而是取消被拖成了真实副作用：
 * 用户离开页面后任务照旧往下走完（继续上传、改索引、刷机），只在最后多报一条"失败"。
 * 取消必须原样上抛交给结构化并发收尾；其余异常仍然按 [Result] 返回，语义与 runCatching 一致。
 *
 * 纯同步的解析/取值路径（JSON、时间戳）不涉及取消，继续用 runCatching 就好。
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
