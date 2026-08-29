package com.dopachiru.core

import com.dopachiru.core.model.SiteCatalog
import com.dopachiru.core.model.SitePattern
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * URL の当たり判定。
 *
 * ここが緩いと関係ないページまで巻き込み、厳しすぎると
 * 「止めているつもりで止まっていない」になる。後者のほうが害が大きいので、
 * 抜け(当たってほしいのに当たらない)を重点的に見る。
 */
class SitePatternTest {

    // ---- ホスト ---------------------------------------------------------

    @Test
    fun `ホストだけのパターンはそのサイト全体に当たる`() {
        assertTrue(SitePattern.matches("tiktok.com", "https://tiktok.com/"))
        assertTrue(SitePattern.matches("tiktok.com", "https://tiktok.com/foryou"))
    }

    @Test
    fun `下位ドメインにも当たる`() {
        // m.youtube.com や www. を書き忘れて素通りするのがいちばん多い抜け
        listOf(
            "https://www.youtube.com/",
            "https://m.youtube.com/",
            "https://music.youtube.com/",
        ).forEach { assertTrue(SitePattern.matches("youtube.com", it), it) }
    }

    @Test
    fun `似た名前のホストには当たらない`() {
        assertFalse(SitePattern.matches("youtube.com", "https://notyoutube.com/"))
        assertFalse(SitePattern.matches("youtube.com", "https://youtube.com.evil.example/"))
    }

    // ---- パス -----------------------------------------------------------

    @Test
    fun `パスは前方一致で当たる`() {
        val p = "youtube.com/shorts"
        assertTrue(SitePattern.matches(p, "https://www.youtube.com/shorts"))
        assertTrue(SitePattern.matches(p, "https://www.youtube.com/shorts/"))
        assertTrue(SitePattern.matches(p, "https://www.youtube.com/shorts/abc123"))
    }

    @Test
    fun `パスは区切りを跨いで一致しない`() {
        // /shorts が /shortstack に当たると、無関係なページを巻き込む
        assertFalse(SitePattern.matches("youtube.com/shorts", "https://youtube.com/shortstack"))
    }

    @Test
    fun `同じサイトの他のページは通る`() {
        val p = "youtube.com/shorts"
        assertFalse(SitePattern.matches(p, "https://www.youtube.com/watch?v=abc"))
        assertFalse(SitePattern.matches(p, "https://www.youtube.com/results?search_query=x"))
    }

    // ---- 書き方の甘さ ---------------------------------------------------

    @Test
    fun `URLをそのまま貼っても同じ意味になる`() {
        val urls = listOf(
            "youtube.com/shorts",
            "https://youtube.com/shorts",
            "https://www.youtube.com/shorts/",
        )
        urls.forEach {
            assertTrue(SitePattern.matches(it, "https://m.youtube.com/shorts/xyz"), it)
        }
    }

    @Test
    fun `クエリと断片は見ない`() {
        assertTrue(SitePattern.matches("youtube.com/shorts", "https://youtube.com/shorts/a?t=5#x"))
    }

    @Test
    fun `大文字小文字を区別しない`() {
        assertTrue(SitePattern.matches("YouTube.com/Shorts", "HTTPS://WWW.YOUTUBE.COM/shorts/a"))
    }

    @Test
    fun `ポートや認証情報が付いていても読める`() {
        assertTrue(SitePattern.matches("example.com", "http://user:pw@example.com:8080/a"))
    }

    // ---- web 以外 -------------------------------------------------------

    @Test
    fun `ブラウザの内部ページは対象外`() {
        listOf("chrome://newtab", "about:blank", "file:///C:/a.html", "edge://settings")
            .forEach { assertFalse(SitePattern.matches("example.com", it), it) }
    }

    @Test
    fun `空やURLでないものは当たらない`() {
        assertFalse(SitePattern.matches("youtube.com", ""))
        assertFalse(SitePattern.matches("", "https://youtube.com/"))
        assertFalse(SitePattern.matchesAny(setOf("youtube.com"), null))
        assertFalse(SitePattern.matchesAny(emptySet(), "https://youtube.com/"))
    }

    // ---- 整形と検査 -----------------------------------------------------

    @Test
    fun `正規化すると見せる形になる`() {
        assertEquals("youtube.com/shorts", SitePattern.normalize("https://www.YouTube.com/shorts/"))
        assertEquals("tiktok.com", SitePattern.normalize("https://tiktok.com"))
    }

    @Test
    fun `ドットの無いものは書き間違いとして弾く`() {
        assertFalse(SitePattern.isValid("youtube"))
        assertFalse(SitePattern.isValid(""))
        assertTrue(SitePattern.isValid("youtube.com"))
        assertTrue(SitePattern.isValid("youtube.com/shorts"))
    }

    // ---- 一覧 -----------------------------------------------------------

    @Test
    fun `束に入っているパターンはすべて書き方として正しい`() {
        SiteCatalog.all.forEach { group ->
            assertTrue(group.patterns.isNotEmpty(), "${group.id}: 空の束")
            group.patterns.forEach { p ->
                assertTrue(SitePattern.isValid(p), "${group.id}: 書き方が不正 $p")
                assertEquals(p, SitePattern.normalize(p), "${group.id}: 正規化前の形で入っている")
            }
        }
    }

    @Test
    fun `束のIDは重複していない`() {
        val ids = SiteCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "重複: $ids")
    }

    @Test
    fun `短い動画の束はYouTube全体を巻き込まない`() {
        // ここが崩れると「調べ物にも使えるまま」という説明が嘘になる
        val patterns = SiteCatalog.SHORT_VIDEO.patterns.toSet()
        assertTrue(SitePattern.matchesAny(patterns, "https://www.youtube.com/shorts/a"))
        assertFalse(SitePattern.matchesAny(patterns, "https://www.youtube.com/watch?v=a"))
    }
}
