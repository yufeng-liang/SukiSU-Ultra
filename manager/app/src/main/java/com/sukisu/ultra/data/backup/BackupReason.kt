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

    /**
     * 索引里的模块 id 形状不可用，拒绝拿它去拼 ksud 命令。
     *
     * 恢复禁用状态走的是 `ksud module disable <id>`，id 直接来自索引（共享存储或远端，外部可写）
     * 并被拼进以 root 身份执行的 shell。同仓库对同类输入都有白名单（boot 的 sha1、文件名的
     * `safeFileName`），模块 id 这一处也一样：形状不对就拒绝执行，而不是"转义一下再试"。
     * 与 [ModuleDisableFailed] 分开：那个是"命令跑了但失败"，这个是"根本没敢跑"。
     */
    data class ModuleIdUnusable(val entryId: String) : BackupReason

    /**
     * 备份里的包不是这个模块的 —— 读不出 `module.prop`，或者里面写的 id 和条目对不上。
     *
     * 这两个包名都来自可写位置（共享存储 / WebDAV / 导入的归档），别人换一个同名 zip 进去
     * 就能让「恢复 A」实际装上 B；`setDisabled` 还会照着 A 的 id 去执行。所以装之前先认包，
     * 认不出就整条拒收 —— 与 [ModuleIdUnusable] 同一套「形状不对就别动手」的道理。
     */
    data class ModuleZipUnusable(val entryId: String) : BackupReason

    /**
     * 这个模块已被标记为「下次开机删除」，所以没有打包。
     *
     * 打进去的后果很具体：用户下次开机看着它消失，之后若从这份备份恢复，它又回来了。
     * 备份是「当前状态的快照」，一个正在退场的模块不属于这个快照。
     */
    data class ModulePendingRemoval(val entryId: String) : BackupReason

    /**
     * 仓库源配置归档解析不出来或字段不合法，拒绝整体覆盖。
     *
     * 恢复仓库源配置走的是"整批替换"：解析到一半的残缺列表一旦写下去，用户已有的源就没了。
     * 所以解析失败、缺 url、url 全空都算失败——宁可让用户拿着这份归档来反馈，也不静默清空。
     */
    data class RepoConfigInvalid(val external: String?) : BackupReason

    /** 引擎没有这个 kind 的源。 */
    data class NoSource(val kind: BackupKind) : BackupReason

    /**
     * 这是一份已经下线了的功能留下的归档（当前只有旧的「原厂 boot 镜像」）。
     *
     * 与 [NoSource] 分开是为了给出可执行的解释：`NoSource` 说的是"这个版本没有该类内容的
     * 备份源"，用户听完不知道该怎么办；而这一条能直接告诉他"该功能已被分区快照取代"。
     *
     * 为什么导入时必须**拒绝**而不是收下：收下就等于在索引里登记一条永远恢复不了的记录，
     * 它还会以"模块"的身份出现在列表里（列表按 kind 分派，退役的 kind 落到默认分支），
     * 用户既恢复不了也删不掉。同 [BackupEngine] 里"边车 meta 写失败就不登记"的道理。
     */
    data class LegacyKindUnsupported(val kind: BackupKind) : BackupReason

    /**
     * 读分区失败：`dd` 起不来、分区路径不存在、或写出来的文件是空的。
     *
     * [partition] 是带槽位的分区名（`boot_a`），[external] 是 dd 的 stderr。
     */
    data class PartitionDumpFailed(val partition: String, val external: String?) : BackupReason

    /** 写分区失败：解只读、dd 写入或 fsync 任一步失败。[external] 是 shell 的 stderr。 */
    data class PartitionFlashFailed(val partition: String, val external: String?) : BackupReason

    /**
     * 本机没有这个分区。
     *
     * 恢复时必须由我们拒绝：归档里记的是 `init_boot_a`，而当前设备可能压根没有 init_boot
     * （android12 之前的内核把 root 放在 boot 里）。这时候"照着路径刷"就是往一个不存在的
     * 设备节点写，dd 会自己建出一个普通文件，看着成功、重启后毫无变化。
     */
    data class PartitionMissing(val partition: String) : BackupReason

    /**
     * 拍快照时的分区大小和现在不一致。
     *
     * 与 [PartitionSizeMismatch] 分开：那个说的是"归档内容不对"，这个说的是"分区本身变了"。
     * 混用会让用户以为镜像损坏，去重新备份一份同样大小不符的快照；真实原因往往是这份快照
     * 来自另一台设备，或者设备的分区表被重划过。
     */
    data class PartitionTableChanged(
        val partition: String,
        /** 现在这个分区多大。 */
        val current: Long,
        /** 拍快照时这个分区多大。 */
        val snapshot: Long,
    ) : BackupReason

    /**
     * 归档解压后的字节数与分区大小不符。
     *
     * 这是刷分区前最后一道闸：boot 分区 96MB，写进去 8MB 的 init_boot 镜像会把分区头部
     * 覆盖成另一个镜像的内容，设备直接起不来。宁可拒绝，也不"试试看"。
     */
    data class PartitionSizeMismatch(
        val partition: String,
        val expected: Long,
        val actual: Long,
    ) : BackupReason

    /**
     * 索引里的分区名形状不可用，拒绝拿它去拼 dd 命令。
     *
     * 名字来自边车 meta（共享存储或远端 WebDAV，外部可写）并被拼进以 root 执行的 shell；
     * 同 [ModuleIdUnusable] 的道理，形状不对就拒绝执行，而不是转义一下再试。
     */
    data class PartitionNameUnusable(val partition: String) : BackupReason

    /**
     * 归档没有边车 meta，无从知道它该刷回哪个分区。
     *
     * 分区快照的 meta 是**唯一**的身份来源：文件名只用来给用户看，恢复时的分区名、大小、
     * 是否压缩全在 meta 里。没有它就只能拒绝——猜一个分区名去 dd，等于蒙着眼睛刷机。
     */
    data object PartitionNoSidecarMeta : BackupReason

    /** 打不开用户选的文件。 */
    data object FileUnreadable : BackupReason

    /** 用户选择的导入归档超过允许大小。 */
    data class ImportTooLarge(val path: String, val limitBytes: Long) : BackupReason

    /** 导入阶段生成的备份索引提交失败；文件回滚后没有新增条目。 */
    data class ImportIndexWriteFailed(val external: String?) : BackupReason

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
