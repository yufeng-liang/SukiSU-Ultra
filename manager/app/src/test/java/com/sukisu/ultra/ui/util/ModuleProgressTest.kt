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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收录进度里**不依赖网络与 Android** 的那几段判据：stage 映射、坏响应的兜底、
 * 「去商店看看」的准入、步骤条节点状态。
 *
 * 这些是 UI 之外唯一会真的出错的地方——服务端多一个 stage 值、响应半截、
 * 或者某个字段缺失，界面都不该崩，也不该把「没查到」画成「没过审」。
 */
class ModuleProgressTest {

    @Test
    fun `an unknown stage is shown as merely submitted`() {
        // 服务端将来加了第六个 stage，旧客户端不能把它当成「未通过」，
        // 更不能抛：退回到最保守的「已提交」，等用户刷新时再看。
        assertEquals(ProgressStage.SUBMITTED, progressStageOf("queued"))
        assertEquals(ProgressStage.SUBMITTED, progressStageOf(""))
        assertEquals(ProgressStage.SUBMITTED, progressStageOf(" PENDING "))
        assertEquals(ProgressStage.REVIEWING, progressStageOf("pr_open"))
        assertEquals(ProgressStage.PUBLISHED, progressStageOf("PUBLISHED"))
    }

    @Test
    fun `a broken body becomes a failure instead of an exception`() {
        // 连不上、被网关截断、返回一段 HTML：都只能得到「查不到」，
        // 绝不能把异常抛到组合层（那里没有 catch）。
        val truncated = parseProgressBody("{\"ok\":true,\"items\":[{\"id\":\"device_faker\"")
        assertTrue(truncated is ProgressOutcome.Failed)

        val empty = parseProgressBody(null)
        assertTrue(empty is ProgressOutcome.Failed)

        // 401 的响应体也是合法 JSON，但它没有 ok=true：同样按「查不到」处理。
        val sessionMissing =
            parseProgressBody("{\"ok\":false,\"code\":\"session_missing\",\"error\":\"no session\"}")
        assertTrue(sessionMissing is ProgressOutcome.Failed)
    }

    @Test
    fun `an unverified item is submitted without a pr link`() {
        // 排在「最近 12 枚」之外的条目没有去查过：按 pending 画，但 PR 链接必须藏掉，
        // 否则用户点进去看到的是一份还没轮到的状态，像是进度倒退了。
        val outcome = parseProgressBody(
            "{\"ok\":true,\"items\":[{\"id\":\"device_faker\",\"versionCode\":10300," +
                "\"stage\":\"merged\",\"prUrl\":\"https://github.com/o/r/pull/9\",\"unverified\":true}]}",
        )
        val item = (outcome as ProgressOutcome.Ok).items.single()
        assertEquals(ProgressStage.SUBMITTED, item.stage)
        assertNull(item.prUrl)
        assertTrue(item.unverified)
    }

    @Test
    fun `only a published version may offer the store link`() {
        val published = progressItem(stage = ProgressStage.PUBLISHED)
        val reviewing = progressItem(stage = ProgressStage.REVIEWING)
        val rejected = progressItem(stage = ProgressStage.REJECTED)

        assertTrue(published.allowsStoreLink())
        assertFalse(reviewing.allowsStoreLink())
        assertFalse(rejected.allowsStoreLink())
        // 查不到（null）时同样不给：商店里搜不到「还没收录」的这一版。
        assertFalse((null as ProgressItem?).allowsStoreLink())
    }

    @Test
    fun `a rejected version stops at review and marks that node as failed`() {
        val rejected = progressItem(stage = ProgressStage.REJECTED)

        // 已提交是走过的，待审核是失败的那一格，后两格还没到。
        assertEquals(ProgressNodeState.DONE, rejected.nodeStateOf(0))
        assertEquals(ProgressNodeState.FAILED, rejected.nodeStateOf(1))
        assertEquals(ProgressNodeState.UPCOMING, rejected.nodeStateOf(2))
        assertEquals(ProgressNodeState.UPCOMING, rejected.nodeStateOf(3))

        // 没查到的时候四格全灰，而不是把第一格点亮成「进行中」。
        val unknown: ProgressItem? = null
        assertEquals(ProgressNodeState.UPCOMING, unknown.nodeStateOf(0))
        assertEquals(ProgressStage.SUBMITTED, progressStageOf("pending"))
    }

    private fun progressItem(stage: ProgressStage) = ProgressItem(
        id = "device_faker",
        versionCode = 10300,
        stage = stage,
        prUrl = "https://github.com/o/r/pull/9",
        reason = null,
        updatedAt = 1_793_089_943_775L,
    )
}
