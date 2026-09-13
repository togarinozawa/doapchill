package com.dopachiru.core

import com.dopachiru.core.condition.types.CooldownCondition
import com.dopachiru.core.condition.types.OnScreenCondition
import com.dopachiru.core.condition.types.ReservationCondition
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.engine.UsageSnapshot
import com.dopachiru.core.model.Reservation
import com.dopachiru.core.model.Reservations
import com.dopachiru.core.model.ScreenSignals
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReservationCooldownTest {

    private fun ctx(
        sinceLastUse: Int? = null,
        withinReservation: Boolean = false,
        screens: Set<String> = emptySet(),
    ) = EvalContext(
        now = LocalDateTime.of(2026, 9, 13, 12, 0),
        packageName = "com.x",
        usage = UsageSnapshot.EMPTY,
        currentRuleId = 3L,
        minutesSinceLastUseOf = { if (it == 3L) sinceLastUse else null },
        withinReservation = withinReservation,
        screenSignals = screens,
    )

    // ---- クールダウン --------------------------------------------------

    @Test
    fun `前回から間があくまで成立する`() {
        val p = Params.of(CooldownCondition.KEY_HOURS to 3)
        assertTrue(CooldownCondition.evaluate(p, ctx(sinceLastUse = 60)))   // 1時間前 → まだ
        assertFalse(CooldownCondition.evaluate(p, ctx(sinceLastUse = 200))) // 3時間20分前 → 明けた
    }

    @Test
    fun `一度も使っていなければ開けてよい`() {
        val p = Params.of(CooldownCondition.KEY_HOURS to 3)
        assertFalse(CooldownCondition.evaluate(p, ctx(sinceLastUse = null)))
    }

    @Test
    fun `分の端数も効く`() {
        val p = Params.of(CooldownCondition.KEY_HOURS to 0, CooldownCondition.KEY_MINUTES to 30)
        assertTrue(CooldownCondition.evaluate(p, ctx(sinceLastUse = 20)))
        assertFalse(CooldownCondition.evaluate(p, ctx(sinceLastUse = 40)))
    }

    @Test
    fun `明ける時刻まで眠る`() {
        val p = Params.of(CooldownCondition.KEY_HOURS to 2)
        val at = CooldownCondition.nextChangeAt(p, ctx(sinceLastUse = 90))
        assertEquals(ctx().now.plusMinutes(30), at) // あと30分で2時間
    }

    // ---- 予約 ----------------------------------------------------------

    @Test
    fun `予約の外なら成立する`() {
        assertTrue(ReservationCondition.evaluate(Params.EMPTY, ctx(withinReservation = false)))
        assertFalse(ReservationCondition.evaluate(Params.EMPTY, ctx(withinReservation = true)))
    }

    @Test
    fun `対象が当たる予約だけがカバーする`() {
        val now = 10_000L
        val reservations = listOf(
            Reservation(
                target = Target(packages = setOf("com.x")),
                startEpochSec = now - 60,
                endEpochSec = now + 600,
            ),
        )
        assertTrue(Reservations.covers(reservations, "com.x", emptySet(), now))
        assertFalse(Reservations.covers(reservations, "com.y", emptySet(), now))
    }

    @Test
    fun `始まる前と終わった後はカバーしない`() {
        val now = 10_000L
        val upcoming = Reservation(target = Target(matchAll = true), startEpochSec = now + 100, endEpochSec = now + 700)
        val past = Reservation(target = Target(matchAll = true), startEpochSec = now - 700, endEpochSec = now - 100)
        assertFalse(Reservations.covers(listOf(upcoming), "com.x", emptySet(), now))
        assertFalse(Reservations.covers(listOf(past), "com.x", emptySet(), now))
    }

    @Test
    fun `終わった予約は掃除される`() {
        val now = 10_000L
        val all = listOf(
            Reservation(uid = "a", target = Target(matchAll = true), startEpochSec = now - 700, endEpochSec = now - 100),
            Reservation(uid = "b", target = Target(matchAll = true), startEpochSec = now - 60, endEpochSec = now + 600),
        )
        val kept = Reservations.prune(all, now)
        assertEquals(listOf("b"), kept.map { it.uid })
    }

    // ---- 画面 ----------------------------------------------------------

    @Test
    fun `狙う画面のときだけ成立する`() {
        val p = Params.of(OnScreenCondition.KEY_SIGNALS to ScreenSignals.SHORT_VIDEO)
        assertTrue(OnScreenCondition.evaluate(p, ctx(screens = setOf(ScreenSignals.SHORT_VIDEO))))
        assertFalse(OnScreenCondition.evaluate(p, ctx(screens = setOf(ScreenSignals.HOME_FEED))))
    }

    @Test
    fun `目印が取れなければ素通し`() {
        val p = Params.of(OnScreenCondition.KEY_SIGNALS to ScreenSignals.SHORT_VIDEO)
        assertFalse(OnScreenCondition.evaluate(p, ctx(screens = emptySet())))
    }
}
