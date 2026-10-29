package com.sukisu.ultra.ui.screen.upload

import android.content.Context
import androidx.core.content.edit
import com.sukisu.ultra.ksuApp
import com.sukisu.ultra.ui.util.CheckStatus
import com.sukisu.ultra.ui.util.UpdateEligibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 收录查询（`POST /check`）结果的短时缓存。
 *
 * ── 为什么要有 ────────────────────────────────────────────────────────────────
 *
 * 每进一次这一页都会把「已装的模块各有几版」整批问一遍：装了 20 个模块、服务端单批
 * 上限 8 条，就是三次请求。而这一页的进出极其频繁（挑个包、回列表、再进表单），
 * 用户数量一多，这份流量几乎全是重复的——同一个模块、同一个版本，前一分钟刚问过。
 *
 * 缓存解决两件事：
 * - **不再重复问**：TTL 内直接拿上次的结论，一次请求都不发；
 * - **首帧就有标签**：以前进页面先是三秒空标签再一次性刷出来，现在进页面即定。
 *
 * ── TTL 是这条链上唯一的取舍 ─────────────────────────────────────────────────
 *
 * 收录状态确实会变（别人刚传了同一版、PR 刚合），所以缓存必须过期。取 [TTL_MS]
 * 的理由：投稿是「一个人一天几次」的操作，慢半小时知道「已收录」不会让人做错事；
 * 而半小时内的反复进出才是真正把接口打爆的那部分流量。
 *
 * 两点保证它不会变成「假数据」：
 * - **手动刷新绕过缓存**（`ModuleUploadViewModel.refresh()` 强制重查），用户想要最新
 *   结论时随时能要；
 * - 条目的键是**模块 id**，versionCode 存在值里、命中后要逐条比对：模块一升级，
 *   旧结论自动作废，不会拿上一版的结论回答这一版。
 *
 * ── 为什么落盘而不是只放内存 ─────────────────────────────────────────────────
 *
 * 进程随时会被系统回收，而「关掉再打开」恰恰是最典型的重进路径。只放内存的话，
 * 每一次冷启动都是一轮全量查询——缓存挡掉的正好是最该挡掉的那一半。
 *
 * 存的只有收录状态、归属、服务端原话与时刻，没有令牌、没有文件内容；
 * 文件被云备份拷走也无非是「别的设备上少问一次」，不构成凭据。
 */
object ModuleUploadCheckCache {

    /**
     * 条目有效期。改这个值前先想清楚「半小时前的『未收录』会不会让人传一份重复的包」：
     * 会的话就往短了调，别往长了调。
     */
    const val TTL_MS = 30 * 60 * 1000L

    /** 独立的 prefs 文件：缓存体积会随安装量长大，不该混进 `settings`。 */
    private const val PREFS_NAME = "module_upload_check_cache"
    private const val KEY_BLOB = "blob"

    /** 落盘格式版本。字段增删/改序时 +1，旧格式会被整份丢弃（缓存丢了只是重新问一次）。 */
    private const val SCHEMA = 1

    /** 条数上限。
     *
     * 键是模块 id（versionCode 在值里）：模块升级就地覆盖同一条，但已经卸载的模块
     * 会留下旧条目，所以条数可能比「装了几个模块」多。
     * 200 条对得上装 100 个模块、各留一版旧记录；超了按时间砍最老的。
     */
    private const val MAX_ENTRIES = 200

    /**
     * 写入串行化。
     *
     * 查询是两批并发的（`ModuleUploadRules.CHECK_CONCURRENCY`），而这个文件是
     * 「读整份 → 合并 → 写整份」：两个批次同时走完就会互相覆盖，晚写的赢，
     * 先写的那批条目凭空消失。缓存丢一条只是下次多问一次，但没必要留这个坑。
     */
    private val writeLock = Mutex()

    /**
     * 一条查询结论。
     *
     * [uid] 一起存是必须的：[eligibility] 是「这一版归不归这个 UID」的答案，换个号再读
     * 同一份缓存，`status` 仍然有效、归属却不再成立。读的人据此把归属退回 UNKNOWN
     * （见 `ModuleUploadViewModel.applyCached`），而不是把前一个人的权限借给下一个人。
     */
    data class Entry(
        val versionCode: Long,
        val status: CheckStatus,
        /** 问出这条结论时带的 UID；空串表示当时没带（归属必为 UNKNOWN）。 */
        val uid: String,
        val eligibility: UpdateEligibility,
        val reason: String,
        /** 查询时刻（ms）。 */
        val at: Long,
    )

    /** 读一份还没过期的结论。过期项在这里就被剔掉，调用方不必再判时间。 */
    suspend fun read(now: Long = System.currentTimeMillis()): Map<String, Entry> =
        withContext(Dispatchers.IO) { decode(rawBlob(), now) }

    /**
     * 把这一批新结论合并进缓存。
     *
     * 合并而不是覆盖：这一趟只查了 N 条，整份覆盖会把别的模块还没过期的结论一起丢掉，
     * 下次进页面又得重问。
     */
    suspend fun write(updates: Map<String, Entry>, now: Long = System.currentTimeMillis()) {
        if (updates.isEmpty()) return
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                val merged = LinkedHashMap(decode(rawBlob(), now))
                merged.putAll(updates)
                runCatching { prefs()?.edit { putString(KEY_BLOB, encode(trim(merged))) } }
            }
        }
    }

    /**
     * 时刻 [at] 的结论在 [now] 这一刻还作不作数。
     *
     * 设备时钟往回跳时 `now - at` 会是负数：这里按「还没过期」处理。反过来的话，
     * 一次时区/校时抖动就能让整份缓存作废、把所有用户同时赶去查一遍——
     * 而缓存作废本来只是「多问一次」，这里选的是一边代价明显更小的那一边。
     */
    fun isFresh(at: Long, now: Long): Boolean = at > 0L && now - at < TTL_MS

    /** 超出 [MAX_ENTRIES] 时按时间砍最老的。 */
    internal fun trim(entries: Map<String, Entry>): Map<String, Entry> {
        if (entries.size <= MAX_ENTRIES) return entries
        return entries.entries
            .sortedByDescending { it.value.at }
            .take(MAX_ENTRIES)
            .associateTo(LinkedHashMap<String, Entry>()) { it.key to it.value }
    }

    /**
     * 解析落盘的那份 JSON。
     *
     * 一条坏数据只丢它自己：字段缺失、枚举名不认识、时间戳过期，都按「没有这条」
     * 处理。整份丢弃会让一次手改/一次版本回退把所有人的缓存清空。
     */
    internal fun decode(blob: String?, now: Long): Map<String, Entry> {
        if (blob.isNullOrBlank()) return emptyMap()
        val root = runCatching { JSONObject(blob) }.getOrNull() ?: return emptyMap()
        if (root.optInt("schema", 0) != SCHEMA) return emptyMap()
        val arr = root.optJSONArray("entries") ?: return emptyMap()
        val out = LinkedHashMap<String, Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty()) continue
            val status = runCatching { CheckStatus.valueOf(o.optString("st", "")) }.getOrNull()
                ?: continue
            val at = o.optLong("at", 0L)
            if (!isFresh(at, now)) continue
            val eligibility = runCatching {
                UpdateEligibility.valueOf(o.optString("el", ""))
            }.getOrDefault(UpdateEligibility.UNKNOWN)
            out[id] = Entry(
                versionCode = o.optLong("vc", 0L),
                status = status,
                uid = o.optString("uid", ""),
                eligibility = eligibility,
                reason = o.optString("rs", ""),
                at = at,
            )
        }
        return out
    }

    internal fun encode(entries: Map<String, Entry>): String {
        val arr = JSONArray()
        entries.forEach { (id, entry) ->
            arr.put(
                JSONObject()
                    .put("id", id)
                    .put("vc", entry.versionCode)
                    .put("st", entry.status.name)
                    .put("uid", entry.uid)
                    .put("el", entry.eligibility.name)
                    .put("rs", entry.reason)
                    .put("at", entry.at)
            )
        }
        return JSONObject().put("schema", SCHEMA).put("entries", arr).toString()
    }

    private fun rawBlob(): String? = runCatching {
        prefs()?.getString(KEY_BLOB, null)
    }.getOrNull()

    private fun prefs() = runCatching {
        ksuApp.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }.getOrNull()
}
