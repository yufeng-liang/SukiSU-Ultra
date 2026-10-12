package com.sukisu.ultra.ui.screen.modulerepo

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 免责说明正文的重点标记解析。
 *
 * 资源里写成 `**重点**`，界面上换成加粗+红色。这里锁住的是"标记本身绝不露出来"和
 * "高亮范围正好盖住那几个字"：标记漏进正文用户会看到一串 `**`，范围算错则会把旁边
 * 的字也染红或漏染，两种都是肉眼可见的错误，却在纯编译期看不出来。
 */
class RepoDisclaimerTextTest {

    private val emphasis = SpanStyle(fontWeight = FontWeight.Bold, color = Color.Red)

    @Test
    fun `markers never appear in the rendered text`() {
        assertEquals("未审核。", annotateDisclaimer("**未审核**。", emphasis).text)
    }

    @Test
    fun `the emphasised range covers exactly the marked phrase`() {
        // 「甲**乙**丙」：标记本身不进正文，输出是「甲乙丙」，高亮必须正好落在「乙」上（下标 1..2），
        // 既不能把「甲」染红，也不能只染一半。
        val annotated = annotateDisclaimer("甲**乙**丙", emphasis)
        assertEquals("甲乙丙", annotated.text)
        assertEquals(1, annotated.spanStyles.size)
        assertEquals(1, annotated.spanStyles[0].start)
        assertEquals(2, annotated.spanStyles[0].end)
        assertEquals(emphasis, annotated.spanStyles[0].item)
    }

    @Test
    fun `several marked phrases each get their own span`() {
        val annotated = annotateDisclaimer("**甲**与**乙**", emphasis)
        assertEquals("甲与乙", annotated.text)
        assertEquals(2, annotated.spanStyles.size)
    }

    @Test
    fun `plain text is left alone`() {
        val annotated = annotateDisclaimer("没有任何标记", emphasis)
        assertEquals("没有任何标记", annotated.text)
        assertEquals(0, annotated.spanStyles.size)
    }

    @Test
    fun `an unclosed marker keeps every word and adds no span`() {
        // 漏了收尾标记时不能吞字：宁可少一处高亮，也不能让用户少读一句话。
        val annotated = annotateDisclaimer("前半**后半", emphasis)
        assertEquals("前半**后半", annotated.text)
        assertEquals(0, annotated.spanStyles.size)
    }
}
