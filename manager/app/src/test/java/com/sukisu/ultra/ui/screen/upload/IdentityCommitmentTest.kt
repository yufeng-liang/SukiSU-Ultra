package com.sukisu.ultra.ui.screen.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 身份承诺串的三端钉子。
 *
 * 承诺串 = `sha256("sukisu-identity-v2:" + secret)` 的前 24 位，由三端各自实现：
 * App（本仓库）、worker（contrib/worker/src/proof.ts）、网页端（modules-web）。
 * 三份实现必须逐字节一致 —— 否则用户在 App 里抄进酷安签名的那一串，
 * 到网页端或服务端算出来就是另一个值，闸会报 `not_owner`，读起来跟「你不是本人」
 * 一模一样，而真正的原因是两端算法漂了。
 *
 * 所以这里钉死两条用 Python 独立算出来的黄金值，与 worker / 网页端测试里的那两条
 * 是同一对向量。改算法必须先改这三处，改不动就说明不该改。
 */
class IdentityCommitmentTest {

    @Test
    fun `承诺串与 worker 和网页端逐字节一致`() {
        // python -c "import hashlib;print(hashlib.sha256(('sukisu-identity-v2:'+'a'*32).encode()).hexdigest()[:24])"
        assertEquals(
            "8ebe414d17c942d044eebe10",
            ModuleUploadPrefs.identityCommitment("a".repeat(32)),
        )
        // python -c "import hashlib;print(hashlib.sha256(('sukisu-identity-v2:'+'0123456789abcdef0123456789abcdef').encode()).hexdigest()[:24])"
        assertEquals(
            "1c081f9de97c937b5cb5dc80",
            ModuleUploadPrefs.identityCommitment("0123456789abcdef0123456789abcdef"),
        )
    }

    @Test
    fun `承诺串恒为 24 位小写十六进制`() {
        val commit = ModuleUploadPrefs.identityCommitment("f".repeat(32))
        assertEquals(ModuleUploadPrefs.IDENTITY_COMMIT_LEN, commit.length)
        assertTrue("承诺串只该有小写十六进制，实际是 $commit", commit.matches(Regex("^[0-9a-f]{24}$")))
    }

    @Test
    fun `换一位验证串就换一个承诺串`() {
        // 承诺串必须对 secret 敏感：否则「换一串」按钮换了个寂寞，
        // 用户以为作废了旧签名，实际上服务端仍认旧的。
        assertNotEquals(
            ModuleUploadPrefs.identityCommitment("0".repeat(32)),
            ModuleUploadPrefs.identityCommitment("1".repeat(32)),
        )
    }
}
