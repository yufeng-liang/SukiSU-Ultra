package com.sukisu.ultra.ui.util

/**
 * 把字节数格式化成「12.3 MB」这样的可读文本。
 *
 * 之前模块仓库页的两套界面各内联了一份同样的 when 分支，第三处再抄一遍就该出
 * 「同一个数字在两屏显示成不同精度」的问题了，所以提出来一份。
 *
 * 精度跟着量级走：GB/MB 留一位小数（够看出差别），KB 取整（差 1 KB 没人关心），
 * 不足 1 KB 直接给字节数。
 */
fun formatFileSize(bytes: Long): String {
    if (bytes < 0L) return "$bytes B"
    return when {
        bytes >= 1024L * 1024L * 1024L -> String.format("%.1f GB", bytes / (1024f * 1024f * 1024f))
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024f * 1024f))
        bytes >= 1024L -> String.format("%.0f KB", bytes / 1024f)
        else -> "$bytes B"
    }
}
