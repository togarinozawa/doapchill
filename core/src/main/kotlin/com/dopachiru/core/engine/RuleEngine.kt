package com.dopachiru.core.engine

import com.dopachiru.core.action.ActionRegistry
import com.dopachiru.core.action.ActionType
import com.dopachiru.core.condition.ConditionRegistry
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Rule
import com.dopachiru.core.param.Params
import java.time.LocalDateTime

/** 評価の結果、そのアプリをどう扱うか。 */
sealed interface Decision {
    /** 何も起こさない。 */
    data object Allow : Decision

    /** [rule] が成立したので [action] を実行する。 */
    data class Act(
        val rule: Rule,
        val action: ActionType,
        val params: Params,
    ) : Decision
}

/**
 * ルールを評価して、いま何をすべきかを決める。
 *
 * 条件・アクションの実体は一切知らず、レジストリ越しに引くだけ。
 * 種類が増えてもこのクラスは変わらない。
 */
class RuleEngine {

    /**
     * 成立したルールのうち、アクションの [ActionType.severity] が最大のものを採る。
     * 同じ強さが並んだ場合は、先に見つかったものを優先する。
     *
     * @param tagsOf パッケージ名からそのアプリに付いたタグを引く。
     */
    fun decide(
        rules: List<Rule>,
        ctx: EvalContext,
        tagsOf: (String) -> Set<String>,
    ): Decision {
        var best: Decision.Act? = null
        val tags by lazy { tagsOf(ctx.packageName) }

        for (rule in rules) {
            if (!rule.enabled) continue
            if (!rule.target.matches(ctx.packageName, tags)) continue
            if (!evaluate(rule.condition, ctx)) continue

            val action = ActionRegistry[rule.actionId] ?: continue
            if (best == null || action.severity > best.action.severity) {
                best = Decision.Act(rule, action, rule.actionParams)
            }
        }
        return best ?: Decision.Allow
    }

    /** 条件の木を評価する。未登録の条件IDは「成立しない」として扱う。 */
    fun evaluate(node: ConditionNode, ctx: EvalContext): Boolean = when (node) {
        is ConditionNode.Leaf ->
            ConditionRegistry[node.typeId]?.evaluate(node.params, ctx) ?: false

        is ConditionNode.AllOf -> node.children.all { evaluate(it, ctx) }
        is ConditionNode.AnyOf -> node.children.any { evaluate(it, ctx) }
        is ConditionNode.Not -> !evaluate(node.child, ctx)
    }

    /**
     * このアプリについて、次に判定結果が変わりうる最も早い時刻。
     *
     * それまでは見に来る必要がない、という意味なので、電池を使わずに眠れる。
     * 1つでも「分からない」と答えた条件があれば null を返し、
     * 呼び出し側は安全側に倒して短い間隔で見に来る。
     */
    fun nextChangeAt(
        rules: List<Rule>,
        ctx: EvalContext,
        tagsOf: (String) -> Set<String>,
    ): LocalDateTime? {
        val tags by lazy { tagsOf(ctx.packageName) }
        var earliest: LocalDateTime? = null

        for (rule in rules) {
            if (!rule.enabled) continue
            if (!rule.target.matches(ctx.packageName, tags)) continue
            val at = nextChangeAt(rule.condition, ctx) ?: return null
            if (earliest == null || at.isBefore(earliest)) earliest = at
        }
        return earliest
    }

    /** 条件の木のうち、いちばん early に変わりうる時刻。1つでも不明なら null。 */
    fun nextChangeAt(node: ConditionNode, ctx: EvalContext): LocalDateTime? = when (node) {
        is ConditionNode.Leaf ->
            ConditionRegistry[node.typeId]?.nextChangeAt(node.params, ctx)

        is ConditionNode.Not -> nextChangeAt(node.child, ctx)

        is ConditionNode.AllOf -> earliestOf(node.children, ctx)
        is ConditionNode.AnyOf -> earliestOf(node.children, ctx)
    }

    private fun earliestOf(children: List<ConditionNode>, ctx: EvalContext): LocalDateTime? {
        // 子が空 = 無条件。時間では変わらない
        if (children.isEmpty()) return ctx.now.plusDays(1)
        var earliest: LocalDateTime? = null
        for (child in children) {
            val at = nextChangeAt(child, ctx) ?: return null
            if (earliest == null || at.isBefore(earliest)) earliest = at
        }
        return earliest
    }
}
