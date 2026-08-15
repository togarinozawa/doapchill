package com.dopachiru.core

import com.dopachiru.core.engine.CalendarState
import com.dopachiru.core.gate.Gate
import com.dopachiru.core.gate.GatePolicy
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GateTest {

    // 2026-08-06 は木曜。以降 07=金, 08=土, 09=日, 10=月。
    private val thu = LocalDateTime.of(2026, 8, 6, 12, 0)
    private val fri = LocalDateTime.of(2026, 8, 7, 12, 0)
    private val sat = LocalDateTime.of(2026, 8, 8, 12, 0)
    private val sun = LocalDateTime.of(2026, 8, 9, 12, 0)
    private val mon = LocalDateTime.of(2026, 8, 10, 12, 0)

    private fun calendarWith(vararg titles: String): CalendarState = object : CalendarState {
        override val busy = titles.isNotEmpty()
        override fun inEventMatching(keyword: String) =
            titles.any { it.contains(keyword, ignoreCase = true) }
        override val currentTitles = titles.toList()
    }

    @Test
    fun `前提として曜日の並びが想定どおり`() {
        assertEquals(DayOfWeek.THURSDAY, thu.dayOfWeek)
        assertEquals(DayOfWeek.FRIDAY, fri.dayOfWeek)
        assertEquals(DayOfWeek.SATURDAY, sat.dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, sun.dayOfWeek)
        assertEquals(DayOfWeek.MONDAY, mon.dayOfWeek)
    }

    @Test
    fun `平日昼の窓は曜日と時刻の両方を見る`() {
        val gate = Gate.TimeWindow(9 * 60, 18 * 60, days = setOf(1, 2, 3, 4, 5))

        assertTrue(gate.contains(mon.withHour(10)))
        assertTrue(gate.contains(thu.withHour(17).withMinute(59)))
        // 時刻が外
        assertFalse(gate.contains(mon.withHour(20)))
        assertFalse(gate.contains(mon.withHour(8).withMinute(59)))
        // 曜日が外
        assertFalse(gate.contains(sat.withHour(10)))
        assertFalse(gate.contains(sun.withHour(10)))
    }

    @Test
    fun `日をまたぐ窓は開始側の曜日で判定される`() {
        // 「金の 22:00 から 2:00 まで」= 土曜の未明まで有効
        val gate = Gate.TimeWindow(22 * 60, 2 * 60, days = setOf(5))

        assertTrue(gate.contains(fri.withHour(23)))
        assertTrue(gate.contains(sat.withHour(1)))          // 前日が金なので通る
        assertFalse(gate.contains(sat.withHour(23)))        // 土曜の夜は対象外
        assertFalse(gate.contains(sun.withHour(1)))         // 前日が土なので通らない
        assertFalse(gate.contains(fri.withHour(12)))        // 時刻が範囲外
    }

    /**
     * カレンダー凍結中は、この関門は常に開く。
     * 閉じたままだとルールを二度と直せなくなるため([CalendarFreezeTest] を参照)。
     */
    @Test
    fun `カレンダーの窓は該当する予定があるあいだだけ開く`() {
        if (!DopaFeatures.CALENDAR_ENABLED) return
        val gate = Gate.CalendarWindow("#可変")

        assertTrue(gate.isOpen(calendarWith("#可変 設定を見直す")))
        assertTrue(gate.isOpen(calendarWith("週次レビュー", "夜: #可変")))
        assertFalse(gate.isOpen(calendarWith("打ち合わせ")))
        assertFalse(gate.isOpen(calendarWith()))
        assertFalse(gate.isOpen(CalendarState.NONE))
    }

    @Test
    fun `クールダウンは時間の経過だけで自動的に通過する`() {
        val gates = listOf(Gate.Cooldown(30))
        val createdAt = thu

        assertEquals(1, GatePolicy.remaining(gates, emptySet(), createdAt, thu.plusMinutes(29)).size)
        assertTrue(GatePolicy.isReady(gates, emptySet(), createdAt, thu.plusMinutes(30)))
    }

    @Test
    fun `いったん開いた窓も条件が外れれば塞がる`() {
        if (!DopaFeatures.CALENDAR_ENABLED) return
        val gates = listOf(Gate.CalendarWindow("#可変"))
        val createdAt = thu

        assertTrue(
            GatePolicy.isReady(gates, emptySet(), createdAt, thu, calendarWith("#可変")),
        )
        // 予定が終われば、通過済みの記録が無い以上また塞がる
        assertFalse(
            GatePolicy.isReady(gates, emptySet(), createdAt, thu.plusHours(2), calendarWith()),
        )
    }

    @Test
    fun `手で通す関門は記録が残っているかぎり通過したままになる`() {
        val gates = listOf(Gate.Password, Gate.MiniGame(rounds = 3))

        assertEquals(2, GatePolicy.remaining(gates, emptySet(), thu, thu).size)
        assertEquals(1, GatePolicy.remaining(gates, setOf("password"), thu, thu).size)
        assertTrue(GatePolicy.isReady(gates, setOf("password", "miniGame"), thu, thu))
    }

    @Test
    fun `Gate はJSONで往復できる`() {
        val gates = listOf(
            Gate.Cooldown(45),
            Gate.TimeWindow(22 * 60, 2 * 60, setOf(5, 6)),
            Gate.CalendarWindow("#可変"),
            Gate.Password,
            Gate.WriteReason(50),
            Gate.MiniGame("arithmetic", 7),
        )
        val serializer = kotlinx.serialization.builtins.ListSerializer(Gate.serializer())
        val restored = DopaCore.json.decodeFromString(
            serializer,
            DopaCore.json.encodeToString(serializer, gates),
        )
        assertEquals(gates, restored)
    }

    @Test
    fun `曜日の指定が無い古い設定を読んでも毎日として扱われる`() {
        // days を持たない時期に保存された JSON
        val old = """{"kind":"timeWindow","startMinuteOfDay":480,"endMinuteOfDay":1260}"""
        val gate = DopaCore.json.decodeFromString(Gate.serializer(), old) as Gate.TimeWindow

        assertEquals(setOf(1, 2, 3, 4, 5, 6, 7), gate.days)
        assertTrue(gate.contains(sat.withHour(10)))
    }
}
