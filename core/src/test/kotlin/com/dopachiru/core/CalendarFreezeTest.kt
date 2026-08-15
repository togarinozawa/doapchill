package com.dopachiru.core

import com.dopachiru.core.condition.ConditionRegistry
import com.dopachiru.core.condition.types.CalendarBusyCondition
import com.dopachiru.core.engine.CalendarState
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.engine.UsageSnapshot
import com.dopachiru.core.gate.Gate
import com.dopachiru.core.gate.GatePolicy
import com.dopachiru.core.param.Params
import com.dopachiru.core.preset.RulePresets
import com.dopachiru.core.time.ResetPolicy
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * カレンダー凍結中の振る舞い。
 *
 * 凍結でいちばん怖いのは「読まなくなった結果、縛りがきつくなる」こと。
 * 素直にカレンダーを空として扱うと、
 *  - 「予定が無いあいだ封印」は永久に成立する
 *  - 「予定中だけ変更できる」の関門は永久に閉じる = ルールを一生直せない
 * のどちらも起きる。倒す向きを間違えていないかを、ここで押さえておく。
 */
class CalendarFreezeTest {

    @Before
    fun setUp() {
        DopaCore.registerAll()
    }

    private fun ctx(calendar: CalendarState) = EvalContext(
        now = LocalDateTime.of(2026, 8, 15, 12, 0),
        packageName = "com.example.sns",
        usage = object : UsageSnapshot {
            override val currentSessionMinutes = 0
            override fun usageMinutesIn(policy: ResetPolicy) = 0
            override fun sessionCountIn(policy: ResetPolicy) = 0
        },
        calendar = calendar,
    )

    private fun busyWith(vararg titles: String): CalendarState = object : CalendarState {
        override val busy = titles.isNotEmpty()
        override fun inEventMatching(keyword: String) =
            titles.any { it.contains(keyword, ignoreCase = true) }
        override val currentTitles = titles.toList()
    }

    @Test
    fun `凍結中は予定中の条件が成立しない`() {
        val p = Params.of(CalendarBusyCondition.KEY_DURING_EVENT to true)
        val result = CalendarBusyCondition.evaluate(p, ctx(busyWith("会議")))
        assertEquals(DopaFeatures.CALENDAR_ENABLED, result)
    }

    @Test
    fun `凍結中は予定が無いあいだの条件も成立しない`() {
        // ここが肝。素直に評価すると「予定が無い」= 常に真になり、
        // 端末が閉まりっぱなしになる
        val p = Params.of(CalendarBusyCondition.KEY_DURING_EVENT to false)
        val result = CalendarBusyCondition.evaluate(p, ctx(CalendarState.NONE))
        if (!DopaFeatures.CALENDAR_ENABLED) {
            assertFalse(result, "凍結中に「予定が無いあいだ封印」が成立してはいけない")
        } else {
            assertTrue(result)
        }
    }

    @Test
    fun `凍結中はカレンダーの関門が開いたままになる`() {
        // ここも肝。閉じたままだとルールを二度と直せなくなる
        val gate = Gate.CalendarWindow("#可変")
        val remaining = GatePolicy.remaining(
            gates = listOf(gate),
            clearedKeys = emptySet(),
            createdAt = LocalDateTime.of(2026, 8, 15, 11, 0),
            now = LocalDateTime.of(2026, 8, 15, 12, 0),
            calendar = CalendarState.NONE,
        )
        if (!DopaFeatures.CALENDAR_ENABLED) {
            assertEquals(emptyList(), remaining, "凍結中に関門が閉じたままでは、ルールを直せなくなる")
        } else {
            assertEquals(listOf(gate), remaining)
        }
    }

    @Test
    fun `凍結中は条件の選択肢に出ない`() {
        val selectable = ConditionRegistry.selectable().map { it.id }
        assertEquals(
            DopaFeatures.CALENDAR_ENABLED,
            CalendarBusyCondition.id in selectable,
        )
        // レジストリからは外さない。保存済みのルールを読むために要る
        assertTrue(CalendarBusyCondition.id in ConditionRegistry.all().map { it.id })
    }

    @Test
    fun `凍結中はカレンダーの雛形が出ない`() {
        val ids = RulePresets.all.map { it.id }
        assertEquals(DopaFeatures.CALENDAR_ENABLED, "calendar_focus" in ids)
        // ほかの雛形は消えていない
        assertTrue("night" in ids)
        assertTrue("study_session" in ids)
    }

    @Test
    fun `凍結中のルールもJSONで往復できる`() {
        // 凍結しても保存済みのルールは読めなければならない。
        // 読めなくなると、解除したときに戻ってこない
        val json = """
            {"id":1,"uid":"u1","name":"予定中は封印","enabled":true,
             "target":{"packages":["com.example.sns"]},
             "condition":{"kind":"leaf","typeId":"calendar_busy",
                          "params":{"keyword":"#集中","duringEvent":true}},
             "actionId":"block","actionParams":{}}
        """.trimIndent()
        val rule = DopaCore.decodeRule(json)
        assertEquals("予定中は封印", rule.name)
        assertEquals(rule, DopaCore.decodeRule(DopaCore.encodeRule(rule)))
    }
}
