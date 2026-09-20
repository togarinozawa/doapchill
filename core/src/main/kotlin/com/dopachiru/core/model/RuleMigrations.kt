package com.dopachiru.core.model

import com.dopachiru.core.condition.types.TotalUsageCondition
import com.dopachiru.core.condition.types.UsageBudgetCondition
import com.dopachiru.core.condition.types.UsageSinceBreakCondition
import com.dopachiru.core.condition.types.WindowBudgetCondition
import com.dopachiru.core.engine.BudgetReset
import com.dopachiru.core.engine.CountBy
import com.dopachiru.core.param.Params
import com.dopachiru.core.time.ResetPolicy

/**
 * 保存されているルールを、いまの形に読み替える。
 *
 * **読むときに通します。書き戻すのは次に保存したときだけ**なので、
 * 通し忘れても古いルールが壊れることはありません(古い条件の実装は残してある)。
 * それでも読むたびに通すのは、画面と判定で見えかたが食い違わないようにするため。
 *
 * 移行はここに1つだけ置きます ── Android と Windows で別々に書くと、
 * かたや移行済み、かたや古いまま、が必ず起きます。
 */
object RuleMigrations {

    /** ルール1本を、いまの形に。何も変わらなければ同じものを返す。 */
    fun upgrade(rule: Rule): Rule {
        val condition = upgradeCondition(rule.condition)
        val extras = rule.extraClauses.map { it.copy(condition = upgradeCondition(it.condition)) }
        if (condition == rule.condition && extras == rule.extraClauses) return rule
        return rule.copy(condition = condition, extraClauses = extras)
    }

    fun upgradeAll(rules: List<Rule>): List<Rule> = rules.map { upgrade(it) }

    private fun upgradeCondition(node: ConditionNode): ConditionNode = when (node) {
        is ConditionNode.Leaf -> upgradeLeaf(node)
        is ConditionNode.Not -> ConditionNode.Not(upgradeCondition(node.child))
        is ConditionNode.AllOf -> ConditionNode.AllOf(node.children.map { upgradeCondition(it) })
        is ConditionNode.AnyOf -> ConditionNode.AnyOf(node.children.map { upgradeCondition(it) })
    }

    /**
     * 古い3つを「使いすぎたら」に寄せる。
     *
     * **数える単位は元の挙動をそのまま写します。** 揃えるまで、ここは条件ごとに
     * バラバラでした ── 「合計使用時間」だけが前面のアプリ1つを数えていた。
     * 直すのは簡単ですが、黙って直すと**いまのルールの効きかたが変わります**。
     */
    private fun upgradeLeaf(leaf: ConditionNode.Leaf): ConditionNode = when (leaf.typeId) {
        UsageSinceBreakCondition.id -> budget(
            budgetMinutes = leaf.params.int(UsageSinceBreakCondition.KEY_MINUTES, 20),
            countBy = CountBy.GROUP,
            reset = BudgetReset.AWAY,
            awayMinutes = leaf.params.int(UsageSinceBreakCondition.KEY_BREAK_MINUTES, 10),
        )

        WindowBudgetCondition.id -> budget(
            budgetMinutes = leaf.params.int(WindowBudgetCondition.KEY_BUDGET_MINUTES, 15),
            countBy = CountBy.GROUP,
            reset = BudgetReset.WINDOW,
            windowMinutes = leaf.params.int(WindowBudgetCondition.KEY_WINDOW_MINUTES, 60),
        )

        TotalUsageCondition.id -> budget(
            budgetMinutes = leaf.params.int(TotalUsageCondition.KEY_MINUTES, 60),
            // 「合計使用時間」は前面のアプリ1つを数えていた。ここを GROUP にすると
            // 既存のルールが黙って強くなる
            countBy = CountBy.APP,
            reset = BudgetReset.PERIOD,
            period = leaf.params.resetPolicy(TotalUsageCondition.KEY_PERIOD),
        )

        else -> leaf
    }

    private fun budget(
        budgetMinutes: Int,
        countBy: CountBy,
        reset: BudgetReset,
        awayMinutes: Int = 30,
        windowMinutes: Int = 180,
        period: ResetPolicy = ResetPolicy(),
    ): ConditionNode.Leaf = ConditionNode.Leaf(
        UsageBudgetCondition.id,
        Params.of(
            UsageBudgetCondition.KEY_BUDGET_MINUTES to budgetMinutes,
            UsageBudgetCondition.KEY_COUNT_BY to countBy.name,
            UsageBudgetCondition.KEY_RESET to reset.name,
            UsageBudgetCondition.KEY_AWAY_MINUTES to awayMinutes,
            UsageBudgetCondition.KEY_WINDOW_MINUTES to windowMinutes,
            UsageBudgetCondition.KEY_PERIOD to period,
        ),
    )
}
