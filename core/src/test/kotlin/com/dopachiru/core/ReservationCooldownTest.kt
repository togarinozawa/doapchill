package com.dopachiru.core

import com.dopachiru.core.condition.types.CooldownCondition
import com.dopachiru.core.condition.types.OnScreenCondition
import com.dopachiru.core.condition.types.ReservationCondition
import com.dopachiru.core.condition.types.TimeRangeCondition
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
        now: LocalDateTime = LocalDateTime.of(2026, 9, 13, 12, 0),
        clauseTimeRangeParams: Params? = null,
    ) = EvalContext(
        now = now,
        packageName = "com.x",
        usage = UsageSnapshot.EMPTY,
        currentRuleId = 3L,
        minutesSinceLastUseOf = { if (it == 3L) sinceLastUse else null },
        withinReservation = withinReservation,
        screenSignals = screens,
        clauseTimeRangeParams = clauseTimeRangeParams,
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

    // ---- 間を空ける(時間帯とセット、枠の終わりが起点) --------------------

    private val window9to17 = Params.of(TimeRangeCondition.KEY_START to 9 * 60, TimeRangeCondition.KEY_END to 17 * 60)

    @Test
    fun `前回が枠の中なら枠の終わりを起点にする`() {
        // 10:00 に使った(枠は9-17)。いま20:00。実時刻なら600分前、枠の終わり(17:00)なら180分前
        val p = Params.of(CooldownCondition.KEY_HOURS to 3, CooldownCondition.KEY_MINUTES to 20) // 200分
        val at20 = ctx(
            sinceLastUse = 600,
            now = LocalDateTime.of(2026, 9, 14, 20, 0),
            clauseTimeRangeParams = window9to17,
        )
        // 実時刻(600分前)なら明けているはずだが、枠の終わり(180分前)を起点にするのでまだ
        assertTrue(CooldownCondition.evaluate(p, at20))
    }

    @Test
    fun `枠の中でいつ触っても起点は同じ`() {
        val p = Params.of(CooldownCondition.KEY_HOURS to 3)
        val now = LocalDateTime.of(2026, 9, 14, 19, 59)
        // 9:05に触った(654分前)場合と16:55に触った(304分前)場合、どちらも枠の終わり(17:00)が起点
        val early = ctx(sinceLastUse = 654, now = now, clauseTimeRangeParams = window9to17)
        val late = ctx(sinceLastUse = 304, now = now, clauseTimeRangeParams = window9to17)
        assertEquals(
            CooldownCondition.evaluate(p, early),
            CooldownCondition.evaluate(p, late),
        )
        assertTrue(CooldownCondition.evaluate(p, early)) // 17:00から3時間経っていない(19:59)のでまだ
    }

    @Test
    fun `前回が枠の外なら実際の時刻のまま`() {
        val p = Params.of(CooldownCondition.KEY_HOURS to 3, CooldownCondition.KEY_MINUTES to 20) // 200分
        // 20:00に触った(枠9-17の外)。いま23:00。実時刻どおり180分前
        val at = ctx(
            sinceLastUse = 180,
            now = LocalDateTime.of(2026, 9, 14, 23, 0),
            clauseTimeRangeParams = window9to17,
        )
        assertTrue(CooldownCondition.evaluate(p, at)) // 180 < 200 でまだ
    }

    @Test
    fun `枠がまだ終わっていなければ実際の時刻のまま`() {
        val p = Params.of(CooldownCondition.KEY_HOURS to 0, CooldownCondition.KEY_MINUTES to 5)
        // 9:01に触って、いま9:10(枠9-17はまだ終わっていない)
        val at = ctx(
            sinceLastUse = 9,
            now = LocalDateTime.of(2026, 9, 14, 9, 10),
            clauseTimeRangeParams = window9to17,
        )
        assertFalse(CooldownCondition.evaluate(p, at)) // 9分 > 5分 で明けている
    }

    @Test
    fun `時間帯の枠の終わりを求める`() {
        // 同日内(9-17)。中にいれば同じ日の終了時刻
        assertEquals(
            LocalDateTime.of(2026, 9, 14, 17, 0),
            TimeRangeCondition.windowEndContaining(window9to17, LocalDateTime.of(2026, 9, 14, 10, 0)),
        )
        // 範囲の外なら null
        assertEquals(null, TimeRangeCondition.windowEndContaining(window9to17, LocalDateTime.of(2026, 9, 14, 20, 0)))
    }

    @Test
    fun `日をまたぐ時間帯の枠の終わりを求める`() {
        val overnight = Params.of(TimeRangeCondition.KEY_START to 22 * 60, TimeRangeCondition.KEY_END to 6 * 60)
        // 日付が変わる前(23時) → 翌日の終了時刻
        assertEquals(
            LocalDateTime.of(2026, 9, 15, 6, 0),
            TimeRangeCondition.windowEndContaining(overnight, LocalDateTime.of(2026, 9, 14, 23, 0)),
        )
        // 日付が変わった後(3時) → 同じ日の終了時刻
        assertEquals(
            LocalDateTime.of(2026, 9, 15, 6, 0),
            TimeRangeCondition.windowEndContaining(overnight, LocalDateTime.of(2026, 9, 15, 3, 0)),
        )
        // 範囲の外(正午) → null
        assertEquals(null, TimeRangeCondition.windowEndContaining(overnight, LocalDateTime.of(2026, 9, 14, 12, 0)))
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
