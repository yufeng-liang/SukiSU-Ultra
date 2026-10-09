package com.sukisu.ultra.data.backup

import java.io.IOException
import java.io.InputStream

/**
 * 凭据被服务端拒绝（401 / 403）。
 *
 * 单独一个类型是为了让 UI 能把它翻译成"请检查是不是用了应用密码"，
 * 而不是把 `MKCOL -> HTTP 401` 这种原始信息丢给用户。
 */
class BackupAuthException(message: String) : IOException(message)

/**
 * 备份归档的持久化后端。路径都是相对后端根目录的路径。
 *
 * 接口按流设计：boot 镜像 32–96MB，任何把内容读成 ByteArray 的实现都会 OOM。
 * 所有操作通过 Result 报告失败，不抛异常——单个后端故障不能拖垮整批备份。
 * 但 [get] 返回的流在读取过程中仍会抛异常（内容损坏、传输中断），调用方按普通 IO 异常处理。
 */
interface BackupStorage {
    val id: String

    /** 校验后端可达且可写。 */
    suspend fun test(): Result<Unit>

    /**
     * 把 [open] 返回的流写到 [relativePath]，覆盖已有内容。
     * 实现必须保证 [open] 与 [size] 一致；[size] 用于让后端提前声明长度。
     */
    suspend fun put(relativePath: String, size: Long, open: () -> InputStream): Result<Unit>

    /** 返回读取用的流，调用方负责关闭；实现负责清掉为此落下的临时副本。 */
    suspend fun get(relativePath: String): Result<InputStream>

    suspend fun delete(relativePath: String): Result<Unit>

    /** 只用于 index.json / meta.json 这类小文件。 */
    suspend fun readText(relativePath: String): Result<String>

    suspend fun writeText(relativePath: String, text: String): Result<Unit>
}
