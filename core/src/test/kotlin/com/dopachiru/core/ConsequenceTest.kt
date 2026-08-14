package com.dopachiru.core

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.engine.Decision
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.engine.RuleEngine
import com.dopachiru.core.engine.UsageSnapshot
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Consequence
import com.dopachiru.core.model.LockScope
import com.dopachiru.core.model.Lockout
import com.dopachiru.core.model.Lockouts
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import com.dopachiru.core.points.PointPolicy
import com.dopachiru.core.time.ResetPolicy
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsequenceTest {

    private val engine = RuleEngine()
    private val noTags: (String) -> Set<String> = { emptySet() }
    private val now = 1_000_000L

    @Before
    fun setUp() {
        DopaCore.registerAll()
    }

    private fun ctx(pkg: String = "com.example.sns") = EvalContext(
        now = LocalDateTime.of(2026, 8, 14, 12, 0),
        packageName = pkg,
        usage = object : UsageSnapshot {
            override val currentSessionMinutes = 0
            override fun usageMinutesIn(policy: ResetPolicy) = 0
            override fun sessionCountIn(policy: ResetPolicy) = 0
        },
    )

    private fun lockout(target: Target, untilSec: Long) =
        Lockout(target = target, untilEpochSec = untilSec, reason = "テスト", createdAtEpochSec = now)

    // ---- 範囲の解決 -----------------------------------------------------

    @Test
    fun `そのアプリだけの罰は他のアプリに及ばない`() {
        val target = Consequence(LockScope.APP, lockMinutes = 30)
            .resolveTarget("com.example.sns", Target(matchAll = true))!!

        assertTrue(target.matches("com.example.sns", emptySet()))
        assertTrue(!target.matches("com.example.other", emptySet()))
    }

    @Test
    fun `ルールの対象ぜんぶの罰はタグ単位で閉まる`() {
        val ruleTarget = Target(tags = setOf("SNS"))
        val target = Consequence(LockScope.RULE_TARGET, lockMinutes = 30)
            .resolveTarget("com.example.sns", ruleTarget)!!

        assertTrue(target.matches("com.example.other", setOf("SNS")))
        assertTrue(!target.matches("com.example.other", setOf("仕事")))
    }

    @Test
    fun `端末ぜんぶの罰でも逃がしたアプリは開く`() {
        val target = Consequence(
            LockScope.EVERYTHING,
            lockMinutes = 60,
            lockAllowPackages = setOf("com.example.dictionary"),
            lockAllowTags = setOf("仕事"),
        ).resolveTarget("com.example.sns", Target())!!

        assertTrue(target.matches("com.example.sns", emptySet()))
        assertTrue(!target.matches("com.example.dictionary", emptySet()))
        assertTrue(!target.matches("com.example.mail", setOf("仕事")))
    }

    @Test
    fun `分が0なら封鎖しない`() {
        assertNull(Consequence(LockScope.EVERYTHING, lockMinutes = 0).resolveTarget("x", Target()))
        assertTrue(Consequence(LockScope.NONE, lockMinutes = 60).locksNothing)
    }

    // ---- 封鎖の効き方 ---------------------------------------------------

    @Test
    fun `封鎖はルールより先に効く`() {
        // ルールは1つも成立しないのに、罰で閉まっている
        val rules = listOf(
            Rule(
                name = "昼は封印",
                target = Target(packages = setOf("com.example.other")),
                condition = ConditionNode.AllOf(),
                actionId = BlockAction.id,
            )
        )
        val locks = listOf(lockout(Target(packages = setOf("com.example.sns")), now + 600))

        val decision = engine.decide(rules, locks, ctx(), now, 0L, noTags)
        assertIs<Decision.Locked>(decision)
    }

    @Test
    fun `期限が切れた封鎖は効かない`() {
        val locks = listOf(lockout(Target(matchAll = true), now - 1))
        assertEquals(Decision.Allow, engine.decide(emptyList(), locks, ctx(), now, 0L, noTags))
        assertEquals(emptyList(), Lockouts.prune(locks, now))
    }

    @Test
    fun `解禁券はルールを止めるが罰は解かない`() {
        val rules = listOf(
            Rule(
                name = "常に封印",
                target = Target(packages = setOf("com.example.sns")),
                condition = ConditionNode.AllOf(),
                actionId = BlockAction.id,
            )
        )
        val passUntil = now + 900

        // ルールだけなら券で止まる
        assertEquals(
            Decision.Allow,
            engine.decide(rules, emptyList(), ctx(), now, passUntil, noTags),
        )

        // 罰は券では解けない。ここを通すと「押し切る手段はありません」が嘘になる
        val locks = listOf(lockout(Target(packages = setOf("com.example.sns")), now + 600))
        assertIs<Decision.Locked>(
            engine.decide(rules, locks, ctx(), now, passUntil, noTags),
        )
    }

    @Test
    fun `罰が重なったら遅いほうまで閉まる`() {
        val locks = listOf(
            lockout(Target(matchAll = true), now + 600),
            lockout(Target(packages = setOf("com.example.sns")), now + 3600),
        )
        val active = Lockouts.activeFor(locks, "com.example.sns", emptySet(), now)
        assertEquals(now + 3600, active?.untilEpochSec)
    }

    @Test
    fun `残り時間は切り上げる`() {
        val lock = lockout(Target(matchAll = true), now + 61)
        assertEquals(2, lock.remainingMinutesAt(now))
        assertEquals(0, lock.remainingMinutesAt(now + 61))
    }

    // ---- ポイント -------------------------------------------------------

    @Test
    fun `ポイントの指定なしは設定の既定値に落ちる`() {
        val policy = PointPolicy(defaultBreakPoints = -7, defaultKeepPoints = 2)
        assertEquals(-7, policy.breakDelta(null))
        assertEquals(2, policy.keepDelta(null))
        assertEquals(-30, policy.breakDelta(-30))
    }

    @Test
    fun `代金を取らない設定なら押し切りはタダ`() {
        assertEquals(10, PointPolicy(chargeOverride = true, defaultBreakPoints = -10).overrideCost(null))
        assertEquals(0, PointPolicy(chargeOverride = false).overrideCost(null))
        assertEquals(0, PointPolicy(enabled = false).overrideCost(null))
        // 加点になっている罰から代金は取らない
        assertEquals(0, PointPolicy().overrideCost(5))
    }

    @Test
    fun `使い道を両方切ると記録だけになる`() {
        assertTrue(PointPolicy(chargeOverride = false, passEnabled = false).recordOnly)
        assertTrue(!PointPolicy(chargeOverride = false, passEnabled = true).recordOnly)
    }

    @Test
    fun `罰つきルールはJSONで往復できる`() {
        val original = Rule(
            name = "夜に押し切ったらお預け",
            target = Target(packages = setOf("com.example.sns")),
            condition = ConditionNode.AllOf(),
            actionId = BlockAction.id,
            actionParams = Params.EMPTY,
            consequence = Consequence(
                lockScope = LockScope.EVERYTHING,
                lockMinutes = 45,
                lockAllowPackages = setOf("com.example.dictionary"),
                breakPoints = -25,
                keepPoints = 3,
            ),
        )
        assertEquals(original, DopaCore.decodeRule(DopaCore.encodeRule(original)))
    }

    @Test
    fun `罰の無い古いルールも読める`() {
        val json = """
            {"id":1,"uid":"u1","name":"夜は開かない","enabled":true,
             "target":{"packages":["com.example.sns"]},
             "condition":{"kind":"allOf","children":[]},
             "actionId":"block","actionParams":{}}
        """.trimIndent()
        val rule = DopaCore.decodeRule(json)
        assertEquals(Consequence.NONE, rule.consequence)
        assertTrue(rule.consequence.locksNothing)
        assertNull(rule.consequence.breakPoints)
    }
}
