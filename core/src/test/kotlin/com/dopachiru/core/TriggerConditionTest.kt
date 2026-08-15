package com.dopachiru.core

import com.dopachiru.core.action.Rotation
import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.condition.types.AppChainCondition
import com.dopachiru.core.condition.types.ChanceCondition
import com.dopachiru.core.condition.types.HabituationCondition
import com.dopachiru.core.condition.types.QuickReopenCondition
import com.dopachiru.core.engine.Decision
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.engine.RuleEngine
import com.dopachiru.core.engine.UsageSnapshot
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Consequence
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

/** 研究知見から足した引き金まわりの条件。 */
class TriggerConditionTest {

    private val engine = RuleEngine()
    private val noTags: (String) -> Set<String> = { emptySet() }

    @Before
    fun setUp() {
        DopaCore.registerAll()
    }

    private fun ctx(
        pkg: String = "com.example.sns",
        previous: String? = null,
        gapMinutes: Int? = null,
        sessionSeed: Long = 1000L,
        ruleId: Long = 1L,
        overrides: Int = 0,
    ) = EvalContext(
        now = LocalDateTime.of(2026, 8, 15, 12, 0),
        packageName = pkg,
        usage = object : UsageSnapshot {
            override val currentSessionMinutes = 0
            override fun usageMinutesIn(policy: ResetPolicy) = 0
            override fun sessionCountIn(policy: ResetPolicy) = 0
            override val minutesSinceLastSession = gapMinutes
        },
        previousPackage = previous,
        sessionSeed = sessionSeed,
        currentRuleId = ruleId,
        overrideCountOf = { overrides },
    )

    // ---- 直前のアプリ ---------------------------------------------------

    @Test
    fun `選んだアプリの直後だけ成立する`() {
        val p = Params.of(AppChainCondition.KEY_PACKAGES to listOf("com.example.chat"))

        assertTrue(AppChainCondition.evaluate(p, ctx(previous = "com.example.chat")))
        assertFalse(AppChainCondition.evaluate(p, ctx(previous = "com.example.mail")))
    }

    @Test
    fun `ホームから開いたときの扱いを選べる`() {
        val packages = listOf("com.example.chat")
        val excluded = Params.of(
            AppChainCondition.KEY_PACKAGES to packages,
            AppChainCondition.KEY_FROM_HOME to false,
        )
        val included = Params.of(
            AppChainCondition.KEY_PACKAGES to packages,
            AppChainCondition.KEY_FROM_HOME to true,
        )

        assertFalse(AppChainCondition.evaluate(excluded, ctx(previous = null)))
        assertTrue(AppChainCondition.evaluate(included, ctx(previous = null)))
    }

    // ---- 開き直し -------------------------------------------------------

    @Test
    fun `短い間隔で開き直したときだけ成立する`() {
        val p = Params.of(QuickReopenCondition.KEY_WITHIN_MINUTES to 5)

        assertTrue(QuickReopenCondition.evaluate(p, ctx(gapMinutes = 2)))
        assertTrue(QuickReopenCondition.evaluate(p, ctx(gapMinutes = 5)))
        assertFalse(QuickReopenCondition.evaluate(p, ctx(gapMinutes = 6)))
    }

    @Test
    fun `初めて開いたときは成立しない`() {
        // 記録が無いのを「間隔ゼロ」と扱うと、初回から止まって使い物にならない
        val p = Params.of(QuickReopenCondition.KEY_WITHIN_MINUTES to 5)
        assertFalse(QuickReopenCondition.evaluate(p, ctx(gapMinutes = null)))
    }

    // ---- 確率 -----------------------------------------------------------

    @Test
    fun `確率0と100は必ずその通りになる`() {
        val never = Params.of(ChanceCondition.KEY_PERCENT to 0)
        val always = Params.of(ChanceCondition.KEY_PERCENT to 100)
        repeat(20) { seed ->
            assertFalse(ChanceCondition.evaluate(never, ctx(sessionSeed = seed.toLong())))
            assertTrue(ChanceCondition.evaluate(always, ctx(sessionSeed = seed.toLong())))
        }
    }

    @Test
    fun `同じセッションのあいだ答えが変わらない`() {
        // ここが崩れると、ブロックが数秒おきに出たり消えたりする
        val p = Params.of(ChanceCondition.KEY_PERCENT to 50)
        val first = ChanceCondition.evaluate(p, ctx(sessionSeed = 777L))
        repeat(50) {
            assertEquals(first, ChanceCondition.evaluate(p, ctx(sessionSeed = 777L)))
        }
    }

    @Test
    fun `セッションが変われば引き直す`() {
        val p = Params.of(ChanceCondition.KEY_PERCENT to 50)
        val results = (1L..200L).map { ChanceCondition.evaluate(p, ctx(sessionSeed = it)) }

        assertTrue(results.any { it }, "一度も成立しないなら抽選が壊れている")
        assertTrue(results.any { !it }, "毎回成立するなら抽選が壊れている")
        // 50%指定で極端に偏っていないか。乱数ではなく決定的な写像なので、
        // 幅は広めに取って「明らかにおかしい」だけを弾く
        val hits = results.count { it }
        assertTrue(hits in 60..140, "50%のはずが $hits/200")
    }

    @Test
    fun `ルールが違えば別々に抽選される`() {
        // 同じ種でも、隣のルールと結果が連動しては困る
        val p = Params.of(ChanceCondition.KEY_PERCENT to 50)
        val a = (1L..100L).map { ChanceCondition.evaluate(p, ctx(sessionSeed = it, ruleId = 1L)) }
        val b = (1L..100L).map { ChanceCondition.evaluate(p, ctx(sessionSeed = it, ruleId = 2L)) }
        assertTrue(a != b, "別のルールでも同じ結果が並ぶなら、種にルールが効いていない")
    }

    // ---- 慣れ -----------------------------------------------------------

    @Test
    fun `押し切りが積み上がったときだけ成立する`() {
        val p = Params.of(HabituationCondition.KEY_OVERRIDES to 3)

        assertFalse(HabituationCondition.evaluate(p, ctx(overrides = 2)))
        assertTrue(HabituationCondition.evaluate(p, ctx(overrides = 3)))
        assertTrue(HabituationCondition.evaluate(p, ctx(overrides = 9)))
    }

    @Test
    fun `エンジンはルールごとの押し切り回数を渡す`() {
        // 慣れの判定はルール単位。エンジンが currentRuleId を差し込まないと
        // どのルールの話か分からず、常に 0 のままになる
        val counts = mapOf(7L to 5)
        val rule = Rule(
            id = 7L,
            name = "慣れたら強くする",
            target = Target(packages = setOf("com.example.sns")),
            condition = ConditionNode.Leaf(
                HabituationCondition.id,
                Params.of(HabituationCondition.KEY_OVERRIDES to 3),
            ),
            actionId = BlockAction.id,
        )
        val context = ctx(ruleId = 0L).copy(overrideCountOf = { counts[it] ?: 0 })

        assertIs<Decision.Act>(engine.decide(listOf(rule), context, noTags))
        // 回数が足りないルールは成立しない
        assertEquals(
            Decision.Allow,
            engine.decide(listOf(rule.copy(id = 8L)), context, noTags),
        )
    }

    // ---- 文のローテーション ---------------------------------------------

    @Test
    fun `候補が1つならそのまま返す`() {
        assertEquals("ひとつだけ", Rotation.pick("ひとつだけ", ctx()))
        assertFalse(Rotation.rotates("ひとつだけ"))
    }

    @Test
    fun `候補が複数ならセッションごとに変わる`() {
        val text = "いち\nに\nさん\nよん"
        assertTrue(Rotation.rotates(text))

        val picks = (1L..100L).map { Rotation.pick(text, ctx(sessionSeed = it)) }
        assertTrue(picks.toSet().size >= 3, "候補が偏りすぎている: ${picks.toSet()}")
        assertTrue(picks.all { it in listOf("いち", "に", "さん", "よん") })

        // 同じセッションでは変わらない
        val fixed = Rotation.pick(text, ctx(sessionSeed = 42L))
        repeat(20) { assertEquals(fixed, Rotation.pick(text, ctx(sessionSeed = 42L))) }
    }

    @Test
    fun `空の候補は空行を無視する`() {
        assertEquals(listOf("あ", "い"), Rotation.optionsOf("\n あ \n\n い \n"))
        assertEquals("代わり", Rotation.pick("   ", ctx(), fallback = "代わり"))
    }

    // ---- 段階的な封鎖 ---------------------------------------------------

    @Test
    fun `段階を切っていれば毎回同じ長さ`() {
        val c = Consequence(lockMinutes = 30, lockEscalates = false)
        assertEquals(30, c.lockMinutesFor(0))
        assertEquals(30, c.lockMinutesFor(5))
    }

    @Test
    fun `段階を入れると繰り返すほど長くなる`() {
        val c = Consequence(lockMinutes = 5, lockEscalates = true)
        assertEquals(5, c.lockMinutesFor(0))
        assertEquals(10, c.lockMinutesFor(1))
        assertEquals(20, c.lockMinutesFor(2))
        assertEquals(40, c.lockMinutesFor(3))
    }

    @Test
    fun `段階を重ねても上限を越えない`() {
        val c = Consequence(lockMinutes = 60, lockEscalates = true)
        assertEquals(Consequence.MAX_LOCK_MINUTES, c.lockMinutesFor(99))
        assertTrue(c.lockMinutesFor(99) > 0, "溢れて負や 0 になっていないか")
    }
}
