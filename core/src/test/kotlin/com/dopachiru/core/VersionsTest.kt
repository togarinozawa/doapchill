package com.dopachiru.core

import com.dopachiru.core.update.ReleaseBuild
import com.dopachiru.core.update.UpdateEndpoint
import com.dopachiru.core.update.Versions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 版の比べかた。
 *
 * 肝は **桁上がりで嘘をつかないこと**と、**分からないときに黙ること**。
 */
class VersionsTest {

    @Test
    fun `新しいほうを新しいと言う`() {
        assertTrue(Versions.isNewer("0.24.0", "0.25.0"))
        assertTrue(Versions.isNewer("0.24.0", "1.0.0"))
        assertTrue(Versions.isNewer("0.24.0", "0.24.1"))
    }

    @Test
    fun `同じなら新しくない`() {
        assertFalse(Versions.isNewer("0.24.0", "0.24.0"))
    }

    @Test
    fun `古いものを勧めない`() {
        // 引っ込めたときにサーバーが一つ前を返す。それで更新を勧めては困る
        assertFalse(Versions.isNewer("0.24.0", "0.23.0"))
    }

    @Test
    fun `桁上がりで嘘をつかない`() {
        // 文字として比べると "1.2.10" < "1.2.9" になる
        assertTrue(Versions.isNewer("1.2.9", "1.2.10"))
        assertFalse(Versions.isNewer("1.2.10", "1.2.9"))
    }

    @Test
    fun `桁数が違っても比べられる`() {
        assertTrue(Versions.isNewer("1.2", "1.2.1"))
        assertFalse(Versions.isNewer("1.2.0", "1.2"))
        assertEquals(0, Versions.compare("1.2", "1.2.0"))
    }

    @Test
    fun `読めない版では黙る`() {
        // 入っているパッケージから版が読めないときに「新しい版があります」と
        // 出すと、壊れているときにかぎって更新を勧めることになる
        assertFalse(Versions.isNewer("", "0.25.0"))
        assertFalse(Versions.isNewer("0.24.0", ""))
        assertFalse(Versions.isNewer("0.24.0", "なにか"))
    }

    @Test
    fun `尻尾は切って数だけ見る`() {
        assertTrue(Versions.isNewer("1.0.0", "1.0.1-rc1"))
        assertEquals(0, Versions.compare("1.0.0-rc1", "1.0.0"))
    }

    // ---- 配っているもの ------------------------------------------------

    @Test
    fun `落とし先が https でなければ使わない`() {
        // 更新は実行ファイルを落とす口。ここを緩めると、
        // 途中で差し替えられたものを入れる道になる
        val plain = ReleaseBuild("0.25.0", "a.apk", "http://example.com/a.apk", 100)
        assertFalse(plain.isUsable)
        assertTrue(ReleaseBuild("0.25.0", "a.apk", "https://example.com/a.apk", 100).isUsable)
    }

    @Test
    fun `版が無ければ使わない`() {
        assertFalse(ReleaseBuild("", "a.apk", "https://example.com/a.apk", 100).isUsable)
    }

    @Test
    fun `大きさは読める形で出す`() {
        assertEquals("", ReleaseBuild(sizeBytes = 0).sizeLabel())
        assertEquals("500 KB", ReleaseBuild(sizeBytes = 512_000).sizeLabel())
        assertTrue(ReleaseBuild(sizeBytes = 12_900_000).sizeLabel().endsWith(" MB"))
    }

    @Test
    fun `同期を繋いでいなくても見に行ける`() {
        assertEquals(UpdateEndpoint.DEFAULT_BASE_URL, UpdateEndpoint.resolve(""))
        assertEquals(UpdateEndpoint.DEFAULT_BASE_URL, UpdateEndpoint.resolve("   "))
        assertEquals("https://example.com", UpdateEndpoint.resolve(" https://example.com "))
    }
}
