package com.dopachiru.core

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.condition.types.CalendarBusyCondition
import com.dopachiru.core.condition.types.ContinuousUsageCondition
import com.dopachiru.core.condition.types.TimeRangeCondition
import com.dopachiru.core.engine.Decision
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.engine.RuleEngine
import com.dopachiru.core.engine.UsageSnapshot
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import com.dopachiru.core.time.ResetPolicy
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「次に判定が変わる時刻」の計算。
 *
 * ここが実際より後ろの時刻を返すと、その差ぶんだけブロックが遅れる。
 * 早すぎるぶんには無駄に起きるだけで害がないので、常に安全側かどうかを見る。
 */
class NextChangeAtTest {

    private val engine = RuleEngine()
    private val noTags: (String) -> Set<String> = { emptySet() }
    private val pkg = "com.example.sns"

    @Before
    fun setUp() {
        DopaCore.registerAll()
    }

    private fun ctx(now: LocalDateTime, sessionMinutes: Int = 0) = EvalContext(
        now = now,
        packageName = pkg,
        usage = object : UsageSnapshot {
            override val currentSessionMinutes = sessionMinutes
            override fun usageMinutesIn(policy: ResetPolicy) = 0
            override fun sessionCountIn(policy: ResetPolicy) = 0
        },
    )

    private fun rule(condition: ConditionNode) = Rule(
        name = "テスト",
        target = Target(packages = setOf(pkg)),
        condition = condition,
        actionId = BlockAction.id,
        actionParams = Params.EMPTY,
    )

    private fun timeRange(startMin: Int, endMin: Int) = ConditionNode.Leaf(
        TimeRangeCondition.id,
        Params.of(TimeRangeCondition.KEY_START to startMin, TimeRangeCondition.KEY_END to endMin),
    )

    @Test
    fun `時間帯は次の境界を返す`() {
        val leaf = timeRange(22 * 60, 6 * 60)
        val day = LocalDateTime.of(2026, 8, 6, 0, 0)

        // 範囲の手前 → 開始時刻
        assertEquals(
            day.withHour(22),
            engine.nextChangeAt(leaf, ctx(day.withHour(20))),
        )
        // 範囲の中(日跨ぎの前半) → 翌日の終了時刻
        assertEquals(
            day.plusDays(1).withHour(6),
            engine.nextChangeAt(leaf, ctx(day.withHour(23))),
        )
        // 範囲の中(日跨ぎの後半) → その日の終了時刻
        assertEquals(
            day.withHour(6),
            engine.nextChangeAt(leaf, ctx(day.withHour(3))),
        )
    }

    @Test
    fun `連続使用時間は閾値に届く時刻を返す`() {
        val leaf = ConditionNode.Leaf(
            ContinuousUsageCondition.id,
            Params.of(ContinuousUsageCondition.KEY_MINUTES to 15),
        )
        val now = LocalDateTime.of(2026, 8, 6, 12, 0)

        // 開いた直後 → 15分後まで起きなくてよい
        assertEquals(now.plusMinutes(15), engine.nextChangeAt(leaf, ctx(now, sessionMinutes = 0)))
        // 10分経過 → 残り5分
        assertEquals(now.plusMinutes(5), engine.nextChangeAt(leaf, ctx(now, sessionMinutes = 10)))
        // 超過後は、離れるまで時間では変わらない
        val after = engine.nextChangeAt(leaf, ctx(now, sessionMinutes = 45))
        assertTrue(after!!.isAfter(now.plusHours(12)))
    }

    @Test
    fun `予定した時刻に実際にルールが成立する`() {
        val rules = listOf(rule(ConditionNode.AllOf(listOf(timeRange(22 * 60, 6 * 60)))))
        val before = LocalDateTime.of(2026, 8, 6, 20, 0)

        // 20:00 の時点では成立していない
        assertEquals(Decision.Allow, engine.decide(rules, ctx(before), noTags))

        // 起きろと言われた時刻ちょうどで成立する = 遅れない
        val wakeAt = engine.nextChangeAt(rules, ctx(before), noTags)!!
        assertIs<Decision.Act>(engine.decide(rules, ctx(wakeAt), noTags))

        // その1分前ではまだ成立していない = 早すぎもしない
        assertEquals(Decision.Allow, engine.decide(rules, ctx(wakeAt.minusMinutes(1)), noTags))
    }

    @Test
    fun `複数のルールがあるときは一番早い時刻を採る`() {
        val now = LocalDateTime.of(2026, 8, 6, 12, 0)
        val rules = listOf(
            rule(ConditionNode.AllOf(listOf(timeRange(22 * 60, 23 * 60)))),
            rule(ConditionNode.AllOf(listOf(timeRange(15 * 60, 16 * 60)))),
        )
        assertEquals(now.withHour(15), engine.nextChangeAt(rules, ctx(now), noTags))
    }

    @Test
    fun `AND の中では一番早く変わる条件に合わせる`() {
        val now = LocalDateTime.of(2026, 8, 6, 12, 0)
        val node = ConditionNode.AllOf(
            listOf(
                timeRange(22 * 60, 23 * 60),
                ConditionNode.Leaf(
                    ContinuousUsageCondition.id,
                    Params.of(ContinuousUsageCondition.KEY_MINUTES to 5),
                ),
            )
        )
        assertEquals(now.plusMinutes(5), engine.nextChangeAt(node, ctx(now)))
    }

    @Test
    fun `いつ変わるか分からない条件が混じったら null を返す`() {
        // カレンダーは次の境界を答えられないので、呼び出し側は短い間隔で見に来る
        val node = ConditionNode.AllOf(
            listOf(
                timeRange(22 * 60, 23 * 60),
                ConditionNode.Leaf(CalendarBusyCondition.id, Params.EMPTY),
            )
        )
        assertNull(engine.nextChangeAt(node, ctx(LocalDateTime.of(2026, 8, 6, 12, 0))))

        val rules = listOf(rule(node))
        assertNull(engine.nextChangeAt(rules, ctx(LocalDateTime.of(2026, 8, 6, 12, 0)), noTags))
    }

    @Test
    fun `対象外のアプリしか無ければ何も予定しない`() {
        val rules = listOf(
            rule(ConditionNode.AllOf(listOf(timeRange(22 * 60, 23 * 60))))
                .copy(target = Target(packages = setOf("com.example.other")))
        )
        assertNull(engine.nextChangeAt(rules, ctx(LocalDateTime.of(2026, 8, 6, 12, 0)), noTags))
    }

    @Test
    fun `条件なしのルールは時間では変わらない`() {
        val now = LocalDateTime.of(2026, 8, 6, 12, 0)
        val at = engine.nextChangeAt(ConditionNode.AllOf(), ctx(now))
        assertTrue(at!!.isAfter(now.plusHours(12)))
    }
}
