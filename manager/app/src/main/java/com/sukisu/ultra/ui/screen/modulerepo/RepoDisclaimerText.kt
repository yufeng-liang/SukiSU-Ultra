package com.sukisu.ultra.ui.screen.modulerepo

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * 正文里标重点的成对标记。资源里写成 `**这样**`，渲染时换成强调样式（加粗 + 醒目色）。
 *
 * 为什么用标记而不是把每段拆成好几条字符串：一条正文是一整句连贯的话，拆开就得在资源里
 * 手工拼装，翻译的人看不到整句、标点也容易接错；标记只加在词上，其余一个字不动。
 */
internal const val DISCLAIMER_EMPHASIS = "**"

/**
 * 把 [raw] 里被 `**` 包住的片段换成 [emphasis]，其余原样。
 *
 * 标记不成对时（漏了收尾的 `**`）不吞字：从那个标记起整段按普通文字输出——宁可少一处高亮，
 * 也不能把用户该看到的字吃掉。
 */
internal fun annotateDisclaimer(raw: String, emphasis: SpanStyle): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    while (true) {
        val open = raw.indexOf(DISCLAIMER_EMPHASIS, cursor)
        if (open < 0) {
            append(raw.substring(cursor))
            return@buildAnnotatedString
        }
        val close = raw.indexOf(DISCLAIMER_EMPHASIS, open + DISCLAIMER_EMPHASIS.length)
        if (close < 0) {
            append(raw.substring(cursor))
            return@buildAnnotatedString
        }
        append(raw.substring(cursor, open))
        withStyle(emphasis) { append(raw.substring(open + DISCLAIMER_EMPHASIS.length, close)) }
        cursor = close + DISCLAIMER_EMPHASIS.length
    }
}
