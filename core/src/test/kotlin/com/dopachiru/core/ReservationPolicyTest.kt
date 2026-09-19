package com.dopachiru.core

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.model.BookingCheck
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Reservation
import com.dopachiru.core.model.ReservationPolicy
import com.dopachiru.core.model.ReservationRules
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import org.junit.Before
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 予約できる枠の型と、その枠を取ってよいかの判定。
 *
 * 肝は **上限の長さと間隔がセットで初めて上限になる** こと。長さだけ決めても、
 * 最大の枠を数珠つなぎに並べれば一日中使える。
 */
class ReservationPolicyTest {

    private val zone = ZoneId.of("Asia/Tokyo")

    @Before
    fun setUp() = DopaCore.registerAll()

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 9, day, hour, minute).atZone(zone).toEpochSecond()

    private val now = at(19, 12, 0)

    private val youtube = Target(packages = setOf("com.google.android.youtube"))

    private val policy = ReservationPolicy(
        id = "p1",
        label = "YouTube を見る",
        target = youtube,
        minLeadMinutes = 60,
        maxDurationMinutes = 60,
        minGapMinutes = 180,
        maxPerDay = 2,
    )

    private fun booked(startHour: Int, endHour: Int, day: Int = 19) = Reservation(
        uid = "r-" + day + "-" + startHour,
        target = youtube,
        startEpochSec = at(day, startHour),
        endEpochSec = at(day, endHour),
        policyId = "p1",
    )

    private fun check(
        startSec: Long,
        endSec: Long,
        existing: List<Reservation> = emptyList(),
        p: ReservationPolicy = policy,
    ) = ReservationRules.check(p, existing, startSec, endSec, now, zone)

    // ---- 直前予約 ------------------------------------------------------

    @Test
    fun `十分先なら取れる`() {
        assertEquals(BookingCheck.Ok, check(at(19, 14), at(19, 15)))
    }

    @Test
    fun `直前すぎると断る`() {
        // いまが12時、リードは60分。12:30 開始は取れない
        val result = check(at(19, 12, 30), at(19, 13))
        assertTrue(result is BookingCheck.Refused)
        assertTrue((result as BookingCheck.Refused).reason.contains("直前"))
    }

    @Test
    fun `ちょうど境目は取れる`() {
        assertEquals(BookingCheck.Ok, check(at(19, 13), at(19, 14)))
    }

    // ---- 長さ ----------------------------------------------------------

    @Test
    fun `上限より長いと断る`() {
        val result = check(at(19, 14), at(19, 16))
        assertTrue(result is BookingCheck.Refused)
        // 何分までなのかを言わないと、どこまで縮めればいいのか分からない
        assertTrue((result as BookingCheck.Refused).reason.contains("60分まで"))
    }

    @Test
    fun `短すぎても断る`() {
        val result = check(at(19, 14), at(19, 14, 2))
        assertTrue(result is BookingCheck.Refused)
    }

    // ---- 間隔 ----------------------------------------------------------

    @Test
    fun `間隔が足りないと断る`() {
        // 既存 14:00-15:00。間隔180分なので 18:00 より前は取れない
        val result = check(at(19, 16), at(19, 17), listOf(booked(14, 15)))
        assertTrue(result is BookingCheck.Refused)
        assertTrue((result as BookingCheck.Refused).reason.contains("あけて"))
    }

    @Test
    fun `間隔が足りていれば取れる`() {
        assertEquals(BookingCheck.Ok, check(at(19, 18), at(19, 19), listOf(booked(14, 15))))
    }

    @Test
    fun `前にある枠との間隔も見る`() {
        // 新しい枠 14:00-15:00 の「後ろ」に既存 16:00-17:00 がある。間隔は60分しかない
        val result = check(at(19, 14), at(19, 15), listOf(booked(16, 17)))
        assertTrue(result is BookingCheck.Refused)
    }

    @Test
    fun `重なっていたら断る`() {
        val result = check(at(19, 14, 30), at(19, 15, 30), listOf(booked(14, 15)))
        assertTrue(result is BookingCheck.Refused)
        assertTrue((result as BookingCheck.Refused).reason.contains("重なって"))
    }

    @Test
    fun `数珠つなぎにはできない`() {
        // 上限の長さだけ決めて間隔を 0 にすると、並べ放題になる。
        // 間隔があるからこそ上限になる、を確かめる
        val noGap = policy.copy(minGapMinutes = 0, maxPerDay = 0)
        assertEquals(
            BookingCheck.Ok,
            ReservationRules.check(noGap, listOf(booked(14, 15)), at(19, 15), at(19, 16), now, zone),
        )
        val withGap = policy.copy(maxPerDay = 0)
        assertTrue(
            ReservationRules.check(withGap, listOf(booked(14, 15)), at(19, 15), at(19, 16), now, zone)
                is BookingCheck.Refused,
        )
    }

    // ---- 1日の回数 -----------------------------------------------------

    @Test
    fun `1日の上限を超えたら断る`() {
        val existing = listOf(booked(14, 15), booked(18, 19))
        // 間隔は足りている(22時)が、その日はもう2回取っている
        val result = check(at(19, 22), at(19, 23), existing)
        assertTrue(result is BookingCheck.Refused)
        assertTrue((result as BookingCheck.Refused).reason.contains("2回"))
    }

    @Test
    fun `翌日ぶんは別に数える`() {
        val existing = listOf(booked(14, 15), booked(18, 19))
        assertEquals(BookingCheck.Ok, check(at(20, 14), at(20, 15), existing))
    }

    @Test
    fun `回数を切っていなければ何度でも`() {
        val unlimited = policy.copy(maxPerDay = 0)
        val existing = listOf(booked(14, 15), booked(18, 19))
        assertEquals(
            BookingCheck.Ok,
            ReservationRules.check(unlimited, existing, at(19, 22), at(19, 23), now, zone),
        )
    }

    // ---- 止めてある枠 --------------------------------------------------

    @Test
    fun `止めてある型からは取れない`() {
        val off = policy.copy(enabled = false)
        assertTrue(check(at(19, 14), at(19, 15), p = off) is BookingCheck.Refused)
    }

    // ---- 数える相手 ----------------------------------------------------

    @Test
    fun `終わった枠は数えない`() {
        // 10:00-11:00 はもう終わっている。間隔の邪魔をしてはいけない
        val past = booked(10, 11)
        assertTrue(ReservationRules.bookedUnder(policy, listOf(past), now).isEmpty())
    }

    @Test
    fun `型が違っても対象が重なれば数える`() {
        // 型を消して作り直すと policyId が変わる。それで間隔が漏れては困る
        val other = booked(14, 15).copy(policyId = "むかしの型")
        assertEquals(1, ReservationRules.bookedUnder(policy, listOf(other), now).size)
    }

    // ---- 塞いでいなければ意味がない ------------------------------------

    @Test
    fun `塞ぐルールが無ければ知らせる`() {
        // 予約は「本来ダメな時間に穴を開ける」もの。塞いでいない相手を
        // 予約しても、いつでも開くので何も起きない
        assertFalse(ReservationRules.isGuarded(policy, emptyList()))
    }

    @Test
    fun `塞ぐルールがあれば通る`() {
        val rule = Rule(
            id = 1,
            name = "YouTube は予約の外では開かない",
            target = youtube,
            condition = ConditionNode.AllOf(emptyList()),
            actionId = BlockAction.id,
            actionParams = Params.EMPTY,
        )
        assertTrue(ReservationRules.isGuarded(policy, listOf(rule)))
    }

    @Test
    fun `止めてあるルールは数えない`() {
        val rule = Rule(
            id = 1,
            name = "止めてある",
            enabled = false,
            target = youtube,
            condition = ConditionNode.AllOf(emptyList()),
            actionId = BlockAction.id,
            actionParams = Params.EMPTY,
        )
        assertFalse(ReservationRules.isGuarded(policy, listOf(rule)))
    }
}
