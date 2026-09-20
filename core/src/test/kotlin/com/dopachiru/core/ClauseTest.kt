package com.dopachiru.core

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.DelayAction
import com.dopachiru.core.action.types.IntentionAction
import com.dopachiru.core.action.types.TimerAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.condition.types.TimeRangeCondition
import com.dopachiru.core.engine.Decision
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.engine.RuleEngine
import com.dopachiru.core.engine.UsageSnapshot
import com.dopachiru.core.model.ActionSpec
import com.dopachiru.core.model.Clause
import com.dopachiru.core.model.Clauses
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import org.junit.Before
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「条件 → こうする」を何組も持てるルール。
 *
 * 肝は3つ。
 *  1. **1組目はルール本体のまま**(既存の保存がそのまま読める)
 *  2. **数える鍵(組の番号)を振り直さない**(振り直すと持ち時間の窓が飛ぶ)
 *  3. 組をまたいでも実行するのは**いちばん強い1つ**
 */
class ClauseTest {

    @Before
    fun setUp() = DopaCore.registerAll()

    private val engine = RuleEngine()
    private val youtube = Target(packages = setOf("com.google.android.youtube"))

    private fun ctx(at: LocalDateTime = LocalDateTime.of(2026, 9, 20, 23, 0)) = EvalContext(
        now = at,
        packageName = "com.google.android.youtube",
        usage = UsageSnapshot.EMPTY,
    )

    /** いつでも成立する条件。 */
    private val always = ConditionNode.AllOf(emptyList())

    /** その時刻の範囲でだけ成立する条件。 */
    private fun between(fromHour: Int, toHour: Int) = ConditionNode.Leaf(
        TimeRangeCondition.id,
        Params.of(
            TimeRangeCondition.KEY_START to fromHour * 60,
            TimeRangeCondition.KEY_END to toHour * 60,
        ),
    )

    private fun rule(
        condition: ConditionNode = always,
        actionId: String = BlockAction.id,
        extra: List<Clause> = emptyList(),
    ) = Rule(
        id = 1,
        uid = "r1",
        name = "YouTube",
        target = youtube,
        condition = condition,
        actionId = actionId,
        actionParams = Params.EMPTY,
        extraClauses = extra,
    )

    private fun decide(rule: Rule, at: LocalDateTime = LocalDateTime.of(2026, 9, 20, 23, 0)) =
        engine.decide(listOf(rule), ctx(at)) { emptySet() }

    // ---- 器 ------------------------------------------------------------

    @Test
    fun `1組目はルール本体から作られる`() {
        val clauses = rule().clauses
        assertEquals(1, clauses.size)
        assertEquals(Clauses.FIRST_ID, clauses.first().id)
        assertEquals(BlockAction.id, clauses.first().mainAction?.actionId)
    }

    @Test
    fun `2組目以降は後ろにつく`() {
        val extra = Clause(2, between(22, 24), listOf(ActionSpec(DelayAction.id)))
        val clauses = rule(extra = listOf(extra)).clauses
        assertEquals(listOf(1, 2), clauses.map { it.id })
    }

    @Test
    fun `1組目と番号がかち合うものは捨てる`() {
        // 番号は数える財布の鍵。重なると2つの組が同じ財布を共有してしまう
        val bogus = Clause(Clauses.FIRST_ID, always, listOf(ActionSpec(WarnAction.id)))
        assertEquals(1, rule(extra = listOf(bogus)).clauses.size)
    }

    @Test
    fun `番号は使い回さない`() {
        // 消した組の番号を再利用すると、前の組で数えていた持ち時間を引き継ぐ
        val clauses = listOf(Clause(1, always), Clause(5, always))
        assertEquals(6, Clauses.nextId(clauses))
    }

    @Test
    fun `重ねる動作は先頭を除いたぶん`() {
        val clause = Clause(
            2,
            always,
            listOf(ActionSpec(BlockAction.id), ActionSpec(WarnAction.id)),
        )
        assertEquals(BlockAction.id, clause.mainAction?.actionId)
        assertEquals(listOf(WarnAction.id), clause.overlays.map { it.actionId })
    }

    @Test
    fun `組が2つあるかを見分けられる`() {
        assertFalse(rule().hasManyClauses)
        assertTrue(rule(extra = listOf(Clause(2, always))).hasManyClauses)
    }

    // ---- 判定 ----------------------------------------------------------

    @Test
    fun `2組目だけ成立してもその動作が出る`() {
        // 1組目は昼だけ、2組目は夜だけ。いまは23時
        val r = rule(
            condition = between(9, 12),
            actionId = BlockAction.id,
            extra = listOf(Clause(2, between(22, 24), listOf(ActionSpec(DelayAction.id)))),
        )
        val decision = decide(r)
        assertTrue(decision is Decision.Act)
        assertEquals(DelayAction.id, (decision as Decision.Act).action.id)
        assertEquals(2, decision.clauseId)
    }

    @Test
    fun `両方成立したら強いほうだけ`() {
        // 待たせてから閉じる、では待った時間が無駄になるだけ
        val r = rule(
            condition = always,
            actionId = DelayAction.id,
            extra = listOf(Clause(2, always, listOf(ActionSpec(BlockAction.id)))),
        )
        val decision = decide(r) as Decision.Act
        assertEquals(BlockAction.id, decision.action.id)
        assertEquals(2, decision.clauseId)
    }

    @Test
    fun `どの組も成立しなければ通す`() {
        val r = rule(
            condition = between(9, 12),
            extra = listOf(Clause(2, between(13, 15), listOf(ActionSpec(DelayAction.id)))),
        )
        assertEquals(Decision.Allow, decide(r))
    }

    @Test
    fun `動作を持たない組は飛ばす`() {
        // 壊れた組でルール全体が黙るのはまずい
        val r = rule(
            condition = between(9, 12),
            extra = listOf(
                Clause(2, always, emptyList()),
                Clause(3, always, listOf(ActionSpec(WarnAction.id))),
            ),
        )
        assertEquals(WarnAction.id, (decide(r) as Decision.Act).action.id)
    }

    @Test
    fun `止めてあるルールはどの組も見ない`() {
        val r = rule(extra = listOf(Clause(2, always, listOf(ActionSpec(BlockAction.id)))))
        assertEquals(Decision.Allow, engine.decide(listOf(r.copy(enabled = false)), ctx()) { emptySet() })
    }

    @Test
    fun `対象に当たらなければどの組も見ない`() {
        val r = rule(extra = listOf(Clause(2, always, listOf(ActionSpec(BlockAction.id)))))
        val other = ctx().copy(packageName = "com.example.other")
        assertEquals(Decision.Allow, engine.decide(listOf(r), other) { emptySet() })
    }

    // ---- 覚え書きの重ねがけ --------------------------------------------

    private val timer = ActionSpec(TimerAction.id, Params.EMPTY)
    private val intention = ActionSpec(IntentionAction.id, Params.EMPTY)

    @Test
    fun `覚え書きは主と一緒に集まる`() {
        val r = rule(
            condition = always,
            actionId = BlockAction.id,
            extra = listOf(Clause(2, always, listOf(timer))),
        )
        val decision = decide(r) as Decision.Act
        assertEquals(BlockAction.id, decision.action.id)
        assertEquals(listOf(TimerAction.id), decision.overlays.map { it.actionId })
    }

    @Test
    fun `覚え書きは成立した組ぜんぶから集まる`() {
        val r = rule(
            condition = always,
            actionId = BlockAction.id,
            extra = listOf(
                Clause(2, always, listOf(timer)),
                Clause(3, always, listOf(intention)),
            ),
        )
        val decision = decide(r) as Decision.Act
        assertEquals(setOf(TimerAction.id, IntentionAction.id), decision.overlays.map { it.actionId }.toSet())
    }

    @Test
    fun `成立していない組の覚え書きは集めない`() {
        val r = rule(
            condition = always,
            actionId = BlockAction.id,
            extra = listOf(Clause(2, between(3, 4), listOf(timer))),
        )
        assertTrue((decide(r) as Decision.Act).overlays.isEmpty())
    }

    @Test
    fun `同じ覚え書きが2組から出ても1枚`() {
        val r = rule(
            condition = always,
            actionId = BlockAction.id,
            extra = listOf(
                Clause(2, always, listOf(timer)),
                Clause(3, always, listOf(timer)),
            ),
        )
        assertEquals(1, (decide(r) as Decision.Act).overlays.size)
    }

    @Test
    fun `覚え書きしか無ければそれが主になる`() {
        // 主が無いと何も出せない。いちばん軽いものを立てる
        val r = rule(
            condition = between(3, 4),
            actionId = BlockAction.id,
            extra = listOf(Clause(2, always, listOf(timer, intention))),
        )
        val decision = decide(r) as Decision.Act
        assertEquals(TimerAction.id, decision.action.id)
        assertEquals(listOf(IntentionAction.id), decision.overlays.map { it.actionId })
    }

    @Test
    fun `覆うものは2つ目以降に置いても出ない`() {
        // 覆いの裏に隠れて見えないので、重ねても嘘になる
        val r = rule(
            condition = always,
            actionId = WarnAction.id,
            extra = listOf(Clause(2, always, listOf(timer, ActionSpec(BlockAction.id, Params.EMPTY)))),
        )
        val decision = decide(r) as Decision.Act
        assertEquals(WarnAction.id, decision.action.id)
        assertEquals(listOf(TimerAction.id), decision.overlays.map { it.actionId })
    }

    @Test
    fun `重ねられるのは画面を覆わないものだけ`() {
        assertTrue(TimerAction.stackable)
        assertTrue(IntentionAction.stackable)
        assertFalse(BlockAction.stackable)
        assertFalse(WarnAction.stackable)
    }

    // ---- 組の番号が条件に届くか ----------------------------------------

    @Test
    fun `評価する組の番号が文脈に乗る`() {
        // 持ち時間の財布を組ごとに分けるための鍵。乗っていないと分けられない
        val r = rule()
        val clause = Clause(7, always, listOf(ActionSpec(BlockAction.id)))
        val scoped = ctx().forClause(r, clause)

        assertEquals(7, scoped.currentClauseId)
        assertEquals(r.id, scoped.currentRuleId)
        assertEquals(r.uid, scoped.currentRuleUid)
    }

    @Test
    fun `1組しか無ければ番号は常に1`() {
        assertEquals(Clauses.FIRST_ID, ctx().currentClauseId)
        assertEquals(
            Clauses.FIRST_ID,
            ctx().forClause(rule(), rule().clauses.first()).currentClauseId,
        )
    }
}
