package com.sukisu.ultra.data.backup

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
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
 *
 * **时间必须按"本地时区折算后仍是 1980-01-01T00:00"的方式落**：[ZipEntry.setTime] 接的是
 * "本地时区下的 wall clock 毫秒"，写进 zip 时会按 JVM 默认时区折算成 DOS 时间——
 * 同一个条目在东八区与西八区就是两个不同的 DOS 时间，字节也就不同，去重的前提直接不成立。
 * 西半球更糟：那里的时间折回 DOS 纪元（1980-01-01）之前，条目会退化成另一种表示，
 * 连长度都可能变（实测：UTC 下 394 字节，America/Los_Angeles 下 448 字节）。
 *
 * 所以这里用 [timeFor] 反着补偿一次：给 `setTime` 一个"在**当前**默认时区下正好是
 * 1980-01-01T00:00"的毫秒值，它折算回 UTC 就恒等于 DOS 纪元，与默认时区无关。
 *
 * 不能用 [ZipEntry.setTimeLocal]：它额外写一份 0x5455 扩展时间戳，而那份扩展时间戳
 * 记的是**传入时刻**对应的 UTC 毫秒——同样随时区漂（实测东八区与西八区字节在第 46 字节处分叉）。
 */
internal object DeterministicZip {

    /** 1980-01-01T00:00:00Z：DOS 时间的起点，zip 能表示的最早时刻。 */
    const val EPOCH_MS = 315_532_800_000L

    /** 不带时区的 1980-01-01T00:00，zip 能表示的最早时刻。 */
    private val EPOCH_LOCAL: LocalDateTime = LocalDateTime.of(1980, 1, 1, 0, 0)

    /**
     * 归档里的每个条目（含目录条目）都用它建：时间固定且按时区无关的方式写入，
     * 同一份内容在任何一台机器上都能打出同样的字节。
     */
    fun newEntry(name: String): ZipEntry = ZipEntry(name).apply { time = timeFor() }

    /**
     * 在当前默认时区下正好是 1980-01-01T00:00 的毫秒值。
     *
     * 交给 [ZipEntry.setTime] 后，它按同一时区折回 UTC，恒等于 DOS 纪元——
     * 于是写进文件的 DOS 时间都与默认时区无关。
     * 必须在**每次**建条目时重新算：默认时区在进程活着的时候可能被改（设置页、系统广播）。
     */
    private fun timeFor(): Long =
        ZonedDateTime.of(EPOCH_LOCAL, ZoneId.systemDefault()).toInstant().toEpochMilli()

    /**
     * 条目应当落成的那个墙钟时刻（不带时区），供单测核对：
     * 不管默认时区是什么，把 [ZipEntry.time] 折算回本地时间都该得到它。
     */
    internal val expectedLocalDateTime: LocalDateTime get() = EPOCH_LOCAL
}
