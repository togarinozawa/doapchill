package com.dopachiru.core

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.OneShotLimit
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.Rules
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import com.dopachiru.core.preset.RulePresets
import org.junit.Before
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 「今日だけ」のルール。肝は**明日の朝には消える**こと。 */
class OneShotLimitTest {

    private val zone = ZoneId.of("Asia/Tokyo")

    @Before
    fun setUp() = DopaCore.registerAll()

    private fun rule(expiresAtSec: Long = 0L, name: String = "YouTube") = Rule(
        name = name,
        target = Target(packages = setOf("com.google.android.youtube")),
        condition = ConditionNode.AllOf(),
        actionId = BlockAction.id,
        actionParams = Params.EMPTY,
        expiresAtSec = expiresAtSec,
    )

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

    @Test
    fun `どの雛形でも今日だけにできる`() {
        // 前は「◯時間使ったら◯分休憩」しか今日だけにできなかった
        val noon = LocalDateTime.of(2026, 9, 20, 12, 0)
        RulePresets.all.forEach { preset ->
            val today = OneShotLimit.forToday(preset.build(setOf("com.example")), noon, zone)
            assertTrue(today.isTemporary, preset.id)
            assertEquals(OneShotLimit.endOfDay(noon, zone), today.expiresAtSec)
        }
    }

    // ---- 期限 ----------------------------------------------------------

    @Test
    fun `期限を過ぎたら消える`() {
        val rule = rule(expiresAtSec = 1000L)
        assertTrue(rule.isTemporary)
        assertFalse(rule.isExpiredAt(999L))
        assertTrue(rule.isExpiredAt(1000L))
        assertTrue(rule.isExpiredAt(1001L))
    }

    @Test
    fun `期限なしは消えない`() {
        val rule = rule()
        assertFalse(rule.isTemporary)
        assertFalse(rule.isExpiredAt(Long.MAX_VALUE))
    }

    @Test
    fun `掃除は期限切れだけを落とす`() {
        val forever = rule(name = "ずっと")
        val dead = rule(expiresAtSec = 500L)
        val alive = rule(expiresAtSec = 2000L)
        val all = listOf(forever, dead, alive)

        assertTrue(Rules.hasExpired(all, 1000L))
        assertEquals(listOf(forever, alive), Rules.prune(all, 1000L))
        assertFalse(Rules.hasExpired(listOf(forever, alive), 1000L))
    }
}
