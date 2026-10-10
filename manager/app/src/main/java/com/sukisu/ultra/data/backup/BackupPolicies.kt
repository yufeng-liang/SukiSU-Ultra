package com.sukisu.ultra.data.backup

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
     * 6 个：用户看到"已写入 11 项"，列表里却只剩 5 项，而那 6 个是他刚备份的。回滚点不参与
     * 业务额度计算（它有自己的 [RollbackPolicy.expiredRollbacks]），否则拍一次回滚点就会挤掉
     * 一次业务备份。
     */
    fun expired(existing: List<BackupEntry>, kind: BackupKind, keep: Int): List<BackupEntry> {
        val sameKind = existing.filter { it.kind == kind && !RollbackPolicy.isRollback(it) }
        if (keep <= 0) return sameKind
        // 会话号就是备份那一刻的时间戳（yyyyMMdd_HHmmss），字典序即时间序；解析不出来的
        // 条目退化成用 createdAt 当会话号，至少不会和别的条目并成一次。
        return sameKind
            .groupBy { ArchiveNaming.sessionOf(it.fileName) ?: it.createdAt }
            .entries
            .sortedBy { it.key }
            .dropLast(keep)
            .flatMap { it.value }
    }

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

    /** 回滚点是"恢复到一半出问题"时才用得上的安全网，几份就够，多了只是占空间。 */
    const val MAX_ROLLBACK = 5

    fun clamp(value: Int, max: Int = MAX): Int = value.coerceIn(MIN, max)

    fun clampModule(value: Int): Int = clamp(value, MAX)

    fun clampBoot(value: Int): Int = clamp(value, MAX_BOOT)

    fun clampRollback(value: Int): Int = clamp(value, MAX_ROLLBACK)
}

/**
 * 回滚点：恢复模块/boot 之前，先把"当前那一项"导出成 `pre_restore_*` 留在本地后端。
 * 恢复失败或恢复后出问题时，用户可以再把这个回滚点恢复回去——root 工具里恢复出错等于变砖，
 * 这个安全网比多留几份业务备份值钱。
 */
object RollbackPolicy {
    const val PREFIX = "pre_restore_"

    fun isRollback(entry: BackupEntry): Boolean = entry.fileName.startsWith(PREFIX)

    fun rollbackNameFor(original: BackupEntry, timestamp: String): String =
        "$PREFIX${timestamp}_${original.fileName}"

    /**
     * 回滚点按"同一项"裁剪：每个 (kind, entryId) 只留最近 [keep] 份。
     *
     * 不能全局只留 1 份——恢复完模块 A 再恢复模块 B，会把 A 的回滚点一起删掉，
     * 用户以为有安全网其实已经被静默丢弃了。
     *
     * [keep] 由调用方给：它是个用户可调的额度，而这里不该知道偏好项从哪来。
     */
    fun expiredRollbacks(existing: List<BackupEntry>, keep: Int): List<BackupEntry> {
        val rollbacks = existing.filter { isRollback(it) }
        if (keep <= 0) return rollbacks
        return rollbacks
            .groupBy { it.kind to it.entryId }
            .values
            .flatMap { group -> group.sortedBy { it.createdAt }.dropLast(keep) }
    }
}

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
