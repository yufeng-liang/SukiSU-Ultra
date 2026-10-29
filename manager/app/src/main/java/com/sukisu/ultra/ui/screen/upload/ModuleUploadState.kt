package com.sukisu.ultra.ui.screen.upload

import androidx.compose.runtime.Immutable
import com.sukisu.ultra.ui.component.CoolapkUidState
import com.sukisu.ultra.ui.component.SearchStatus
import com.sukisu.ultra.ui.util.BatchLimits
import com.sukisu.ultra.ui.util.CheckStatus
import com.sukisu.ultra.ui.util.UpdateEligibility
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
    /**
     * 「要更新的这一版归不归当前这个酷安 UID」——与 [status] 是两件事，别合并。
     *
     * [status] 只回答「索引里有没有这一版」（谁都能看），归属回答「这一版是谁的」（只有
     * 服务端知道，且要带着 UID 去问）。同一枚 `VERSION_UPDATE` 的包，原作者查到
     * [UpdateEligibility.MINE] 就能发更新，非原作者查到 [UpdateEligibility.NOT_MINE]
     * 必须被拦下——拦在本地而不是等服务端 403，
     * 是因为那一趟会把整包传完才被拒，白耗用户的流量与时间。
     *
     * 默认 [UpdateEligibility.UNKNOWN]（不知道）而不是 `NOT_MINE`（不是你的）：
     * 索引读不到、服务端没回这个字段的时段里，宁可放行后由服务端裁决，也不能让所有人都提交不了。
     */
    val eligibility: UpdateEligibility = UpdateEligibility.UNKNOWN,
    /**
     * 服务端对归属判断的原话（`reason` 字段），用来在界面上解释「为什么点不了」。
     *
     * 只做展示、不参与任何判据：判据只看 [eligibility]。留空是常态（`UNKNOWN` / `MINE`
     * 时服务端不必解释），界面此时用本地兜底句，不替服务端编原因。
     */
    val eligibilityReason: String = "",
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
    /**
     * 安装这个模块时有没有留下原始包（见 ModuleZipArchive）。
     *
     * 为 true 时这一行可以直接「用安装时的包」，不用再让用户去文件管理器里找路径；
     * 为 false 才退回手选。
     */
    val archived: Boolean = false,
    /**
     * 这一版的更新权归不归当前这个酷安 UID；语义与 [UploadZip.eligibility] 相同。
     *
     * 列表页也带着它，是为了让用户在挑包之前就看出「这枚是别人的」，而不是挑完、
     * 查完、点提交才在按钮上方吃到一句拒绝。默认 [UpdateEligibility.UNKNOWN]：
     * 列表里绝大多数行只是「看看」，不该因为没查过归属就渲染成别人的。
     */
    val eligibility: UpdateEligibility = UpdateEligibility.UNKNOWN,
    /** 服务端对归属的原话；只做展示，规则层判据只看 [eligibility]。 */
    val eligibilityReason: String = "",
)

/** 当前在哪一屏。身份 / 列表 / 表单 / 上传记录共用同一个路由，靠这个状态切换。 */
enum class UploadStep {
    /**
     * 身份页：填酷安 UID、看核验结果、勾统一承诺、抄口令并完成一次签名核验。
     *
     * 只在**手上没有有效令牌**时出现。核验通过就写下九十天的令牌并跳到 [LIST]，
     * 之后每次进来都直接是列表 —— 用户不必每次上传都重填身份，也不必每次都改一次酷安签名。
     * 主动「退出验证」后会回到这一屏。
     */
    IDENTITY,

    LIST,
    FORM,

    /** 上传记录：按模块汇总投稿过的版本。从列表顶栏的头像进，返回键回列表。 */
    HISTORY,
}

/**
 * 需要用户看清并确认的一次性提示。
 *
 * 与 [ModuleUploadListState.message] 分工：那条通道走 Toast，只留给一句话就能说完的
 * 结果（复制失败、超过上限）—— 它 3.5 秒就消失，用户没法回看。这里给的是**必须先读明白
 * 才能纠正**的拒绝理由，典型是「挑错了包」：两个模块 id 同时出现，Toast 在窄屏上会被
 * 系统截掉后半句，而用户还没来得及看清是哪一边错了。
 */
@Immutable
data class UploadNotice(
    val title: String,
    val body: String,
)

@Immutable
data class ModuleUploadListState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val checking: Boolean = false,
    val rows: List<UploadRow> = emptyList(),
    /**
     * 上一次扫描已安装模块失败了。
     *
     * 与「一个模块都没装」必须分开：失败时 [rows] 会保留上一次的结果，
     * 列表空着也不能画「装个模块再来看看」，孤儿包清理更不能按空名单去删。
     */
    val scanFailed: Boolean = false,
    val filter: ModuleUploadRules.Filter = ModuleUploadRules.Filter.ALL,
    val query: String = "",
    /**
     * 顶栏搜索状态机（展开/收起/文本），与上游模块页、仓库页同一套 [SearchStatus]。
     * [query] 仍由 VM 在同步搜索状态时一并写入，规则层筛选只认它。
     */
    val searchStatus: SearchStatus = SearchStatus(""),
    val limits: BatchLimits? = null,
    /** 还有多少条没查过（>0 时显示「继续查」）。 */
    val remaining: Int = 0,
    /**
     * 这一屏的收录结论全部来自缓存（[ModuleUploadCheckCache]）——这一次一条请求都没发。
     *
     * 存在只为了一件事：进度那一行得说清楚这批读数是什么时候的。没有它的话，
     * 「已查 20/20」在一瞬间出现、中间又没有进度条走过，用户没法区分「刚查的」和
     * 「半小时前的」；而点顶栏刷新是绕过缓存重查的，他要能判断自己该不该点。
     */
    val fromCache: Boolean = false,
    /** 展开详情的那一行；null 表示都收起。 */
    val expandedId: String? = null,
    /** 正在为哪一行挑 zip（挑的过程中禁掉重复点击）。 */
    val pickingFor: String? = null,
    /**
     * 一句话就能说完的一次性提示（复制失败、超过上限），消费后清空。
     *
     * 需要对照两个 id / 两个版本号才看得懂的拒绝理由不走这里，走 [UploadNotice]：
     * Toast 在窄屏上会被截断，而且到点自己就消失了。
     */
    val message: String? = null,
    /** 「安装时留存原始 zip」开关的当前值（见 ModuleZipArchive）。 */
    val keepZip: Boolean = true,
    /**
     * 已留存的包数与总字节数，给「清理留存的包」入口显示占用。
     *
     * 拆成两个 Long/Int 而不是塞一个 ModuleZipArchive.Usage 进来：状态类只放
     * 不可变基元，Compose 的比较与 remember 都省事。
     */
    val archivedCount: Int = 0,
    val archivedBytes: Long = 0L,
    /**
     * 其中已经不属于任何已安装模块的那几份（卸载后遗留）。
     *
     * 这些包在列表里没有行可以点「用留存的包」，只会白占空间；给它们一个单独的清理入口，
     * 用户就不必为了回收它们而「清空全部」、连带删掉还想留的那些。
     */
    val orphanCount: Int = 0,
) {
    /**
     * 顶栏是不是「正在刷新」——进度条要不要出现、刷新键要不要转，都只认这一个。
     *
     * 三段合成一个：本地扫描（[loading]）、手动刷新（[refreshing]）、网络查询
     * （[checking]）。只看 [refreshing] 的话，扫描结束那一刻它就先落了 false，
     * 而查询才刚开始 —— 用户看到的是「转了一下就不转了」，下一次刷新要等好几秒
     * 才有东西可看，很容易以为没点上。
     */
    val busy: Boolean
        get() = loading || refreshing || checking

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
    /**
     * 心跳时刻（毫秒），由 ViewModel 的 ticker 每 [TICK] 刷新一次；0 表示还没跳过。
     *
     * 它存在只为一件事：[sessionActive] 能「自己」变成 false。没有它的话，判据里读的是
     * `System.currentTimeMillis()`，而那个值变了**不会**触发重组：用户停在表单上跨过到期
     * 时刻，界面照样写着「已验证」、提交按钮照样亮着，点下去 `submit()` 才发现判据已假、
     * 静默 return —— 屏幕上什么都不会发生。反过来，时钟比服务端快的设备一进页面就判失效，
     * 而「退出验证」按钮只在 `sessionActive` 为真时才显示，令牌连清都清不掉。
     *
     * 为 0 时 [sessionActive] 回落到 `System.currentTimeMillis()`：状态刚建、ticker 还没跳
     * 第一下（或这个状态是测试里手搓的）时判据必须照常工作。
     */
    val nowTick: Long = 0L,
    val submitting: Boolean = false,
    /** 本次要上传的总字节数，提交中显示（契约没有字节回调，见 ViewModel 注释）。 */
    val totalBytes: Long = 0L,
    val outcome: UploadOutcome? = null,
    /** 人机校验没完成 / 超时之类的一次性提示。 */
    val notice: String? = null,
    /** 正在拿签名换令牌（身份页那个按钮按下之后）。 */
    val verifying: Boolean = false,
    /**
     * 上一次核验失败的原因，直出界面。
     *
     * 与 [notice] 分开：那条是 Toast，3.5 秒就没；核验失败的文案（「口令没在签名里找到」
     * 「这个口令已经用过」）必须留在原地让用户对着改，所以单独一个字段。
     */
    val verifyError: String? = null,
    /**
     * 身份页拿去当锚点的那一枚包（只用到 id 与整包 sha256，不会进 [items]）。
     *
     * 口令必须绑一套具体的包才不可重放（酷安签名是公开的，只绑 UID 等于谁看见谁能冒充）。
     * 优先用留存包，用户手上没有留存包时由界面走一次 SAF 挑一枚。
     */
    val verifyItem: UploadZip? = null,
) {
    /**
     * 手里这张令牌此刻能不能顶掉「改签名」那一步。
     *
     * 判据是**形状 + 到期**，不看服务端是否确认过：本地改 `expiresAt` 延长不了任何东西
     * （服务端那一份才是作数的），所以这里宽一点只影响「App 让不让你点提交」，
     * 不影响任何安全边界 —— 令牌真失效时服务端会拒，界面再回落签名路。
     */
    val sessionActive: Boolean
        get() = session?.validAt(if (nowTick > 0L) nowTick else System.currentTimeMillis()) == true

    /** 令牌绑的 UID 与当前输入框里的 UID 是同一个 —— 换了号就不认这张令牌。 */
    val sessionMatchesUid: Boolean
        get() = sessionActive && session?.uid == uid.trim()

    /**
     * 身份页能不能点「验证身份」。
     *
     * 与 [canSubmit] 分开：那一套是「上传」的门，这里是「换令牌」的门，只要集齐三件事
     * ——UID 形状像样、统一承诺勾了、手上有那枚做锚点的包。等级、签名里有没有口令这些
     * 只有服务端说了算，不在本地预判。
     */
    val canVerify: Boolean
        get() = verifyItem != null && consent && !verifying && !sessionChecking &&
            uid.trim().length in 4..12

    /**
     * 身份页那串口令：只绑 [verifyItem] 这一枚包。
     *
     * 与表单页的 [proof] 是同一套算法（[ModuleUploadRules.proofSnapshot]），但锚点不同 ——
     * 身份页走到这里时 [items] 通常还是空的（先验证身份、再挑要传的包），所以不能复用 [proof]。
     * 为 null 表示还没挑锚点包。
     */
    val verifyProof: ModuleUploadRules.ProofSnapshot?
        get() = verifyItem?.let { ModuleUploadRules.proofSnapshot(listOf(it), nonce) }

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
        ModuleUploadRules.canSubmit(
            items = items,
            uidVerified = uidVerified,
            consent = consent,
            signatureValid = signatureValid,
            submitting = submitting,
            limitProblem = ModuleUploadRules.limitProblem(items.size, items.map { it.sizeBytes }, limits),
            sessionActive = sessionMatchesUid,
            submitted = outcome is UploadOutcome.Success,
        )

    /** 按钮为灰的全部原因；文案由界面翻译（见 [ModuleUploadRules.submitBlockers]）。 */
    fun blockers(limits: BatchLimits?): List<ModuleUploadRules.SubmitBlocker> =
        ModuleUploadRules.submitBlockers(
            items = items,
            uidVerified = uidVerified,
            consent = consent,
            signatureValid = signatureValid,
            submitting = submitting,
            limitProblem = ModuleUploadRules.limitProblem(items.size, items.map { it.sizeBytes }, limits),
            sessionActive = sessionMatchesUid,
            submitted = outcome is UploadOutcome.Success,
        )
}
