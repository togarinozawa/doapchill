package com.dopachiru.core

import com.dopachiru.core.model.FocusAnchor
import com.dopachiru.core.model.FocusSchedule
import com.dopachiru.core.model.FocusSchedules
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 自分で始めなくても始まる集中。
 *
 * いちばん大事なのは **同じ日に二度始まらない** こと。ここが崩れると、
 * 明けた瞬間にまた閉まって永久に開かなくなります ── 出口の無い封鎖は事故です。
 *
 * 次に大事なのは **午後に「朝の集中」が始まらない** こと。
 */
class FocusScheduleTest {

    private val morning = FocusSchedule(
        uid = "s1",
        label = "朝",
        anchor = FocusAnchor.AFTER_WAKE,
        offsetMinutes = 20,
        byMinuteOfDay = 12 * 60,
        minutes = 20,
        steps = "顔を洗う\n机に3分だけ座る",
    )

    /** 2026-09-18 は金曜。 */
    private fun at(hour: Int, minute: Int = 0): LocalDateTime =
        LocalDateTime.of(2026, 9, 18, hour, minute)

    private fun minuteOf(hour: Int, minute: Int = 0) = hour * 60 + minute

    // ---- 起床起点 ------------------------------------------------------

    @Test
    fun `はじめて触ってから指定ぶん経つと始まる`() {
        assertFalse(
            FocusSchedules.isDue(morning, at(7, 15), minuteOf(7, 0), lastRunDate = null),
        )
        assertTrue(
            FocusSchedules.isDue(morning, at(7, 20), minuteOf(7, 0), lastRunDate = null),
        )
    }

    @Test
    fun `起床がずれても同じように効く`() {
        // 10時起きの日は 10:20 に始まる。時計で決め打つとここが空振りする
        assertFalse(
            FocusSchedules.isDue(morning, at(10, 10), minuteOf(10, 0), lastRunDate = null),
        )
        assertTrue(
            FocusSchedules.isDue(morning, at(10, 25), minuteOf(10, 0), lastRunDate = null),
        )
    }

    @Test
    fun `まだ触っていなければ始まらない`() {
        // 触っていない = 眺めていない。取り上げるものが無い
        assertFalse(FocusSchedules.isDue(morning, at(9, 0), null, lastRunDate = null))
    }

    // ---- 二度始まらない ------------------------------------------------

    @Test
    fun `同じ日に二度は始まらない`() {
        // ここが崩れると、明けた瞬間にまた閉まって永久に開かない
        assertFalse(
            FocusSchedules.isDue(morning, at(9, 0), minuteOf(7, 0), lastRunDate = LocalDate.of(2026, 9, 18)),
        )
    }

    @Test
    fun `翌日はまた始まる`() {
        assertTrue(
            FocusSchedules.isDue(morning, at(9, 0), minuteOf(7, 0), lastRunDate = LocalDate.of(2026, 9, 17)),
        )
    }

    // ---- 昼を過ぎたら朝ではない ----------------------------------------

    @Test
    fun `締切を過ぎたらその日は始めない`() {
        // 午後2時にはじめて端末を触った日に「朝の集中」が始まるのはおかしい
        assertFalse(
            FocusSchedules.isDue(morning, at(14, 30), minuteOf(14, 0), lastRunDate = null),
        )
    }

    @Test
    fun `起点が締切より後ろに来る日も始めない`() {
        // 11:50 に起きると、20分後は締切(12:00)の向こう側
        assertFalse(
            FocusSchedules.isDue(morning, at(11, 55), minuteOf(11, 50), lastRunDate = null),
        )
    }

    // ---- 時計起点 ------------------------------------------------------

    private val clock = morning.copy(
        anchor = FocusAnchor.AT_CLOCK,
        offsetMinutes = minuteOf(8, 30),
    )

    @Test
    fun `時計起点は触っていなくても始まる`() {
        assertFalse(FocusSchedules.isDue(clock, at(8, 0), null, lastRunDate = null))
        assertTrue(FocusSchedules.isDue(clock, at(8, 30), null, lastRunDate = null))
    }

    // ---- 曜日 ----------------------------------------------------------

    @Test
    fun `曜日で切れる`() {
        // 金曜(5)を外す
        val weekdaysOnly = morning.copy(days = setOf(1, 2, 3, 4))
        assertFalse(
            FocusSchedules.isDue(weekdaysOnly, at(9, 0), minuteOf(7, 0), lastRunDate = null),
        )
    }

    @Test
    fun `曜日を全部外したら効かない`() {
        assertFalse(
            FocusSchedules.isDue(morning.copy(days = emptySet()), at(9, 0), minuteOf(7, 0), null),
        )
    }

    @Test
    fun `止めてあれば始まらない`() {
        assertFalse(
            FocusSchedules.isDue(morning.copy(enabled = false), at(9, 0), minuteOf(7, 0), null),
        )
    }

    // ---- 最初の一歩 ----------------------------------------------------

    @Test
    fun `一歩は日替わりで回る`() {
        val a = morning.stepFor(LocalDate.of(2026, 9, 18))
        val b = morning.stepFor(LocalDate.of(2026, 9, 19))
        assertTrue(a.isNotBlank())
        assertTrue(b.isNotBlank())
        // 同じ日のうちは何度読んでも同じ。変わると、描き直すだけで別の指示になる
        assertEquals(a, morning.stepFor(LocalDate.of(2026, 9, 18)))
    }

    @Test
    fun `一歩を書いていなければ空`() {
        assertEquals("", morning.copy(steps = "   \n  ").stepFor(LocalDate.of(2026, 9, 18)))
    }

    // ---- 次に見に来る時刻 ----------------------------------------------

    @Test
    fun `次に始まる時刻を返す`() {
        val next = FocusSchedules.nextDueMinuteOfDay(
            listOf(morning, clock),
            at(6, 0),
            minuteOf(5, 50),
        ) { null }
        // 起床起点が 6:10、時計起点が 8:30。早いほう
        assertEquals(minuteOf(6, 10), next)
    }

    @Test
    fun `今日のぶんが残っていなければ null`() {
        val next = FocusSchedules.nextDueMinuteOfDay(
            listOf(morning),
            at(13, 0),
            minuteOf(7, 0),
        ) { LocalDate.of(2026, 9, 18) }
        assertNull(next)
    }
}
