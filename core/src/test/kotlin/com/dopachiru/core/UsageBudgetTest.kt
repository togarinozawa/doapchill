package com.dopachiru.core

import com.dopachiru.core.condition.ConditionRegistry
import com.dopachiru.core.condition.types.TotalUsageCondition
import com.dopachiru.core.condition.types.UsageBudgetCondition
import com.dopachiru.core.condition.types.UsageSinceBreakCondition
import com.dopachiru.core.condition.types.WindowBudgetCondition
import com.dopachiru.core.engine.BudgetQuery
import com.dopachiru.core.engine.BudgetReset
import com.dopachiru.core.engine.CountBy
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.engine.UsageBudgets
import com.dopachiru.core.engine.UsageSnapshot
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.RuleMigrations
import com.dopachiru.core.model.Target
import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.param.Params
import com.dopachiru.core.time.ResetPolicy
import org.junit.Before
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 使いすぎを止める条件。
 *
 * 肝は **何分使ったら** と **どこで数え直すか** の2つだけであること、そして
 * 古い3つが**効きかたを変えずに**ここへ移ること。
 */
class UsageBudgetTest {

    private val zone = ZoneId.of("Asia/Tokyo")

    @Before
    fun setUp() = DopaCore.registerAll()

    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 9, 20, hour, minute).atZone(zone).toEpochSecond()

    private fun query(
        reset: BudgetReset,
        awayMinutes: Int = 30,
        windowMinutes: Int = 180,
        period: ResetPolicy = ResetPolicy(),
    ) = BudgetQuery(
        ruleId = 1,
        clauseId = 1,
        packageName = "com.google.android.youtube",
        countBy = CountBy.GROUP,
        reset = reset,
        awayMinutes = awayMinutes,
        windowMinutes = windowMinutes,
        period = period,
    )

    private fun compute(
        q: BudgetQuery,
        spans: List<Pair<Long, Long>>,
        nowHour: Int,
        nowMinute: Int = 0,
    ) = UsageBudgets.compute(
        q,
        spans,
        LocalDateTime.of(2026, 9, 20, nowHour, nowMinute),
        at(nowHour, nowMinute),
        zone,
    )

    // ---- 離れたら数え直す ----------------------------------------------

    @Test
    fun `離れたら数え直す`() {
        // 10:00-10:30 使って、いま12:00。30分離れているので数え直し済み
        val spans = listOf(at(10) to at(10, 30))
        assertEquals(0, compute(query(BudgetReset.AWAY, awayMinutes = 30), spans, 12).usedMinutes)
    }

    @Test
    fun `まだ離れていなければ足され続ける`() {
        // 10:00-10:30 と 10:40-11:00。あいだは10分で、休憩には足りない
        val spans = listOf(at(10) to at(10, 30), at(10, 40) to at(11))
        val usage = compute(query(BudgetReset.AWAY, awayMinutes = 30), spans, 11, 5)
        assertEquals(50, usage.usedMinutes)
    }

    @Test
    fun `残りは数え直しまでの時間`() {
        // 11:00 に終わって、いま11:10。あと20分離れれば数え直し
        val spans = listOf(at(10) to at(11))
        assertEquals(20, compute(query(BudgetReset.AWAY, awayMinutes = 30), spans, 11, 10).remainingMinutes)
    }

    @Test
    fun `数えるものが無ければ待つ必要も無い`() {
        assertEquals(0, compute(query(BudgetReset.AWAY), emptyList(), 12).remainingMinutes)
    }

    // ---- 窓 ------------------------------------------------------------

    @Test
    fun `窓は最初に触った時刻に張られる`() {
        // 10:00 から3時間の窓。10:00-12:00 使ったので2時間
        val spans = listOf(at(10) to at(12))
        val usage = compute(query(BudgetReset.WINDOW, windowMinutes = 180), spans, 12, 30)
        assertEquals(120, usage.usedMinutes)
        // 窓は13:00まで。あと30分が休憩
        assertEquals(30, usage.remainingMinutes)
    }

    // ---- 区切り --------------------------------------------------------

    @Test
    fun `区切りの中だけ数える`() {
        // 毎日4時起点。3:00-5:00 使った。いまは6時なので、区切りに入るのは4:00以降
        val spans = listOf(at(3) to at(5))
        val usage = compute(query(BudgetReset.PERIOD, period = ResetPolicy()), spans, 6)
        assertEquals(60, usage.usedMinutes)
    }

    @Test
    fun `区切りをまたいでも手前のぶんだけ落とす`() {
        // 丸ごと落とすと、またいで使い続けたときに直前の分が消える
        val spans = listOf(at(3, 30) to at(4, 30))
        assertEquals(30, compute(query(BudgetReset.PERIOD), spans, 5).usedMinutes)
    }

    @Test
    fun `残りは次の区切りまで`() {
        // 毎日4時起点、いま6時 → 次は翌4時 = 22時間後
        assertEquals(22 * 60, compute(query(BudgetReset.PERIOD), emptyList(), 6).remainingMinutes)
    }

    @Test
    fun `半日ごとにも切れる`() {
        val half = ResetPolicy(periodMinutes = 12 * 60, anchorMinuteOfDay = 4 * 60)
        // 4:00-16:00 が区切り。15:00 時点で残り1時間
        assertEquals(60, compute(query(BudgetReset.PERIOD, period = half), emptyList(), 15).remainingMinutes)
    }

    // ---- 条件としての振る舞い ------------------------------------------

    private fun ctx(used: Int, remaining: Int = 60) = EvalContext(
        now = LocalDateTime.of(2026, 9, 20, 12, 0),
        packageName = "com.google.android.youtube",
        usage = UsageSnapshot.EMPTY,
        budgetUsageOf = {
            com.dopachiru.core.engine.WindowUsage(used * 60L, remaining * 60L)
        },
    )

    private fun budgetParams(minutes: Int) = Params.of(
        UsageBudgetCondition.KEY_BUDGET_MINUTES to minutes,
        UsageBudgetCondition.KEY_RESET to BudgetReset.WINDOW.name,
    )

    @Test
    fun `使い切ったら成立する`() {
        assertTrue(UsageBudgetCondition.evaluate(budgetParams(120), ctx(used = 120)))
        assertFalse(UsageBudgetCondition.evaluate(budgetParams(120), ctx(used = 119)))
    }

    @Test
    fun `数えられない端末では成立しない`() {
        // 渡されなければ 0。閉め出しが空振りするだけで済む
        val blind = EvalContext(
            now = LocalDateTime.of(2026, 9, 20, 12, 0),
            packageName = "x",
            usage = UsageSnapshot.EMPTY,
        )
        assertFalse(UsageBudgetCondition.evaluate(budgetParams(1), blind))
    }

    @Test
    fun `読める要約を出す`() {
        val p = Params.of(
            UsageBudgetCondition.KEY_BUDGET_MINUTES to 120,
            UsageBudgetCondition.KEY_RESET to BudgetReset.WINDOW.name,
            UsageBudgetCondition.KEY_WINDOW_MINUTES to 180,
        )
        assertEquals("2時間使ったら(3時間の窓)", UsageBudgetCondition.summarize(p))
    }

    // ---- 古い3つからの移行 ---------------------------------------------

    private fun ruleWith(condition: ConditionNode) = Rule(
        id = 1,
        uid = "r",
        name = "YouTube",
        target = Target(packages = setOf("com.google.android.youtube")),
        condition = condition,
        actionId = BlockAction.id,
        actionParams = Params.EMPTY,
    )

    private fun migratedLeaf(condition: ConditionNode): ConditionNode.Leaf =
        RuleMigrations.upgrade(ruleWith(condition)).condition as ConditionNode.Leaf

    @Test
    fun `休憩をはさむまでの使用時間は離れたらに移る`() {
        val old = ConditionNode.Leaf(
            UsageSinceBreakCondition.id,
            Params.of(
                UsageSinceBreakCondition.KEY_MINUTES to 20,
                UsageSinceBreakCondition.KEY_BREAK_MINUTES to 10,
            ),
        )
        val leaf = migratedLeaf(old)
        assertEquals(UsageBudgetCondition.id, leaf.typeId)
        assertEquals(20, leaf.params.int(UsageBudgetCondition.KEY_BUDGET_MINUTES, 0))
        assertEquals(BudgetReset.AWAY.name, leaf.params.string(UsageBudgetCondition.KEY_RESET, ""))
        assertEquals(10, leaf.params.int(UsageBudgetCondition.KEY_AWAY_MINUTES, 0))
        assertEquals(CountBy.GROUP.name, leaf.params.string(UsageBudgetCondition.KEY_COUNT_BY, ""))
    }

    @Test
    fun `使い始めてからの持ち時間は窓に移る`() {
        val old = ConditionNode.Leaf(
            WindowBudgetCondition.id,
            Params.of(
                WindowBudgetCondition.KEY_WINDOW_MINUTES to 180,
                WindowBudgetCondition.KEY_BUDGET_MINUTES to 120,
            ),
        )
        val leaf = migratedLeaf(old)
        assertEquals(120, leaf.params.int(UsageBudgetCondition.KEY_BUDGET_MINUTES, 0))
        assertEquals(BudgetReset.WINDOW.name, leaf.params.string(UsageBudgetCondition.KEY_RESET, ""))
        assertEquals(180, leaf.params.int(UsageBudgetCondition.KEY_WINDOW_MINUTES, 0))
    }

    @Test
    fun `合計使用時間はアプリごとのまま移る`() {
        // ここを GROUP にすると、既存のルールが黙って強くなる
        val old = ConditionNode.Leaf(
            TotalUsageCondition.id,
            Params.of(
                TotalUsageCondition.KEY_MINUTES to 60,
                TotalUsageCondition.KEY_PERIOD to ResetPolicy(periodMinutes = 12 * 60),
            ),
        )
        val leaf = migratedLeaf(old)
        assertEquals(BudgetReset.PERIOD.name, leaf.params.string(UsageBudgetCondition.KEY_RESET, ""))
        assertEquals(CountBy.APP.name, leaf.params.string(UsageBudgetCondition.KEY_COUNT_BY, ""))
        assertEquals(12 * 60, leaf.params.resetPolicy(UsageBudgetCondition.KEY_PERIOD).periodMinutes)
    }

    @Test
    fun `入れ子の中の古い条件も移る`() {
        val old = ConditionNode.AnyOf(
            listOf(
                ConditionNode.Not(
                    ConditionNode.Leaf(
                        WindowBudgetCondition.id,
                        Params.of(WindowBudgetCondition.KEY_BUDGET_MINUTES to 15),
                    ),
                ),
            ),
        )
        val migrated = RuleMigrations.upgrade(ruleWith(old)).condition as ConditionNode.AnyOf
        val inner = (migrated.children.first() as ConditionNode.Not).child as ConditionNode.Leaf
        assertEquals(UsageBudgetCondition.id, inner.typeId)
    }

    @Test
    fun `関係ない条件は触らない`() {
        val untouched = ruleWith(ConditionNode.AllOf(emptyList()))
        assertEquals(untouched, RuleMigrations.upgrade(untouched))
    }

    @Test
    fun `古い3つはもう選べない`() {
        // 実装は残す(移行していない保存を読むため)が、一覧には出さない
        val selectable = ConditionRegistry.selectable().map { it.id }
        assertFalse(UsageSinceBreakCondition.id in selectable)
        assertFalse(WindowBudgetCondition.id in selectable)
        assertFalse(TotalUsageCondition.id in selectable)
        assertTrue(UsageBudgetCondition.id in selectable)
        // 読むほうは残っている
        assertTrue(ConditionRegistry[UsageSinceBreakCondition.id] != null)
    }
}
