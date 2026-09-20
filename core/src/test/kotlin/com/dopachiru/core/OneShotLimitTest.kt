package com.dopachiru.core

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.condition.types.WindowBudgetCondition
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.OneShotLimit
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.Rules
import com.dopachiru.core.model.Target
import org.junit.Before
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * その場で決める「◯時間使ったら◯分休憩」。
 *
 * 肝は **窓 = 使う + 休む** であること(休憩用の条件を別に持たない)と、
 * **明日には消える**こと。
 */
class OneShotLimitTest {

    private val zone = ZoneId.of("Asia/Tokyo")

    @Before
    fun setUp() = DopaCore.registerAll()

    private val youtube = Target(packages = setOf("com.google.android.youtube"))

    @Test
    fun `使う時間と休む時間の合計が窓になる`() {
        val rule = OneShotLimit.build(youtube, useMinutes = 120, restMinutes = 60)
        val leaf = rule.condition as ConditionNode.Leaf
        assertEquals(WindowBudgetCondition.id, leaf.typeId)
        // 窓が「使う + 休む」でないと、休憩が始まる前に窓が明けて休憩が消える
        assertEquals(180, leaf.params.int(WindowBudgetCondition.KEY_WINDOW_MINUTES, 0))
        assertEquals(120, leaf.params.int(WindowBudgetCondition.KEY_BUDGET_MINUTES, 0))
    }

    @Test
    fun `閉じるだけではなく塞ぎ続ける`() {
        // 「閉じるだけ」だと開き直せる。条件が続くあいだ塞ぐ側でないと休憩にならない
        assertEquals(BlockAction.id, OneShotLimit.build(youtube).actionId)
    }

    @Test
    fun `無茶な数字は丸める`() {
        val tiny = OneShotLimit.build(youtube, useMinutes = 0, restMinutes = 0)
        val leaf = tiny.condition as ConditionNode.Leaf
        assertEquals(OneShotLimit.MIN_USE_MINUTES, leaf.params.int(WindowBudgetCondition.KEY_BUDGET_MINUTES, 0))
        assertEquals(
            OneShotLimit.MIN_USE_MINUTES + OneShotLimit.MIN_REST_MINUTES,
            leaf.params.int(WindowBudgetCondition.KEY_WINDOW_MINUTES, 0),
        )
    }

    @Test
    fun `名前は読んで分かる形にする`() {
        assertEquals("2時間使ったら1時間休憩", OneShotLimit.label(120, 60))
        assertEquals("1時間30分使ったら20分休憩", OneShotLimit.label(90, 20))
        assertEquals("30分使ったら5分休憩", OneShotLimit.label(30, 5))
    }

    // ---- 今日の終わり --------------------------------------------------

    @Test
    fun `深夜に作った枠は朝までもつ`() {
        // 日付が変わった瞬間に消えると、深夜1時に作った枠が1時間で死ぬ
        val lateNight = LocalDateTime.of(2026, 9, 20, 1, 0)
        val ends = OneShotLimit.endOfDay(lateNight, zone)
        assertEquals(
            LocalDateTime.of(2026, 9, 20, 4, 0).atZone(zone).toEpochSecond(),
            ends,
        )
    }

    @Test
    fun `昼に作った枠は翌朝まで`() {
        val noon = LocalDateTime.of(2026, 9, 20, 12, 0)
        assertEquals(
            LocalDateTime.of(2026, 9, 21, 4, 0).atZone(zone).toEpochSecond(),
            OneShotLimit.endOfDay(noon, zone),
        )
    }

    // ---- 期限 ----------------------------------------------------------

    @Test
    fun `期限を過ぎたら消える`() {
        val rule = OneShotLimit.build(youtube, expiresAtSec = 1000L)
        assertTrue(rule.isTemporary)
        assertFalse(rule.isExpiredAt(999L))
        assertTrue(rule.isExpiredAt(1000L))
        assertTrue(rule.isExpiredAt(1001L))
    }

    @Test
    fun `期限なしは消えない`() {
        val rule = OneShotLimit.build(youtube)
        assertFalse(rule.isTemporary)
        assertFalse(rule.isExpiredAt(Long.MAX_VALUE))
    }

    @Test
    fun `掃除は期限切れだけを落とす`() {
        val forever: Rule = OneShotLimit.build(youtube).copy(name = "ずっと")
        val dead = OneShotLimit.build(youtube, expiresAtSec = 500L)
        val alive = OneShotLimit.build(youtube, expiresAtSec = 2000L)
        val all = listOf(forever, dead, alive)

        assertTrue(Rules.hasExpired(all, 1000L))
        assertEquals(listOf(forever, alive), Rules.prune(all, 1000L))
        assertFalse(Rules.hasExpired(listOf(forever, alive), 1000L))
    }
}
