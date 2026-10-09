package com.sukisu.ultra.data.backup

object WebDavPaths {

    /**
     * 用恰好一个分隔符拼 [base] 与 [sub]。
     *
     * [base] 为空时返回规范化后的 [sub]；[sub] 为空时返回去掉尾部分隔符的 [base]，
     * 这样 `url("")` 正好是后端根集合，MKCOL 时不会因为多一个 `/` 而被服务端当成另一个路径。
     */
    fun joinBase(base: String, sub: String): String {
        val normalizedBase = base.trim()
        val normalizedSub = sub.trim().trimStart('/')
        if (normalizedBase.isEmpty()) return normalizedSub
        if (normalizedSub.isEmpty()) return normalizedBase.trimEnd('/')
        return normalizedBase.trimEnd('/') + "/" + normalizedSub
    }

    /** 返回 [path] 的每一级祖先目录，从最外层到 [path] 自身。 */
    fun parentDirs(path: String): List<String> {
        val parts = path.trim().trim('/').split('/').filter { it.isNotEmpty() }
        val directories = ArrayList<String>(parts.size)
        val current = StringBuilder()
        for (part in parts) {
            if (current.isNotEmpty()) current.append('/')
            current.append(part)
            directories += current.toString()
        }
        return directories
    }
}
