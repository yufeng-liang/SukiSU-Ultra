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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 把酷安社区索引里的帖子、用户链接交给酷安 App 打开，没装 App 时回落浏览器。
 *
 * 索引只存了「哪篇帖子、哪个 UID」，所以这里自己拼两种地址：
 * 装了酷安就直接进 App（`coolmarket://` scheme + 包名限定，避免被别的 App 接走），
 * 没装或打不开就退回 `https://www.coolapk.com/...`。两条路都不通时返回 false，由界面提示。
 */
object CoolapkLinks {

    private const val COOLAPK_PACKAGE = "com.coolapk.market"

    private val FEED_ID = Regex("""/feed/(\d+)""")
    private val QUERY_ID = Regex("""[?&]id=(\d+)""")
    private val USER_ID = Regex("""/u/([A-Za-z0-9_-]+)""")

    /**
     * 打开一篇酷安帖子。[sourceUrl] 可以是帖子地址、分享链接，或带 `?id=` 的地址。
     *
     * @return 是否真的打开了（false 表示既没装酷安，浏览器也没接住）。
     */
    fun openPost(context: Context, sourceUrl: String): Boolean {
        val id = feedIdFrom(sourceUrl) ?: return false
        return start(context, coolmarketUri("feed/$id"))
            || start(context, webUri("https://www.coolapk.com/feed/$id"))
    }

    /**
     * 打开一个酷安用户主页。[coolapkUid] 既可以是纯 UID，也可以是带 `/u/` 的地址。
     */
    fun openUser(context: Context, coolapkUid: String): Boolean {
        val uid = userIdFrom(coolapkUid) ?: return false
        return start(context, coolmarketUri("u/$uid"))
            || start(context, webUri("https://www.coolapk.com/u/$uid"))
    }

    /** 从帖子地址里提 feed id；提不出返回 null（界面据此直接提示失败）。 */
    private fun feedIdFrom(url: String): String? {
        FEED_ID.find(url)?.let { return it.groupValues[1] }
        QUERY_ID.find(url)?.let { return it.groupValues[1] }
        return null
    }

    /** UID 可能是纯数字，也可能是 `/u/xxx` 这样的地址。 */
    private fun userIdFrom(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        USER_ID.find(trimmed)?.let { return it.groupValues[1] }
        return trimmed.takeIf { it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
    }

    private fun coolmarketUri(path: String) = Uri.parse("coolmarket://$path")

    private fun webUri(url: String) = Uri.parse(url)

    /**
     * 起一次 ACTION_VIEW。
     *
     * 酷安 App 的路由用 `setPackage` 限定，没装时 [Context.startActivity] 直接抛
     * ActivityNotFoundException；浏览器那条不做包名限定，让系统自己挑。非 Activity 的
     * Context 必须带 NEW_TASK，否则会抛 AndroidRuntimeException，这里一并当作「没打开」。
     */
    private fun start(context: Context, uri: Uri): Boolean {
        val isCoolapk = uri.scheme == "coolmarket"
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            if (isCoolapk) setPackage(COOLAPK_PACKAGE)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
