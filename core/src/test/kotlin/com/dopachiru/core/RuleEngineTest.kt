package com.dopachiru.core

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.WarnAction
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuleEngineTest {

    private val engine = RuleEngine()
    private val noTags: (String) -> Set<String> = { emptySet() }

    @Before
    fun setUp() {
        DopaCore.registerAll()
    }

    private fun ctx(
        now: LocalDateTime,
        pkg: String = "com.example.sns",
        sessionMinutes: Int = 0,
    ) = EvalContext(
        now = now,
        packageName = pkg,
        usage = object : UsageSnapshot {
            override val currentSessionMinutes = sessionMinutes
            override fun usageMinutesIn(policy: ResetPolicy) = 0
            override fun sessionCountIn(policy: ResetPolicy) = 0
        },
    )

    private fun rule(
        name: String,
        actionId: String,
        condition: ConditionNode,
        packages: Set<String> = setOf("com.example.sns"),
    ) = Rule(
        name = name,
        target = Target(packages = packages),
        condition = condition,
        actionId = actionId,
        actionParams = Params.EMPTY,
    )

    @Test
    fun `日をまたぐ時間帯が正しく判定される`() {
        val p = Params.of(
            TimeRangeCondition.KEY_START to 22 * 60,
            TimeRangeCondition.KEY_END to 6 * 60,
        )
        assertTrue(TimeRangeCondition.evaluate(p, ctx(LocalDateTime.of(2026, 8, 6, 23, 30))))
        assertTrue(TimeRangeCondition.evaluate(p, ctx(LocalDateTime.of(2026, 8, 6, 2, 0))))
        assertFalse(TimeRangeCondition.evaluate(p, ctx(LocalDateTime.of(2026, 8, 6, 12, 0))))
        assertFalse(TimeRangeCondition.evaluate(p, ctx(LocalDateTime.of(2026, 8, 6, 6, 0))))
    }

    @Test
    fun `対象外のアプリには何も起きない`() {
        val rules = listOf(rule("夜間封印", BlockAction.id, ConditionNode.AllOf()))
        val decision = engine.decide(rules, ctx(LocalDateTime.of(2026, 8, 6, 23, 0), pkg = "com.example.other"), noTags)
        assertEquals(Decision.Allow, decision)
    }

    @Test
    fun `複数のルールが成立したら強いアクションが勝つ`() {
        val rules = listOf(
            rule("軽い警告", WarnAction.id, ConditionNode.AllOf()),
            rule("完全封印", BlockAction.id, ConditionNode.AllOf()),
        )
        val decision = engine.decide(rules, ctx(LocalDateTime.of(2026, 8, 6, 23, 0)), noTags)
        val act = assertIs<Decision.Act>(decision)
        assertEquals(BlockAction.id, act.action.id)
    }

    @Test
    fun `AND は片方でも崩れると成立しない`() {
        val condition = ConditionNode.AllOf(
            listOf(
                ConditionNode.Leaf(
                    TimeRangeCondition.id,
                    Params.of(TimeRangeCondition.KEY_START to 22 * 60, TimeRangeCondition.KEY_END to 6 * 60),
                ),
                ConditionNode.Leaf(
                    ContinuousUsageCondition.id,
                    Params.of(ContinuousUsageCondition.KEY_MINUTES to 30),
                ),
            )
        )
        val rules = listOf(rule("夜に長居したら封印", BlockAction.id, condition))

        // 時間帯は満たすが連続使用が足りない
        assertEquals(
            Decision.Allow,
            engine.decide(rules, ctx(LocalDateTime.of(2026, 8, 6, 23, 0), sessionMinutes = 10), noTags),
        )
        // 両方満たす
        assertIs<Decision.Act>(
            engine.decide(rules, ctx(LocalDateTime.of(2026, 8, 6, 23, 0), sessionMinutes = 45), noTags),
        )
    }

    @Test
    fun `未登録の条件IDは成立しない扱いになる`() {
        val rules = listOf(
            rule("将来の条件", BlockAction.id, ConditionNode.Leaf("not_implemented_yet", Params.EMPTY)),
        )
        assertEquals(Decision.Allow, engine.decide(rules, ctx(LocalDateTime.of(2026, 8, 6, 23, 0)), noTags))
    }

    @Test
    fun `ルールはJSONで往復できる`() {
        val original = rule(
            "夜間封印",
            BlockAction.id,
            ConditionNode.AllOf(
                listOf(
                    ConditionNode.Leaf(
                        TimeRangeCondition.id,
                        Params.of(TimeRangeCondition.KEY_START to 22 * 60, TimeRangeCondition.KEY_END to 6 * 60),
                    ),
                    ConditionNode.Not(
                        ConditionNode.Leaf(ContinuousUsageCondition.id, Params.of(ContinuousUsageCondition.KEY_MINUTES to 5)),
                    ),
                )
            ),
        ).copy(actionParams = Params.of(BlockAction.KEY_MIN_SECONDS to 30, BlockAction.KEY_REFLECTION to "寝ろ"))

        val restored = DopaCore.decodeRule(DopaCore.encodeRule(original))
        assertEquals(original, restored)
        assertEquals(30, restored.actionParams.int(BlockAction.KEY_MIN_SECONDS))
        assertEquals("寝ろ", restored.actionParams.string(BlockAction.KEY_REFLECTION))
    }
}
