package com.sukisu.ultra.ui.screen.upload

import androidx.compose.runtime.Immutable
import com.sukisu.ultra.ui.component.CoolapkUidState
import com.sukisu.ultra.ui.util.BatchLimits
import com.sukisu.ultra.ui.util.CheckStatus
import com.sukisu.ultra.ui.util.UploadSession
import com.sukisu.ultra.ui.util.makeNonce

/**
 * 一个已经落进 staging 的待上传 zip。
 *
 * 只存路径不存 [java.io.File]：状态要能被 Compose 当作不可变值比较，也方便在
 * 进程重建后按路径重新打开。sha256 在 staging 时就算好（表单上的口令绑它），
 * 空串只可能出现在「还没算完」的中间态，此时口令行不显示。
 */
@Immutable
data class UploadZip(
    val path: String,
    val fileName: String,
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Long,
    val author: String,
    val description: String,
    val sha256: String,
    val sizeBytes: Long,
    val source: String = "",
    /** 手选包自己的收录状态；null = 还没查，checking = 正在查。 */
    val status: CheckStatus? = null,
    val checking: Boolean = false,
)

/** 列表里的一行。status 为 null 表示还没查（被上限截住或刚进页面）。 */
@Immutable
data class UploadRow(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val versionCode: Long,
    val description: String,
    val enabled: Boolean,
    val remove: Boolean,
    val metamodule: Boolean,
    val status: CheckStatus? = null,
    val checking: Boolean = false,
)

/** 当前在哪一屏。列表与表单共用同一个路由，靠这个状态切换。 */
enum class UploadStep {
    LIST,
    FORM,
}

@Immutable
data class ModuleUploadListState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val checking: Boolean = false,
    val rows: List<UploadRow> = emptyList(),
    val filter: ModuleUploadRules.Filter = ModuleUploadRules.Filter.ALL,
    val query: String = "",
    val limits: BatchLimits? = null,
    /** 还有多少条没查过（>0 时显示「继续查」）。 */
    val remaining: Int = 0,
    /** 展开详情的那一行；null 表示都收起。 */
    val expandedId: String? = null,
    /** 正在为哪一行挑 zip（挑的过程中禁掉重复点击）。 */
    val pickingFor: String? = null,
    /** 一次性提示（staging 失败、id 不一致等），消费后清空。 */
    val message: String? = null,
) {
    /** 界面直接渲染的那一份（筛选 + 搜索 + 排序）。 */
    val visibleRows: List<UploadRow>
        get() {
            if (rows.isEmpty()) return emptyList()
            // 规则层只认 id / name / status 三个字段；映射回来时按 id 取回整行。
            val byId = rows.associateBy { it.id }
            return ModuleUploadRules.visibleRows(
                rows.map { ModuleUploadRules.RowView(it.id, it.name, it.status) },
                filter,
                query,
            ).mapNotNull { byId[it.id] }
        }
}

/** 提交结果，成功/失败分开；失败带服务端给的原因。 */
@Immutable
sealed interface UploadOutcome {
    @Immutable
    data class Success(val message: String, val prUrl: String?) : UploadOutcome

    @Immutable
    data class Failure(val message: String, val proofUsed: Boolean = false) : UploadOutcome
}

@Immutable
data class ModuleUploadFormState(
    val items: List<UploadZip> = emptyList(),
    val uid: String = "",
    val uidState: CoolapkUidState = CoolapkUidState.Empty,
    val consent: Boolean = false,
    /**
     * 用户点「我已写进签名」时那一刻的集合指纹。
     * 与当前指纹不一致（或为 null）即视为没确认——集合变了，口令要重抄。
     */
    val confirmedSetKey: String? = null,
    val nonce: String = makeNonce(),
    /**
     * 图标墙上「正在编辑的那一枚」。
     *
     * 只在界面上用：墙把这一枚放大、描边，下面只画这一枚的详情卡。为 null 时
     * （比如刚删掉当前枚）由界面回落到第一枚。空值不影响任何提交判据。
     */
    val selectedPath: String? = null,
    /**
     * 本地存的那一张长期令牌（九十天免改签名）。
     *
     * 只放**已核实过且未到期**的那一份；到期、被吊销、形状坏掉的一律是 null，
     * 界面按「没有令牌」渲染（回到改签名那条路）。
     */
    val session: UploadSession? = null,
    /** 正在问服务端「这张令牌还在不在」（进页面时那一次）。 */
    val sessionChecking: Boolean = false,
    val submitting: Boolean = false,
    /** 本次要上传的总字节数，提交中显示（契约没有字节回调，见 ViewModel 注释）。 */
    val totalBytes: Long = 0L,
    val outcome: UploadOutcome? = null,
    /** 人机校验没完成 / 超时之类的一次性提示。 */
    val notice: String? = null,
) {
    /**
     * 手里这张令牌此刻能不能顶掉「改签名」那一步。
     *
     * 判据是**形状 + 到期**，不看服务端是否确认过：本地改 `expiresAt` 延长不了任何东西
     * （服务端那一份才是作数的），所以这里宽一点只影响「App 让不让你点提交」，
     * 不影响任何安全边界 —— 令牌真失效时服务端会拒，界面再回落签名路。
     */
    val sessionActive: Boolean
        get() = session?.validAt(System.currentTimeMillis()) == true

    /** 令牌绑的 UID 与当前输入框里的 UID 是同一个 —— 换了号就不认这张令牌。 */
    val sessionMatchesUid: Boolean
        get() = sessionActive && session?.uid == uid.trim()

    /** 当前集合 + nonce 算出来的口令；sha 没齐时 text 为空。 */
    val proof: ModuleUploadRules.ProofSnapshot?
        get() = if (ModuleUploadRules.proofReady(items)) {
            ModuleUploadRules.proofSnapshot(items, nonce)
        } else {
            null
        }

    /** 「我已写进签名」当前是否仍然有效。 */
    val signatureValid: Boolean
        get() = proof?.let { ModuleUploadRules.signatureStillValid(confirmedSetKey, it.setKey) } == true

    val uidVerified: Boolean
        get() = uidState is CoolapkUidState.Verified

    val nickname: String
        get() = (uidState as? CoolapkUidState.Verified)?.nickname.orEmpty()

    /** 提交按钮的最终判据（限制由调用方另行传入，见 [canSubmit]）。 */
    fun canSubmit(limits: BatchLimits?): Boolean =
        outcome !is UploadOutcome.Success && ModuleUploadRules.canSubmit(
            items = items,
            uidVerified = uidVerified,
            consent = consent,
            signatureValid = signatureValid,
            submitting = submitting,
            limitProblem = ModuleUploadRules.limitProblem(items.size, items.map { it.sizeBytes }, limits),
            sessionActive = sessionMatchesUid,
        )
}
