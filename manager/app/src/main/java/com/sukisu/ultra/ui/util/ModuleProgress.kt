/*
 * Copyright (C) 2025 SukiSU Ultra
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sukisu.ultra.ui.util

import com.sukisu.ultra.ksuApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 一份投稿此刻走到哪一步了。
 *
 * 与 [CheckStatus] 是两件事：那个回答「索引里有没有这一版」（静态事实），
 * 这个回答「这份投稿的收录流程到哪了」（有先后顺序的状态机）。
 */
enum class ProgressStage {
    /** 已提交：服务端收到了，还没开 PR（或者已经开了但用户看不到）。 */
    SUBMITTED,

    /** 待审核：PR 开着，等维护者看。 */
    REVIEWING,

    /** 已合并：PR 合进了索引仓库，但索引还没重新发布。 */
    MERGED,

    /** 已收录：索引已经发布，商店里应能搜到。 */
    PUBLISHED,

    /**
     * 未通过收录：被关掉或退回，[ProgressItem.reason] 里是给用户的理由。
     *
     * 界面把它画成「停在待审核」并把这个节点染成错误色，而不是在四步之外另开一步 ——
     * 被拒不是「进度更靠后」，而是这条路走到头了。
     */
    REJECTED,
}

/**
 * 一条投稿的进度。
 *
 * [versionCode] 是 **Int**（与 worker 契约一致），与索引/投稿记录里的 `Long` 比较时
 * 调用方自己 `.toLong()`；用 [progressKey] 建索引，避免两处各写一遍拼接格式。
 */
data class ProgressItem(
    val id: String,
    val versionCode: Int,
    val stage: ProgressStage,
    val prUrl: String?,
    val reason: String?,
    val updatedAt: Long,
    /**
     * 这条排在「最近 12 枚」之外、worker 没去查，stage 只是兜底值。
     *
     * 此时**不许**给 PR 链接（那一条根本不是这次查出来的结论），也不许说「已收录」。
     */
    val unverified: Boolean = false,
)

/** 拉进度这一趟的结果；失败只带一句话，不区分网络/协议 —— 界面对用户的说法是同一个。 */
sealed interface ProgressOutcome {
    data class Ok(val items: List<ProgressItem>, val degraded: Boolean = false) : ProgressOutcome

    data class Failed(val message: String) : ProgressOutcome
}

/** 进度接口的超时：与 `/check`、`/mine` 同一档（都是安装/查看时的附加一步）。 */
private const val PROGRESS_TIMEOUT_MS = 8_000L

/** 失败时给界面兜底的一句话；真正的用户文案由界面按 string 资源渲染。 */
private const val UNAVAILABLE = "progress_unavailable"

/**
 * 按 `id@versionCode` 建进度索引。
 *
 * 同一个模块投过多版时每一版各有自己的进度，只按 id 建索引会让「上一版已收录」
 * 盖住「这一版还在审核」。Long 传入是为了让调用方不必先转 Int。
 */
fun progressKey(id: String, versionCode: Long): String = "$id@$versionCode"

/**
 * 「去商店看看」只在**已收录**时出现。
 *
 * 抽成纯函数是为了能被单测钉住：一旦有人把判据改成 `stage >= MERGED`，
 * 用户点进商店却搜不到自己那一版，回来只会觉得这个入口在骗人。
 */
fun ProgressItem?.allowsStoreLink(): Boolean = this?.stage == ProgressStage.PUBLISHED

/**
 * 四步进度里某一节点此刻该画成什么样。
 *
 * 抽成纯函数（而不是在两个皮肤的 Composable 里各写一遍 if）：两套皮肤的配色不同，
 * 但「哪一格是走过的、哪一格是当前、哪一格是没到」必须是同一个答案，否则同一份数据
 * 在 Material 与 Miuix 上会讲出两段不同的进度。
 */
enum class ProgressNodeState {
    /** 已经走过：用次级文字色 + 连线。 */
    DONE,

    /** 当前所在：高亮 + 实心圆点。 */
    CURRENT,

    /** 还没到：灰。 */
    UPCOMING,

    /** 被拒：停在「待审核」并把这一格染成错误色。 */
    FAILED,
}

/** 「未通过收录」在步骤条里停在第几格（待审核）。 */
private const val REJECTED_NODE_INDEX = 1

/** 四步的序号：0 已提交 / 1 待审核 / 2 已合并 / 3 已收录。 */
fun ProgressStage.nodeIndex(): Int = when (this) {
    ProgressStage.SUBMITTED -> 0
    ProgressStage.REVIEWING -> 1
    ProgressStage.MERGED -> 2
    ProgressStage.PUBLISHED -> 3
    // 被拒不是「更靠后」，而是这条路走到头了；画在待审核那一格上。
    ProgressStage.REJECTED -> REJECTED_NODE_INDEX
}

/**
 * 第 [index] 格该画成什么。
 *
 * 没有进度数据（null）时四格全灰：这时候界面显示的是「查不到」，
 * 而不是替用户猜一个「已提交」。
 */
fun ProgressItem?.nodeStateOf(index: Int): ProgressNodeState {
    val stage = this?.stage ?: return ProgressNodeState.UPCOMING
    if (stage == ProgressStage.REJECTED) {
        return when {
            index < REJECTED_NODE_INDEX -> ProgressNodeState.DONE
            index == REJECTED_NODE_INDEX -> ProgressNodeState.FAILED
            else -> ProgressNodeState.UPCOMING
        }
    }
    val current = stage.nodeIndex()
    return when {
        index < current -> ProgressNodeState.DONE
        index == current -> ProgressNodeState.CURRENT
        else -> ProgressNodeState.UPCOMING
    }
}

/**
 * 进度弹层里的一行：某一版投稿 + 它此刻的进度（null = 这一次没查到）。
 *
 * 版本号与模块名来自 `/mine`（投稿记录），stage 来自 `/progress` —— 两个端点各答一半，
 * 在这里合起来，界面层就不必再各拼一次。
 */
data class ProgressVersion(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Long,
    val progress: ProgressItem?,
) {
    /** 「去商店看看」只认这一版真的已收录（见 [ProgressItem?.allowsStoreLink]）。 */
    val allowsStoreLink: Boolean
        get() = progress.allowsStoreLink()
}

/**
 * 把「选了哪几个模块」翻译成「要显示哪几行」。
 *
 * [ids] 是模块 id（入口长在模块卡上）；同一个模块投过多版时每一版都列出来，新的在上 ——
 * 用户要看的是「我投的这一版到哪了」，而不是一个模块一个汇总结论。
 * 不在这里排序去重之外的加工：查不到的版本保留，进度显示为空（界面画「查不到」）。
 */
fun progressVersions(
    modules: List<MineModule>,
    ids: List<String>,
    progress: Map<String, ProgressItem>,
): List<ProgressVersion> = ids.flatMap { id ->
    modules.asSequence()
        .filter { it.id == id }
        .sortedByDescending { it.versionCode }
        .map { module ->
            ProgressVersion(
                id = module.id,
                name = module.name,
                version = module.version,
                versionCode = module.versionCode,
                progress = progress[progressKey(module.id, module.versionCode)],
            )
        }
        .toList()
}

/**
 * 服务端的 `stage` 字符串 → 状态。
 *
 * 认不出的值一律兜底 [ProgressStage.SUBMITTED]：将来 worker 加一个新阶段（比如
 * `checks_failed`）时，旧客户端应当显示「已提交，还在走流程」，而不是掉进一个
 * 「未知」分支把整块进度藏起来。大小写与首尾空格都不敏感 —— 协议里写的是小写，
 * 但让客户端为一个空格崩掉判据没有意义。
 */
fun progressStageOf(raw: String): ProgressStage = when (raw.trim().lowercase()) {
    "pr_open" -> ProgressStage.REVIEWING
    "merged" -> ProgressStage.MERGED
    "published" -> ProgressStage.PUBLISHED
    "rejected" -> ProgressStage.REJECTED
    else -> ProgressStage.SUBMITTED
}

/**
 * 解析 `GET /progress` 的应答体；**任何形状问题都只返回失败，不抛**。
 *
 * 传 null（读不出 body）也走失败：调用点有一堆（提交成功后的顺带、历史页、
 * 30 秒轮询），任何一个抛出去都是一次崩溃，而它要回答的只是「查得到吗」。
 */
internal fun parseProgressBody(body: String?): ProgressOutcome {
    val json = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
        ?: return ProgressOutcome.Failed(UNAVAILABLE)
    if (!json.optBoolean("ok", false)) {
        val code = json.optString("code").trim()
        return ProgressOutcome.Failed(code.ifEmpty { UNAVAILABLE })
    }
    val array = json.optJSONArray("items")
    val items = ArrayList<ProgressItem>(array?.length() ?: 0)
    for (i in 0 until (array?.length() ?: 0)) {
        val row = array?.optJSONObject(i) ?: continue
        val id = row.optString("id").trim()
        if (id.isEmpty()) continue
        val unverified = row.optBoolean("unverified", false)
        val prUrl = row.optString("prUrl").trim()
        items.add(
            ProgressItem(
                id = id,
                versionCode = row.optInt("versionCode", 0),
                // 未核验的那几条按 pending 展示：worker 没去查，别把兜底值说成结论。
                stage = if (unverified) ProgressStage.SUBMITTED else progressStageOf(row.optString("stage")),
                // 同上：没核验过就不给 PR 链接，否则用户点过去看到的可能是别人的/不相干的 PR。
                prUrl = if (unverified) null else prUrl.takeIf { it.isNotEmpty() },
                reason = row.optString("reason").trim().takeIf { it.isNotEmpty() },
                updatedAt = row.optLong("updatedAt", 0L),
                unverified = unverified,
            )
        )
    }
    return ProgressOutcome.Ok(items, degraded = json.optBoolean("degraded", false))
}

/**
 * 查「我投过的这些现在到哪一步了」（`GET /progress?token=`，与 `/mine` 同一套令牌）。
 *
 * 连不上、超时、坏 JSON、401 一律返回 [ProgressOutcome.Failed] —— **不抛异常**：
 * 调用点在提交成功之后与轮询里，抛出去就是把「查不到进度」变成一次崩溃。
 * 令牌只进 query（服务端就是这么定的），与 [ModuleContribution.fetchMine] 同一个形状。
 */
suspend fun fetchProgress(token: String): ProgressOutcome = withContext(Dispatchers.IO) {
    val tok = token.trim()
    if (tok.isEmpty()) return@withContext ProgressOutcome.Failed(UNAVAILABLE)
    val url = runCatching {
        "${ModuleContribution.BASE_URL.trimEnd('/')}/progress".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("token", tok)
            ?.build()
            ?.toString()
    }.getOrNull() ?: return@withContext ProgressOutcome.Failed(UNAVAILABLE)
    val call = runCatching { client().newCall(Request.Builder().url(url).get().build()) }.getOrNull()
        ?: return@withContext ProgressOutcome.Failed(UNAVAILABLE)
    try {
        withTimeout(PROGRESS_TIMEOUT_MS) { execute(call) }.use { response ->
            if (!response.isSuccessful) return@withContext ProgressOutcome.Failed(UNAVAILABLE)
            val body = runCatching { response.body.string() }.getOrNull()
            parseProgressBody(body)
        }
    } catch (e: CancellationException) {
        // 只吞自己那次超时；调用方取消（弹层关了、作用域销毁）必须照常往外传。
        if (e is TimeoutCancellationException) ProgressOutcome.Failed(UNAVAILABLE) else throw e
    } catch (e: Throwable) {
        ProgressOutcome.Failed(UNAVAILABLE)
    }
}

/** 客户端可以被换掉（与 [ModuleContribution] 同一个来源），生产路径用应用里那一份。 */
private fun client(): OkHttpClient =
    runCatching { ksuApp.okhttpClient }.getOrElse { OkHttpClient() }

/**
 * 阻塞式执行，并把 OkHttp 自己的 socket 超时对齐到 [PROGRESS_TIMEOUT_MS]。
 *
 * 只靠外层 [withTimeout] 掐不住「连上了但服务端不回话」的请求：协程超时之后
 * socket 还会自己挂很久，占着线程。
 */
private fun execute(call: okhttp3.Call): Response {
    runCatching { call.timeout().timeout(PROGRESS_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
    return call.execute()
}
