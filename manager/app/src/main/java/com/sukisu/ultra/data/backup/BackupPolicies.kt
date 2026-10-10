package com.sukisu.ultra.data.backup

import java.time.Instant

object DuplicatePolicy {
    /** 同一 kind 下 sha256 相同即视为已存在（内容未变的模块重复备份没有意义）。 */
    fun findDuplicate(existing: List<BackupEntry>, kind: BackupKind, sha256: String): BackupEntry? =
        existing.firstOrNull { it.kind == kind && it.sha256 == sha256 }
}

object RetentionPolicy {
    /**
     * 返回应删除的条目：同 kind 内按"哪一次备份"分堆，超出最近 [keep] 次的部分整堆删掉。
     *
     * 额度按**次**算，不按文件算。一次备份 11 个模块会写出 11 个归档，按文件数裁剪会当场删掉
     * 6 个：用户看到"已备份 11 项"，列表里却只剩 5 项，而那 6 个是他刚备份的。
     *
     * [keepEntries] 是本次刚写进后端的文件名，它们所在的分堆无条件保留。设备时钟正常时它们
     * 本来就最新，不需要这一层；时钟被往回调过时——这才是它的用处——"最近"会算到旧备份头上，
     * 刚写完的这一代当场被自己删掉。时钟不可信，所以不靠排序兜这个底，而是明确保护。
     *
     * 排序键取分堆里最新的 [BackupEntry.createdAt]（解析不出时间就退化成会话号/原字符串）：
     * 会话号是文件名里的 `yyyyMMdd_HHmmss`，文件名又是从 createdAt 生成的，两者同源，差别在于
     * 老数据/外部导入的文件名可能没有那个时间戳，按字符串比会把它们永远排在最新一侧。
     */
    fun expired(
        existing: List<BackupEntry>,
        kind: BackupKind,
        keep: Int,
        keepEntries: Set<String> = emptySet(),
    ): List<BackupEntry> {
        val sameKind = existing.filter { it.kind == kind }
        if (keep <= 0) return sameKind
        val protectedKeys = sameKind
            .filter { it.fileName in keepEntries }
            .mapTo(mutableSetOf()) { groupKeyOf(it) }
        // 刚写的这一代照样占额度，只是不许被删：留给别的备份的名额是 keep - protected。
        val room = (keep - protectedKeys.size).coerceAtLeast(0)
        return sameKind
            .groupBy { groupKeyOf(it) }
            .entries
            .filterNot { it.key in protectedKeys }
            .sortedWith(
                compareBy({ group -> group.value.maxOfOrNull { createdAtOf(it) } ?: Instant.EPOCH }, { it.key }),
            )
            .dropLast(room)
            .flatMap { it.value }
    }

    /** 一次备份 = 一个会话号（文件名里的时间戳）；没有时间戳的文件名退化成用 createdAt 当会话号。 */
    private fun groupKeyOf(entry: BackupEntry) = ArchiveNaming.sessionOf(entry.fileName) ?: entry.createdAt

    private fun createdAtOf(entry: BackupEntry): Instant =
        runCatching { Instant.parse(entry.createdAt) }.getOrDefault(Instant.EPOCH)

    /**
     * 按 kind 取额度：模块、boot 各一份，取值都由 [RetentionLimit] 钳过。
     *
     * 钳位放在这里而不是只放在界面上：这些额度是从 SharedPreferences 读出来的，手改过、从旧
     * 版本升上来、或被别的进程写过都有可能，而额度 0 意味着"下一次备份把自己的成果当场删光"。
     */
    fun keepFor(kind: BackupKind, moduleKeep: Int, bootKeep: Int): Int =
        if (kind == BackupKind.BOOT) RetentionLimit.clampBoot(bootKeep) else RetentionLimit.clampModule(moduleKeep)
}

/**
 * 保留额度的取值范围。
 *
 * 下限取 1 而不是 0：0 会让下一次备份把刚写进去的那一份当场删掉，用户看到的是"点了立即备份，
 * 什么都没留下"，而界面上并没有任何一个地方写着"我设成了 0"。真想不留备份，关掉备份就是了，
 * 不需要靠一个把结果自我删除的额度来表达。
 */
object RetentionLimit {
    const val MIN = 1
    const val MAX = 20

    /** boot 单张镜像 32–96MB，留 20 份就是近 2GB，上限比模块更紧。 */
    const val MAX_BOOT = 10

    fun clamp(value: Int, max: Int = MAX): Int = value.coerceIn(MIN, max)

    fun clampModule(value: Int): Int = clamp(value, MAX)

    fun clampBoot(value: Int): Int = clamp(value, MAX_BOOT)
}

/**
 * 一次备份用哪套保留额度：**每次备份现读**，不许缓存。
 *
 * 保留额度是活设置（设置页随时能改），`by lazy` 把引擎连同当时的额度一起缓存下来，用户改完
 * 设置本次会话就不生效——看着改了、备份照旧按旧数字裁。取值函数而不是直接传 Int，是为了让
 * "每次调用都重新读"这条纪律有一个能被单测盯住的地方（见 BackupPoliciesTest）。
 *
 * @return 模块与 boot 镜像各自的保留份数。
 */
internal fun backupRetentionNow(readModule: () -> Int, readBoot: () -> Int): Pair<Int, Int> =
    readModule() to readBoot()

object AutoBackupPolicy {
    /** 自动备份只覆盖模块，且必须显式开启；boot 镜像体积大且变化少，不做自动上传。 */
    fun shouldRun(enabled: Boolean, kind: BackupKind): Boolean = enabled && kind == BackupKind.MODULE

    /**
     * 这一次自动备份要写哪几处。
     *
     * 云端那一项是**与**不是**或**：勾了但地址没配好，写不了就是写不了，这里直接折掉，
     * 免得调用方还得自己再判断一遍。两处都不写时 [any] 为假，自动备份整体跳过——
     * 界面会说明原因（见 `backup_auto_dest_none`），不然开关开着却什么都没发生。
     */
    data class AutoBackupTargets(val local: Boolean, val cloud: Boolean) {
        val any: Boolean get() = local || cloud
    }

    fun targets(local: Boolean, cloud: Boolean, cloudConfigured: Boolean): AutoBackupTargets =
        AutoBackupTargets(local = local, cloud = cloud && cloudConfigured)

    /**
     * 本地与云端两次备份合起来算成功还是失败。
     *
     * 一边成功一边失败是 [AutoBackupOutcome.PARTIAL]，不是 FAILED：成功的那一份已经是可用的
     * 安全网，报"失败"会让用户以为什么都没备上；但另一份确实没有，也不能说成功。
     * 只写了其中一处时（另一处没勾、或没配云端）就只看那一处。
     */
    fun classify(local: BackupRunResult?, cloud: BackupRunResult?): AutoBackupOutcome {
        val runs = listOfNotNull(local, cloud)
        val ok = runs.count { it.failures.isEmpty() }
        return when {
            // 调用方应当先问 targets.any；真跑到这里说明一个目标都没写，只能算失败。
            runs.isEmpty() -> AutoBackupOutcome.FAILED
            ok == runs.size -> AutoBackupOutcome.OK
            ok == 0 -> AutoBackupOutcome.FAILED
            else -> AutoBackupOutcome.PARTIAL
        }
    }
}
