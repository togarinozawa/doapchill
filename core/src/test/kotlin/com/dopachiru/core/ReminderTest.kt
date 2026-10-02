package com.dopachiru.core

import com.dopachiru.core.action.ActionExtras
import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.condition.types.DeclaredBudgetCondition
import com.dopachiru.core.condition.types.TimeRangeCondition
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.engine.RuleEngine
import com.dopachiru.core.engine.UsageSnapshot
import com.dopachiru.core.model.ActionPlan
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Reminder
import com.dopachiru.core.model.ReminderKind
import com.dopachiru.core.model.Reminders
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import org.junit.Before
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReminderTest {

    private val engine = RuleEngine()

    @Before
    fun setUp() = DopaCore.registerAll()

    private fun ctx(at: LocalDateTime, declared: Int? = null) = EvalContext(
        now = at,
        packageName = "com.example.game",
        usage = UsageSnapshot.EMPTY,
        declaredRemainingMinutes = declared,
    )

    private fun leaf(id: String, vararg p: Pair<String, Any?>) = ConditionNode.Leaf(id, Params.of(*p))

    private val night = leaf(
        TimeRangeCondition.id,
        TimeRangeCondition.KEY_START to 22 * 60,
        TimeRangeCondition.KEY_END to 6 * 60,
    )

    @Test
    fun `時間帯は枠の始まりが閉じる時刻`() {
        val now = LocalDateTime.of(2026, 10, 2, 21, 30)
        assertEquals(LocalDateTime.of(2026, 10, 2, 22, 0), engine.closeEta(night, ctx(now)))
        // 枠の中なら「これから閉じる」ではない
        assertNull(engine.closeEta(night, ctx(now.withHour(23))))
        // 朝に枠が明けた後は、その晩の始まり
        assertEquals(
            LocalDateTime.of(2026, 10, 2, 22, 0),
            engine.closeEta(night, ctx(LocalDateTime.of(2026, 10, 2, 8, 0))),
        )
    }

    @Test
    fun `宣言の残りが尽きる時刻`() {
        val c = leaf(DeclaredBudgetCondition.id)
        val now = LocalDateTime.of(2026, 10, 2, 12, 0)
        assertEquals(now.plusMinutes(10), engine.closeEta(c, ctx(now, declared = 10)))
        assertNull(engine.closeEta(c, ctx(now, declared = null)))
    }

    @Test
    fun `AllOf は成立していない子がそろう時刻、見通せない子があれば null`() {
        val now = LocalDateTime.of(2026, 10, 2, 21, 30)
        val unknown = leaf("no_such_condition")
        assertEquals(
            LocalDateTime.of(2026, 10, 2, 22, 0),
            engine.closeEta(ConditionNode.AllOf(listOf(night)), ctx(now)),
        )
        assertNull(engine.closeEta(ConditionNode.AllOf(listOf(night, unknown)), ctx(now)))
    }

    @Test
    fun `知らせは保存して読み戻せる、壊れた文字列は空になる`() {
        val list = listOf(
            Reminder(300, kind = ReminderKind.BAND),
            Reminder(120, 60, ReminderKind.WORDS, "あと1試合で終わる"),
        )
        assertEquals(list, Reminders.decode(Reminders.encode(list)))
        assertTrue(Reminders.decode("{broken").isEmpty())
        assertTrue(Reminders.decode("").isEmpty())
    }

    @Test
    fun `ランダムは範囲内で、同じ種なら同じ値`() {
        val r = Reminder(beforeSeconds = 60, randomUntilSeconds = 300)
        repeat(50) { seed ->
            val v = r.resolveSeconds(seed.toLong())
            assertTrue(v in 60..300)
            assertEquals(v, r.resolveSeconds(seed.toLong()))
        }
        assertEquals(90, Reminder(90).resolveSeconds(7))
    }

    @Test
    fun `計画に載せた知らせが保存に出入りする`() {
        val plan = ActionPlan(reminders = listOf(Reminder(180, kind = ReminderKind.EDGE)))
        val (id, params) = plan.resolve(BlockAction.id, Params.EMPTY)
        assertEquals(BlockAction.id, id)
        assertEquals(plan.reminders, ActionPlan.from(id, params).reminders)
        assertTrue(params.string(ActionExtras.KEY_REMINDERS).isNotBlank())
    }

    @Test
    fun `upcomingCloses は閉じる組を早い順に返す`() {
        val rule = Rule(
            id = 1, uid = "r1", name = "夜", target = Target(packages = setOf("com.example.game")),
            condition = night, actionId = BlockAction.id, actionParams = Params.EMPTY,
        )
        val up = engine.upcomingCloses(
            listOf(rule),
            ctx(LocalDateTime.of(2026, 10, 2, 21, 0)),
        ) { emptySet() }
        assertEquals(1, up.size)
        assertEquals(LocalDateTime.of(2026, 10, 2, 22, 0), up[0].at)
    }
}
