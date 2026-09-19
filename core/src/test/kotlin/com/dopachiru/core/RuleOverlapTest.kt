package com.dopachiru.core

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.LockoutAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.RuleOverlap
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 同じ相手を狙っているルールを見つけるところ。
 *
 * 「同じアプリでも条件ごとにアクションを変えたい」はもともとできるので、
 * ここが直しているのは**できると気づけないこと**のほう。
 */
class RuleOverlapTest {

    @Before
    fun setUp() = DopaCore.registerAll()

    private fun rule(
        id: Long,
        target: Target,
        actionId: String = BlockAction.id,
        enabled: Boolean = true,
    ) = Rule(
        id = id,
        uid = "u$id",
        name = "ルール$id",
        enabled = enabled,
        target = target,
        condition = ConditionNode.AllOf(emptyList()),
        actionId = actionId,
        actionParams = Params.EMPTY,
    )

    private val youtube = Target(packages = setOf("com.google.android.youtube"))
    private val twitter = Target(packages = setOf("com.twitter.android"))

    // ---- 重なり --------------------------------------------------------

    @Test
    fun `同じアプリを指していれば重なる`() {
        assertTrue(RuleOverlap.overlaps(youtube, youtube))
    }

    @Test
    fun `別のアプリなら重ならない`() {
        assertFalse(RuleOverlap.overlaps(youtube, twitter))
    }

    @Test
    fun `一部でも共通なら重なる`() {
        val both = Target(packages = setOf("com.google.android.youtube", "com.twitter.android"))
        assertTrue(RuleOverlap.overlaps(youtube, both))
    }

    @Test
    fun `タグでも重なる`() {
        val sns = Target(tags = setOf("SNS"))
        assertTrue(RuleOverlap.overlaps(sns, Target(tags = setOf("SNS", "動画"))))
        assertFalse(RuleOverlap.overlaps(sns, Target(tags = setOf("仕事"))))
    }

    @Test
    fun `サイトでも重なる`() {
        val shorts = Target(sites = setOf("youtube.com/shorts"))
        assertTrue(RuleOverlap.overlaps(shorts, Target(sites = setOf("youtube.com/shorts"))))
    }

    @Test
    fun `全指定は何とでも重なる`() {
        assertTrue(RuleOverlap.overlaps(Target(matchAll = true), youtube))
        assertTrue(RuleOverlap.overlaps(youtube, Target(matchAll = true)))
    }

    @Test
    fun `空の対象は誰とも重ならない`() {
        // 何にも当たらないルールなので、案内に出しても混乱するだけ
        assertFalse(RuleOverlap.overlaps(Target(), youtube))
        assertFalse(RuleOverlap.overlaps(Target(), Target(matchAll = true)))
    }

    // ---- 兄弟 ----------------------------------------------------------

    @Test
    fun `自分は兄弟に入らない`() {
        val me = rule(1, youtube)
        assertTrue(RuleOverlap.siblingsOf(me, listOf(me)).isEmpty())
    }

    @Test
    fun `同じアプリの他のルールが強い順に並ぶ`() {
        val me = rule(1, youtube, WarnAction.id)
        val block = rule(2, youtube, BlockAction.id)
        val lockout = rule(3, youtube, LockoutAction.id)
        val other = rule(4, twitter, BlockAction.id)

        val siblings = RuleOverlap.siblingsOf(me, listOf(me, block, lockout, other))
        // 閉め出し(200) > 完全封印(100)。別アプリは入らない
        assertEquals(listOf(3L, 2L), siblings.map { it.id })
    }

    // ---- どれが勝つか --------------------------------------------------

    @Test
    fun `同時に成立したら強いほうが勝つ`() {
        val warn = rule(1, youtube, WarnAction.id)
        val lockout = rule(2, youtube, LockoutAction.id)
        assertEquals(2L, RuleOverlap.winnerAmong(listOf(warn, lockout))?.id)
    }

    @Test
    fun `止めてあるルールは勝たない`() {
        val warn = rule(1, youtube, WarnAction.id)
        val lockout = rule(2, youtube, LockoutAction.id, enabled = false)
        assertEquals(1L, RuleOverlap.winnerAmong(listOf(warn, lockout))?.id)
    }

    @Test
    fun `全部止まっていれば勝者なし`() {
        val warn = rule(1, youtube, WarnAction.id, enabled = false)
        assertNull(RuleOverlap.winnerAmong(listOf(warn)))
    }

    @Test
    fun `知らない措置でも落ちない`() {
        val unknown = rule(1, youtube, "そんな措置は無い")
        assertEquals(0, RuleOverlap.severityOf(unknown))
    }
}
