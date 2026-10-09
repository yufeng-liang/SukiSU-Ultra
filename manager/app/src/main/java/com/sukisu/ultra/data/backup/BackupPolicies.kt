package com.sukisu.ultra.data.backup

object DuplicatePolicy {
    /** 同一 kind 下 sha256 相同即视为已存在（内容未变的模块重复备份没有意义）。 */
    fun findDuplicate(existing: List<BackupEntry>, kind: BackupKind, sha256: String): BackupEntry? =
        existing.firstOrNull { it.kind == kind && it.sha256 == sha256 }
}

object RetentionPolicy {
    /**
     * 返回应删除的条目：同 kind 内按 createdAt 升序，超出最近 [keep] 份的部分。
     * 回滚点不参与业务额度计算（它有自己的 [RollbackPolicy.expiredRollbacks]），
     * 否则拍一次回滚点就会挤掉一份业务备份。
     */
    fun expired(existing: List<BackupEntry>, kind: BackupKind, keep: Int): List<BackupEntry> {
        val sameKind = existing
            .filter { it.kind == kind && !RollbackPolicy.isRollback(it) }
            .sortedBy { it.createdAt }
        if (keep <= 0) return sameKind
        return sameKind.dropLast(keep)
    }

    /** 按 kind 取额度：模块默认 5 份，boot 默认 2 份（单张原厂镜像 32–96MB）。 */
    fun keepFor(kind: BackupKind, moduleKeep: Int, bootKeep: Int): Int =
        if (kind == BackupKind.BOOT) bootKeep else moduleKeep
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
     */
    fun expiredRollbacks(existing: List<BackupEntry>, keep: Int = 1): List<BackupEntry> {
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
}
