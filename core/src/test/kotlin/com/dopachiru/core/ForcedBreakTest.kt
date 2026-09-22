package com.dopachiru.core

import com.dopachiru.core.action.types.LockoutAction
import com.dopachiru.core.condition.types.UsageSinceBreakCondition
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.engine.UsageSnapshot
import com.dopachiru.core.engine.UsageSpans
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.RuleCheck
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「n分使ったらm分閉め出す」の噛み合わせ。
 *
 * 数え方([UsageSpans])と、閉める範囲([LockoutAction])を別々に確かめる。
 * この2つが合っていないと「閉め出しが明けた瞬間にまた閉まる」になる。
 */
class ForcedBreakTest {

    private val now = 10_000L

    /** 分を秒に。読みやすさのためだけ。 */
    private fun min(m: Long) = m * 60

    // ---- 数え方 --------------------------------------------------------

    @Test
    fun `使いっぱなしなら全部足す`() {
        val spans = listOf((now - min(20)) to now)
        assertEquals(20, UsageSpans.minutesSinceBreak(spans, breakMinutes = 10, nowSec = now))
    }

    @Test
    fun `短い離席では切れない`() {
        // 10分使う → 2分離れる → 8分使う。休憩は10分なので切れない
        val spans = listOf(
            (now - min(20)) to (now - min(10)),
            (now - min(8)) to now,
        )
        assertEquals(18, UsageSpans.minutesSinceBreak(spans, breakMinutes = 10, nowSec = now))
    }

    @Test
    fun `休憩をはさんだら数え直す`() {
        // 30分使う → 10分離れる → 5分使う
        val spans = listOf(
            (now - min(45)) to (now - min(15)),
            (now - min(5)) to now,
        )
        assertEquals(5, UsageSpans.minutesSinceBreak(spans, breakMinutes = 10, nowSec = now))
    }

    @Test
    fun `いま離れている最中なら0`() {
        // 20分使って、そのあと12分空いている
        val spans = listOf((now - min(32)) to (now - min(12)))
        assertEquals(0, UsageSpans.minutesSinceBreak(spans, breakMinutes = 10, nowSec = now))
    }

    @Test
    fun `別々のアプリを渡り歩いても足される`() {
        // 呼び出し側が対象アプリぶんをまとめて渡す想定。
        // 「Xを8分 → YouTubeを9分」で17分になる
        val spans = listOf(
            (now - min(17)) to (now - min(9)),
            (now - min(9)) to now,
        )
        assertEquals(17, UsageSpans.minutesSinceBreak(spans, breakMinutes = 10, nowSec = now))
    }

    @Test
    fun `記録が無ければ0`() {
        assertEquals(0, UsageSpans.minutesSinceBreak(emptyList(), breakMinutes = 10, nowSec = now))
    }

    // ---- 条件 ----------------------------------------------------------

    private fun ctx(used: Int) = EvalContext(
        now = LocalDateTime.of(2026, 9, 7, 12, 0),
        packageName = "com.x",
        usage = UsageSnapshot.EMPTY,
        currentRuleId = 7L,
        minutesSinceBreakOf = { ruleId, _ -> if (ruleId == 7L) used else 0 },
    )

    private val params = Params.of(
        UsageSinceBreakCondition.KEY_MINUTES to 20,
        UsageSinceBreakCondition.KEY_BREAK_MINUTES to 10,
    )

    @Test
    fun `閾値に届くまでは成立しない`() {
        assertFalse(UsageSinceBreakCondition.evaluate(params, ctx(19)))
        assertTrue(UsageSinceBreakCondition.evaluate(params, ctx(20)))
    }

    @Test
    fun `数えられない端末では成立しない`() {
        // minutesSinceBreakOf を渡さない = 実測を持たない側。空振りするだけで暴発しない
        val bare = EvalContext(
            now = LocalDateTime.of(2026, 9, 7, 12, 0),
            packageName = "com.x",
            usage = UsageSnapshot.EMPTY,
        )
        assertFalse(UsageSinceBreakCondition.evaluate(params, bare))
    }

    @Test
    fun `残りぶんだけ眠る`() {
        val at = UsageSinceBreakCondition.nextChangeAt(params, ctx(12))
        assertEquals(ctx(12).now.plusMinutes(8), at)
    }

    @Test
    fun `届いた後は休憩ぶん眠る`() {
        val at = UsageSinceBreakCondition.nextChangeAt(params, ctx(25))
        assertEquals(ctx(25).now.plusMinutes(10), at)
    }

    // ---- 閉め出し ------------------------------------------------------

    @Test
    fun `対象ぜんぶを閉める`() {
        val p = Params.of(
            LockoutAction.KEY_MINUTES to 10,
            LockoutAction.KEY_SCOPE to LockoutAction.Scope.TARGET,
        )
        val ruleTarget = Target(tags = setOf("SNS"))
        assertEquals(ruleTarget, LockoutAction.resolveTarget(p, "com.x", ruleTarget))
    }

    @Test
    fun `そのアプリだけを閉める`() {
        val p = Params.of(
            LockoutAction.KEY_MINUTES to 10,
            LockoutAction.KEY_SCOPE to LockoutAction.Scope.APP,
        )
        val resolved = LockoutAction.resolveTarget(p, "com.x", Target(tags = setOf("SNS")))
        assertEquals(Target(packages = setOf("com.x")), resolved)
    }

    @Test
    fun `既定は対象ぜんぶ`() {
        // 範囲が空のまま保存された古いルールを読んでも、狭いほうに落ちない
        val ruleTarget = Target(tags = setOf("SNS"))
        assertEquals(ruleTarget, LockoutAction.resolveTarget(Params.EMPTY, "com.x", ruleTarget))
    }

    @Test
    fun `0分は科さない`() {
        // 分数は必ず1以上に丸める。0分の閉め出しは「効かないのに画面だけ出る」
        assertEquals(1, LockoutAction.minutesFor(Params.of(LockoutAction.KEY_MINUTES to 0), 0))
    }

    @Test
    fun `繰り返すほど長くなるのは切ったときだけ`() {
        val flat = Params.of(LockoutAction.KEY_MINUTES to 10)
        assertEquals(10, LockoutAction.minutesFor(flat, 3))

        val steps = Params.of(LockoutAction.KEY_MINUTES to 10, LockoutAction.KEY_ESCALATES to true)
        assertEquals(10, LockoutAction.minutesFor(steps, 0))
        assertEquals(20, LockoutAction.minutesFor(steps, 1))
        assertEquals(40, LockoutAction.minutesFor(steps, 2))
    }

    @Test
    fun `段階を重ねても上限を越えない`() {
        val steps = Params.of(LockoutAction.KEY_MINUTES to 60, LockoutAction.KEY_ESCALATES to true)
        assertEquals(LockoutAction.MAX_MINUTES, LockoutAction.minutesFor(steps, 99))
        assertTrue(LockoutAction.minutesFor(steps, 99) > 0, "溢れて負や 0 になっていないか")
    }

    @Test
    fun `閉め出しは完全封印より強い`() {
        assertTrue(LockoutAction.severity > com.dopachiru.core.action.types.BlockAction.severity)
    }
}

/** 保存はできるが、書いたとおりには効かない組み合わせ。 */
class RuleCheckTest {

    private fun breakLeaf(breakMinutes: Int) = ConditionNode.AllOf(
        listOf(
            ConditionNode.Leaf(
                UsageSinceBreakCondition.id,
                Params.of(
                    UsageSinceBreakCondition.KEY_MINUTES to 20,
                    UsageSinceBreakCondition.KEY_BREAK_MINUTES to breakMinutes,
                ),
            ),
        ),
    )

    private fun lockParams(minutes: Int, scope: String = LockoutAction.Scope.TARGET) =
        Params.of(LockoutAction.KEY_MINUTES to minutes, LockoutAction.KEY_SCOPE to scope)

    @Test
    fun `噛み合っていれば何も言わない`() {
        val warnings = RuleCheck.warnings(
            condition = breakLeaf(10),
            target = Target(packages = setOf("com.x")),
            actionId = LockoutAction.id,
            actionParams = lockParams(10),
        )
        assertTrue(warnings.isEmpty(), warnings.toString())
    }

    @Test
    fun `数え直しが閉め出しより長いと知らせる`() {
        val warnings = RuleCheck.warnings(
            condition = breakLeaf(30),
            target = Target(packages = setOf("com.x")),
            actionId = LockoutAction.id,
            actionParams = lockParams(10),
        )
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("また閉まります"))
    }

    @Test
    fun `URL だけの対象では数えられないと知らせる`() {
        val warnings = RuleCheck.warnings(
            condition = breakLeaf(10),
            target = Target(sites = setOf("youtube.com/shorts")),
            actionId = LockoutAction.id,
            actionParams = lockParams(10),
        )
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("アプリの使用時間"))
    }

    @Test
    fun `全アプリを閉めるときは断る前に言う`() {
        val warnings = RuleCheck.warnings(
            condition = breakLeaf(10),
            target = Target(matchAll = true),
            actionId = LockoutAction.id,
            actionParams = lockParams(10),
        )
        assertTrue(warnings.any { it.contains("端末全体") })
    }

    @Test
    fun `閉め出さないアクションには噛み合わせの話をしない`() {
        val warnings = RuleCheck.warnings(
            condition = breakLeaf(30),
            target = Target(packages = setOf("com.x")),
            actionId = com.dopachiru.core.action.types.BlockAction.id,
            actionParams = Params.EMPTY,
        )
        assertTrue(warnings.isEmpty())
    }
}
