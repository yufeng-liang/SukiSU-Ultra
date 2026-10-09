package com.sukisu.ultra.data.backup

import java.io.IOException
import java.io.InputStream

/**
 * 凭据被服务端拒绝（401 / 403）。
 *
 * 单独一个类型是为了让 UI 能把它翻译成"请检查是不是用了应用密码"，
 * 而不是把 `MKCOL -> HTTP 401` 这种原始信息丢给用户。[code] 留着是因为 401 和 403
 * 对用户意味着不同的事，而提示文案里要带上它。
 */
class BackupAuthException(val code: Int, message: String) : IOException(message)

/**
 * 备份归档的持久化后端。路径都是相对后端根目录的路径。
 *
 * 接口按流设计：boot 镜像 32–96MB，任何把内容读成 ByteArray 的实现都会 OOM。
 * 所有操作通过 Result 报告失败，不抛异常——单个后端故障不能拖垮整批备份。
 * 但 [get] 返回的流在读取过程中仍会抛异常（内容损坏、传输中断），调用方按普通 IO 异常处理。
 */
interface BackupStorage {
    val id: String

    /**
     * 这个后端会不会自己上报传输进度。
     *
     * 默认不会：那种情况下字节数由引擎包在**源流**上数（`open()` 被读掉多少就是推出去多少），
     * 本地后端走的就是这条路——它把内容拷到目标那一步是 `cp`，没有回调可用。
     *
     * WebDAV 会，而且必须由它自己报：它先让源流整份落到本地 staging、再把 staging 文件当请求体
     * 发出去，引擎包在源流上数到的只是那段本地拷贝（几十毫秒就完了），真正的上传期间一个字节都
     * 数不到。所以它按"写进 socket 的字节数"上报（见 [put] 的 `onProgress`）。
     */
    val reportsTransferProgress: Boolean get() = false

    /** 校验后端可达且可写。 */
    suspend fun test(): Result<Unit>

    /**
     * 把 [open] 返回的流写到 [relativePath]，覆盖已有内容。
     * 实现必须保证 [open] 与 [size] 一致；[size] 用于让后端提前声明长度。
     *
     * [onProgress] 只在 [reportsTransferProgress] 为真时被后端调用，参数是**这个文件**已经推出去
     * 的字节数（不含同一批里更早的那些文件）——累计交给引擎，后端不需要知道整批有多大。
     * 它排在 [open] 前面，是为了让 `put(path, size) { … }` 这种尾随 lambda 仍然绑到内容上。
     */
    suspend fun put(
        relativePath: String,
        size: Long,
        onProgress: (Long) -> Unit = {},
        open: () -> InputStream,
    ): Result<Unit>

    /** 返回读取用的流，调用方负责关闭；实现负责清掉为此落下的临时副本。 */
    suspend fun get(relativePath: String): Result<InputStream>

    suspend fun delete(relativePath: String): Result<Unit>

    /** 只用于 index.json / meta.json 这类小文件。 */
    suspend fun readText(relativePath: String): Result<String>

    suspend fun writeText(relativePath: String, text: String): Result<Unit>
}
