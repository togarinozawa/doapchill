package com.dopachiru.core

import com.dopachiru.core.model.FocusScope
import com.dopachiru.core.model.FocusTemplate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** タイマーロックの型。範囲(グループだけ / グループ以外 / 全部)の組み立て。 */
class FocusTemplateTest {

    private val snsTag = "sns"
    private val snsTags = setOf(snsTag)
    private val noTags = emptySet<String>()

    @Test
    fun `グループだけ止める`() {
        val t = FocusTemplate(id = "1", scope = FocusScope.GROUP, tag = snsTag).target()
        // タグの付いたアプリは閉まる
        assertTrue(t.matches("com.example.twitter", snsTags))
        // タグの付いていないアプリは開いたまま
        assertFalse(t.matches("com.example.work", noTags))
    }

    @Test
    fun `グループ以外を止める`() {
        val t = FocusTemplate(id = "1", scope = FocusScope.EXCEPT_GROUP, tag = snsTag).target()
        // 仕事アプリ(タグ無し)は閉まる
        assertTrue(t.matches("com.example.work", noTags))
        // そのグループのアプリは逃げる = 開いたまま
        assertFalse(t.matches("com.example.twitter", snsTags))
    }

    @Test
    fun `全部止める`() {
        val t = FocusTemplate(id = "1", scope = FocusScope.EVERYTHING).target()
        assertTrue(t.matches("com.example.anything", noTags))
        assertTrue(t.matches("com.example.twitter", snsTags))
    }

    @Test
    fun `全部止めるでも逃がすものは開く`() {
        val t = FocusTemplate(
            id = "1",
            scope = FocusScope.EVERYTHING,
            allowPackages = setOf("com.example.music"),
        ).target()
        assertFalse(t.matches("com.example.music", noTags))
        assertTrue(t.matches("com.example.sns", noTags))
    }

    @Test
    fun `タグを見る型はタグが無いと使えない`() {
        assertFalse(FocusTemplate(id = "1", scope = FocusScope.GROUP, tag = "").isUsable)
        assertFalse(FocusTemplate(id = "1", scope = FocusScope.EXCEPT_GROUP, tag = "").isUsable)
        assertTrue(FocusTemplate(id = "1", scope = FocusScope.GROUP, tag = snsTag).isUsable)
        // 全部止めるはタグに依らず使える
        assertTrue(FocusTemplate(id = "1", scope = FocusScope.EVERYTHING).isUsable)
    }

    @Test
    fun `決め打ちか長さを選ぶか`() {
        assertTrue(FocusTemplate(id = "1", minutes = 30).isOneTap)
        assertFalse(FocusTemplate(id = "1", minutes = 0).isOneTap)
    }

    @Test
    fun `名前が空なら範囲から見繕う`() {
        assertEquals("全部止める", FocusTemplate(id = "1", scope = FocusScope.EVERYTHING).displayLabel())
        assertEquals(
            "snsだけ止める",
            FocusTemplate(id = "1", scope = FocusScope.GROUP, tag = "sns").displayLabel(),
        )
        assertEquals("好きな名前", FocusTemplate(id = "1", label = "好きな名前").displayLabel())
    }
}
