package com.dopachiru.core

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.condition.types.StudySessionCondition
import com.dopachiru.core.engine.Decision
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.engine.RuleEngine
import com.dopachiru.core.engine.StudyState
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

/**
 * 学習予定との連携。
 *
 * 「予定中は、必要なアプリ以外ぜんぶ止める」が、対象の当たり判定と条件の
 * 組み合わせで成り立っていることを見る。
 */
class StudySessionTest {

    private val engine = RuleEngine()
    private val noTags: (String) -> Set<String> = { emptySet() }

    @Before
    fun setUp() {
        DopaCore.registerAll()
    }

    private fun study(inSession: Boolean, boundary: LocalDateTime?) = object : StudyState {
        override val inSession = inSession
        override val currentTitle: String? = if (inSession) "数学 演習" else null
        override fun nextBoundaryAfter(now: LocalDateTime) = boundary
    }

    private fun ctx(now: LocalDateTime, pkg: String, state: StudyState) = EvalContext(
        now = now,
        packageName = pkg,
        usage = object : UsageSnapshot {
            override val currentSessionMinutes = 0
            override fun usageMinutesIn(policy: ResetPolicy) = 0
            override fun sessionCountIn(policy: ResetPolicy) = 0
        },
        study = state,
    )

    /** 雛形が作るのと同じ形のルール。全アプリ対象、辞書だけ除外。 */
    private val rules = listOf(
        Rule(
            name = "学習中は必要なアプリだけ",
            target = Target(matchAll = true, exceptPackages = setOf("com.example.dict")),
            condition = ConditionNode.AllOf(
                listOf(
                    ConditionNode.Leaf(
                        StudySessionCondition.id,
                        Params.of(StudySessionCondition.KEY_DURING_SESSION to true),
                    ),
                )
            ),
            actionId = BlockAction.id,
            actionParams = Params.of(BlockAction.KEY_ALLOW_OVERRIDE to false),
        )
    )

    private val now = LocalDateTime.of(2026, 8, 10, 10, 30)

    @Test
    fun `予定中は許可したアプリ以外が止まる`() {
        val end = now.withHour(11)

        assertIs<Decision.Act>(
            engine.decide(rules, ctx(now, "com.example.sns", study(true, end)), noTags)
        )
        assertEquals(
            Decision.Allow,
            engine.decide(rules, ctx(now, "com.example.dict", study(true, end)), noTags),
        )
    }

    @Test
    fun `予定が無ければ何も止まらない`() {
        val next = now.withHour(13)
        assertEquals(
            Decision.Allow,
            engine.decide(rules, ctx(now, "com.example.sns", study(false, next)), noTags),
        )
    }

    @Test
    fun `予定の終わりまで一度も起きなくていい`() {
        val end = now.withHour(11)
        assertEquals(
            end,
            engine.nextChangeAt(rules, ctx(now, "com.example.sns", study(true, end)), noTags),
        )
    }

    @Test
    fun `予定の終わりに実際に解ける`() {
        val end = now.withHour(11)
        val wakeAt = engine.nextChangeAt(rules, ctx(now, "com.example.sns", study(true, end)), noTags)!!

        // 起きろと言われた時刻には、もう予定の外にいる
        assertEquals(
            Decision.Allow,
            engine.decide(rules, ctx(wakeAt, "com.example.sns", study(false, null)), noTags),
        )
        // その手前ではまだ止まっている
        assertIs<Decision.Act>(
            engine.decide(
                rules,
                ctx(wakeAt.minusMinutes(1), "com.example.sns", study(true, end)),
                noTags,
            )
        )
    }

    @Test
    fun `境界が分からなければ短い間隔に落とす`() {
        // 呼び出し側は null を「安全側に倒して見に来い」と読む
        assertEquals(
            null,
            engine.nextChangeAt(rules, ctx(now, "com.example.sns", study(true, null)), noTags),
        )
    }
}
