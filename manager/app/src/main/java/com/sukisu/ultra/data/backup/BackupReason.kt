package com.sukisu.ultra.data.backup

/**
 * 一条诊断原因，用户可见。
 *
 * 数据层只产出"发生了什么"这个结构化事实，翻译成人话是 UI 层的事——`Context.getString`
 * 在 UI 层，这一层就能保持纯 JVM、单测不用碰 Android；同一条原因在通知、列表、摘要里
 * 也能各说各的（列表里要短，通知里要能独立读懂）。
 *
 * [External] 是唯一的逃生口：ksud 的 stderr、OkHttp 的异常文本这些第三方原文没法翻译，
 * 只能原样带上，由 UI 加一句本地化的前缀。**它进 UI 前必须过 [Redaction]**，构造时就抹掉
 * URL 里的凭据，渲染时再抹一次（见 `BackupText`）。
 *
 * 加新原因时注意：UI 层的 `when` 是穷尽的，漏了会编译不过——这是故意的，比运行时
 * 显示一句英文强。
 */
sealed interface BackupReason {

    /** 云端没配好就发起了云端操作。 */
    data object CloudNotConfigured : BackupReason

    /** 401 / 403。单独一类是因为它几乎总是"该填应用密码却填了登录密码"。 */
    data class CloudCredentialsRejected(val code: Int) : BackupReason

    /** 服务端返回了非 2xx。 */
    data class HttpFailed(val operation: HttpOperation, val path: String, val code: Int) : BackupReason

    /** 索引读不出来或解析不了。与 [ReadFailed] 分开：这是"文件在但结构坏了"。 */
    data class IndexUnreadable(val external: String?) : BackupReason

    /** 本地后端读文件失败。 */
    data class ReadFailed(val path: String, val external: String?) : BackupReason

    /** 写文件失败（含索引）。 */
    data class WriteFailed(val path: String, val external: String?) : BackupReason

    /** 删文件失败。保留策略裁剪时会用到；删不掉等于在盘上留孤儿。 */
    data class DeleteFailed(val path: String, val external: String?) : BackupReason

    /** 备份目录建不出来或不可写。 */
    data class DirectoryNotWritable(val path: String, val external: String?) : BackupReason

    /** 内容与记录的校验和不符。 */
    data class Corrupted(val path: String, val expected: String, val actual: String) : BackupReason

    /** 打包某个模块失败。 */
    data class ArchiveFailed(val entryId: String, val external: String?) : BackupReason

    /** 模块安装失败（恢复走的是 ksud 官方安装流程）。 */
    data class ModuleInstallFailed(val entryId: String, val external: String?) : BackupReason

    /** 模块装上了，但把禁用状态写回去失败。 */
    data class ModuleDisableFailed(val entryId: String, val external: String?) : BackupReason

    /** 引擎没有这个 kind 的源。 */
    data class NoSource(val kind: BackupKind) : BackupReason

    /** 归档没有边车 meta，无从知道它是什么，拒绝恢复。 */
    data object BootNoSidecarMeta : BackupReason

    /** 归档自称的身份与索引记录的不符。 */
    data class BootForeignStockImage(val recorded: String, val expected: String) : BackupReason

    /** 边车里没有校验和。 */
    data object BootNoChecksum : BackupReason

    /**
     * 本机没有这张原厂镜像。
     *
     * 必须由我们拒绝：ksud 找不到匹配文件时只打印一行 Warning，转而 `rebuild_without_ksu`
     * 并退出 0，也就是"报成功但什么都没恢复"。
     */
    data class BootStockImageMissing(val sha1: String) : BackupReason

    /** 内容算出的 sha1 与它自称的身份不符——伪造 meta + index 让任意镜像过关的形状。 */
    data class BootIdentityMismatch(val sha1: String) : BackupReason

    /** ksud boot-restore 失败。[external] 是它的 stderr。 */
    data class BootFlashFailed(val external: String?) : BackupReason

    /** 打不开用户选的文件。 */
    data object FileUnreadable : BackupReason

    /** 拿不到用户选中文件的文件名。 */
    data object FileNameUnresolved : BackupReason

    /**
     * 要收进来的内容和已有的备份一模一样。
     *
     * 导入是唯一"用户能对着同一个文件重复按"的入口：点两次的第二次只是把同一份内容再传一遍、
     * 列表里多一行。备份那条路不会走到这里——那边是跳过（[BackupRunResult.skipped]），因为一次
     * 备份打包了 11 个模块，其中几个没变是常态，整次拒绝等于什么都没备。
     *
     * [existing] 是已经存着的那份归档名，用户拿它去列表里对得上。
     */
    data class DuplicateContent(val existing: String) : BackupReason

    /** 第三方原文（ksud 输出、IO 异常）。UI 只能加一句本地化的前缀。 */
    data class External(val text: String) : BackupReason
}

/** HTTP 动作。翻成人话是"上传/下载/删除/创建目录"，不把 `PUT`/`MKCOL` 丢给用户。 */
enum class HttpOperation { CREATE_DIRECTORY, UPLOAD, DOWNLOAD, DELETE }

/**
 * 带结构化原因的异常。
 *
 * 存储、索引、导入这些路径是 `Result` / `getOrThrow` 风格，只能靠异常把原因带出来；
 * 让异常自己带上 [BackupReason]，上层就不必去正则匹配异常文本了。
 */
class BackupReasonException(val reason: BackupReason) : java.io.IOException(reason.toString())

/** 把异常还原成原因。第三方文本一律先脱敏，再进 [BackupReason.External]。 */
fun reasonOf(error: Throwable): BackupReason = when (error) {
    is BackupReasonException -> error.reason
    is BackupAuthException -> BackupReason.CloudCredentialsRejected(error.code)
    else -> BackupReason.External(externalText(error))
}

/** 第三方文本（ksud 输出、IO 异常）填进 [BackupReason] 的字段前一律先过这里。 */
fun externalText(error: Throwable): String =
    externalTextOrNull(error) ?: error.javaClass.simpleName

/**
 * 同上，但没话说时返回 null。
 *
 * 可选的 external 字段用这个：ksud 失败时 stderr 可能是空的，那时候该让 UI 只说
 * "安装失败"，而不是把 `IllegalStateException` 这种类名当原因塞给用户。
 */
fun externalTextOrNull(error: Throwable): String? =
    Redaction.redactMessage(error.message).takeIf { it.isNotBlank() }
