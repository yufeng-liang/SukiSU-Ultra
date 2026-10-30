package com.sukisu.ultra.ui.screen.upload

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 头像地址的协议修正。
 *
 * 酷安给的是 `http://avatar.coolapk.com/...`，而应用的 network security config 只放行
 * localhost 的明文流量 —— 原样请求会被系统拦掉，界面永远是空头像。这里钉住三件事：
 * 酷安域名抬到 https、别的 http 地址不动、已经 https 的原样返回。
 */
class AvatarUrlTest {

    @Test
    fun `coolapk avatar is upgraded to https`() {
        assertEquals(
            "https://avatar.coolapk.com/data/003/98/88/68_avatar_big.jpg?t=1600594339",
            secureAvatarUrl("http://avatar.coolapk.com/data/003/98/88/68_avatar_big.jpg?t=1600594339"),
        )
    }

    @Test
    fun `weibo cdn is upgraded too`() {
        assertEquals(
            "https://wx1.sinaimg.cn/large/x.jpg",
            secureAvatarUrl("http://wx1.sinaimg.cn/large/x.jpg"),
        )
    }

    @Test
    fun `https address is returned unchanged`() {
        val url = "https://avatar.coolapk.com/x.jpg"
        assertEquals(url, secureAvatarUrl(url))
    }

    @Test
    fun `other http hosts are left alone`() {
        // 换协议可能让它连不上；宁可原样交给下载失败走占位圆，也不凭猜测改坏地址。
        val url = "http://img.example.com/a.jpg"
        assertEquals(url, secureAvatarUrl(url))
    }

    @Test
    fun `non http input is passed through`() {
        assertEquals("javascript:alert(1)", secureAvatarUrl("javascript:alert(1)"))
    }
}
