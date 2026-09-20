package com.dopachiru.core.model

import com.dopachiru.core.condition.types.LinkedRuleCondition
import com.dopachiru.core.param.Params

/**
 * 「どのルールが、ほかの端末から見られているか」。
 *
 * 見られていないルールの状態を配っても誰も読みません。毎回ぜんぶ配ると、
 * 何も起きていない日でも同期のたびに行が動きます。
 */
object RuleLinks {

    /**
     * 連動の条件から指されているルールの uid。
     *
     * 指す先が空の条件は**そのルール自身**を指すので、そのルールの uid を入れます
     * ── いちばん多い使い方(同じルールを両方の端末で効かせる)がこれ。
     */
    fun watchedUids(rules: List<Rule>): Set<String> = buildSet {
        for (rule in rules) {
            if (!rule.enabled) continue
            rule.clauses.forEach { collect(it.condition, rule.uid, this) }
        }
    }

    /** そのルールが連動の条件を持っているか。画面で印を付けるため。 */
    fun isLinked(rule: Rule): Boolean =
        rule.clauses.any { contains(it.condition) }

    /**
     * 「ほかの端末で効いているあいだ、ここでも効かせる」を足す。
     *
     * 元の条件との **OR** で足します。AND にすると、向こうで効いているときしか
     * こちらが効かなくなり、1台で使っているあいだ何も起きません。
     *
     * 指す先は空 = このルール自身。同じルールが端末をまたいで同じ uid を持つので、
     * これで「どちらで使い切っても両方閉まる」になります。
     */
    fun withLink(condition: ConditionNode): ConditionNode {
        if (contains(condition)) return condition
        val leaf = ConditionNode.Leaf(LinkedRuleCondition.id, Params.EMPTY)
        // すでに OR ならその中に足す。入れ子を無駄に深くしない
        return if (condition is ConditionNode.AnyOf) {
            condition.copy(children = condition.children + leaf)
        } else {
            ConditionNode.AnyOf(listOf(condition, leaf))
        }
    }

    /**
     * 連動を外す。
     *
     * 外したあと子が1つしか残らない OR は、**かぶせた殻ごと剥がします** ──
     * 剥がさないと、足して外すたびに `どれか(1つ)` の殻が積もります。
     */
    fun withoutLink(condition: ConditionNode): ConditionNode = when (condition) {
        is ConditionNode.Leaf ->
            if (condition.typeId == LinkedRuleCondition.id) ConditionNode.AllOf() else condition

        is ConditionNode.Not -> ConditionNode.Not(withoutLink(condition.child))

        is ConditionNode.AllOf -> ConditionNode.AllOf(strip(condition.children))

        is ConditionNode.AnyOf -> {
            val left = strip(condition.children)
            if (left.size == 1) left.first() else ConditionNode.AnyOf(left)
        }
    }

    private fun strip(children: List<ConditionNode>): List<ConditionNode> = children
        .filterNot { it is ConditionNode.Leaf && it.typeId == LinkedRuleCondition.id }
        .map { withoutLink(it) }

    /** この木に連動の条件が入っているか。 */
    fun contains(condition: ConditionNode): Boolean = when (condition) {
        is ConditionNode.Leaf -> condition.typeId == LinkedRuleCondition.id
        is ConditionNode.Not -> contains(condition.child)
        is ConditionNode.AllOf -> condition.children.any { contains(it) }
        is ConditionNode.AnyOf -> condition.children.any { contains(it) }
    }

    private fun collect(node: ConditionNode, selfUid: String, into: MutableSet<String>) {
        when (node) {
            is ConditionNode.Leaf -> {
                if (node.typeId != LinkedRuleCondition.id) return
                val uid = node.params.string(LinkedRuleCondition.KEY_RULE_UID, "").ifBlank { selfUid }
                if (uid.isNotBlank()) into += uid
            }

            is ConditionNode.AllOf -> node.children.forEach { collect(it, selfUid, into) }
            is ConditionNode.AnyOf -> node.children.forEach { collect(it, selfUid, into) }
            is ConditionNode.Not -> collect(node.child, selfUid, into)
        }
    }
}
