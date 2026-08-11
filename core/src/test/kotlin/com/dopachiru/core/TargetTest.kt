package com.dopachiru.core

import com.dopachiru.core.model.Target
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 対象アプリの当たり判定。
 *
 * 許可リスト型(全アプリ - 例外)を入れたので、除外が正指定より確実に強いことと、
 * 前の版で保存したルールがそのまま読めることを固定しておく。
 */
class TargetTest {

    private val noTags = emptySet<String>()

    @Test
    fun `正指定はパッケージかタグに当たれば成立`() {
        val target = Target(packages = setOf("com.a"), tags = setOf("sns"))

        assertTrue(target.matches("com.a", noTags))
        assertTrue(target.matches("com.b", setOf("sns")))
        assertFalse(target.matches("com.b", noTags))
        assertFalse(target.matches("com.b", setOf("game")))
    }

    @Test
    fun `全指定は例外以外すべてに当たる`() {
        val target = Target(matchAll = true, exceptPackages = setOf("com.dict"))

        assertTrue(target.matches("com.anything", noTags))
        assertTrue(target.matches("com.other", setOf("sns")))
        assertFalse(target.matches("com.dict", noTags))
    }

    @Test
    fun `タグごと除外できる`() {
        val target = Target(matchAll = true, exceptTags = setOf("勉強"))

        assertTrue(target.matches("com.sns", noTags))
        assertFalse(target.matches("com.dict", setOf("勉強")))
        assertFalse(target.matches("com.calc", setOf("勉強", "その他")))
    }

    @Test
    fun `除外は正指定より強い`() {
        // タグで広く取って、そのうち数個だけ抜く書き方ができる
        val target = Target(tags = setOf("sns"), exceptPackages = setOf("com.work"))

        assertTrue(target.matches("com.x", setOf("sns")))
        assertFalse(target.matches("com.work", setOf("sns")))
    }

    @Test
    fun `全指定なら対象アプリを1つも選ばなくても空ではない`() {
        assertTrue(Target().isEmpty)
        assertFalse(Target(matchAll = true).isEmpty)
        assertFalse(Target(packages = setOf("com.a")).isEmpty)
    }

    @Test
    fun `前の版で保存したルールがそのまま読める`() {
        // ver.0.3 までの Target には matchAll も except も無い
        val old = """{"packages":["com.a"],"tags":["sns"]}"""
        val target = DopaCore.json.decodeFromString(Target.serializer(), old)

        assertEquals(setOf("com.a"), target.packages)
        assertEquals(setOf("sns"), target.tags)
        assertFalse(target.matchAll)
        assertTrue(target.exceptPackages.isEmpty())
        assertTrue(target.matches("com.a", noTags))
    }
}
