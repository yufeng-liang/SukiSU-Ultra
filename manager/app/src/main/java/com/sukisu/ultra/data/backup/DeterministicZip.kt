package com.sukisu.ultra.data.backup

import java.util.zip.ZipEntry

/**
 * 备份归档里 zip 条目的"固定时刻"。
 *
 * 去重（[DuplicatePolicy]）是按归档的 sha256 判的：内容没变就该跳过、不重传。`ZipOutputStream`
 * 对没有时间的条目取**当前时刻**，同一份模块内容两次打出来的字节就不一样，sha256 也就不同——
 * 去重永远命不中。真机上实测同一个模块连备三次得到三个 sha256，自动备份每次把 11 个模块
 * （十几 MB）重传一遍，列表里堆着一份份内容完全相同的备份。
 *
 * 固定成 zip 纪元（1980-01-01）而不是"模块文件自己的 mtime"：模块目录里常见的日志、标记文件
 * 随时会被写，按 mtime 算等于把"内容没变"也判成变了。恢复出来的文件时间对模块没有意义（模块
 * 按内容工作），而去重能不能命中直接决定要不要再传一遍。
 *
 * 单独放一个对象是为了能被单测盯住：打包本身要 root 才能跑（`SuFile`），而"字节稳不稳"是这套
 * 去重的前提，不能只靠真机试出来。
 */
internal object DeterministicZip {

    /** 1980-01-01T00:00:00Z：DOS 时间的起点，zip 能表示的最早时刻。 */
    const val EPOCH_MS = 315_532_800_000L

    /** 归档里的每个条目（含目录条目）都用它建：时间固定，同一份内容就打得出同样的字节。 */
    fun newEntry(name: String): ZipEntry = ZipEntry(name).apply { time = EPOCH_MS }
}
