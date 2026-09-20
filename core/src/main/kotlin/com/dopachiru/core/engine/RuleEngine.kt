package com.dopachiru.core.engine

import com.dopachiru.core.action.ActionRegistry
import com.dopachiru.core.action.ActionType
import com.dopachiru.core.condition.ConditionRegistry
import com.dopachiru.core.model.ActionSpec
import com.dopachiru.core.model.Clauses
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Lockout
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
        /**
         * 成立した組の番号。ルールは「条件 → こうする」を何組も持てる。
         *
         * 何が効いたのかを画面に出すのと、数える財布を組ごとに分けるのに要る。
         */
        val clauseId: Int = Clauses.FIRST_ID,

        /**
         * 一緒に出しておく覚え書き(経過表示・目的のチップ)。
         *
         * **成立している組ぜんぶから集めます。** 1組目が「閉じる」で2組目が
         * 「経過表示」なら、両方が効く ── 覆いが引っ込んだあとに経過が残る。
         */
        val overlays: List<ActionSpec> = emptyList(),
    ) : Decision

    /**
     * ルールを破った罰で閉まっている。
     *
     * ルールの評価より先に決まる。条件を満たしていようがいまいが関係なく、
     * 時間が来るまで開かない ── そうでなければ罰にならない。
     */
    data class Locked(val lockout: Lockout) : Decision
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
    /**
     * 罰 → 解禁券 → ルール の順で見る。この順番そのものが仕様なので、
     * 端末ごとに書かずにここへ置いてある。
     *
     * **罰がいちばん強い。** 閉まっているあいだは、どのルールが成立していようと
     * 関係ない。逆にすると「罰の最中に条件が外れたので開いた」が起きて罰にならない。
     *
     * **解禁券では罰は解けない。** ポイントで買えるのはルールの免除であって、
     * 科された罰の時間ではない。ここを通すと、罰が「もっとポイントを払えば消える」
     * ものに変わり、封鎖画面の「押し切る手段はありません」が嘘になる。
     *
     * @param passUntilSec 解禁券が効いている期限。0 なら効いていない。
     */
    fun decide(
        rules: List<Rule>,
        lockouts: List<Lockout>,
        ctx: EvalContext,
        nowSec: Long,
        passUntilSec: Long,
        tagsOf: (String) -> Set<String>,
    ): Decision {
        if (lockouts.isNotEmpty()) {
            val locked = com.dopachiru.core.model.Lockouts.activeFor(
                all = lockouts,
                packageName = ctx.packageName,
                tagsOfApp = tagsOf(ctx.packageName),
                url = ctx.url,
                nowSec = nowSec,
            )
            if (locked != null) return Decision.Locked(locked)
        }
        if (nowSec < passUntilSec) return Decision.Allow
        return decide(rules, ctx, tagsOf)
    }

    /**
     * 成立した組のうち、いちばん強い措置を1つ採る。
     *
     * ## 組をまたいでも「いちばん強い1つ」
     *
     * ルールは「条件 → こうする」を何組も持てる(「22時以降は待たせる」
     * 「前回から3時間あいていないなら閉じる」)。両方が成立したら、
     * **強いほうだけ**を実行します ── 待たせてから閉じる、では
     * 待った時間が無駄になるだけなので。
     *
     * 重ねられる措置(経過表示など)を同時に出すのは別の話で、
     * 画面を作り直すときに入れます([Clause.overlays])。
     */
    fun decide(
        rules: List<Rule>,
        ctx: EvalContext,
        tagsOf: (String) -> Set<String>,
    ): Decision {
        var best: Decision.Act? = null
        val notes = LinkedHashMap<String, ActionSpec>()
        val tags by lazy { tagsOf(ctx.packageName) }

        for (rule in rules) {
            if (!rule.enabled) continue
            if (!rule.target.matches(ctx.packageName, tags, ctx.url)) continue

            for (clause in rule.clauses) {
                // どのルールのどの組を見ているかを条件に伝える。確率の抽選や
                // 慣れの判定が独立していないと、隣の結果を巻き込む
                if (!evaluate(clause.condition, ctx.forClause(rule, clause))) continue

                for ((index, spec) in clause.actions.withIndex()) {
                    val action = ActionRegistry[spec.actionId] ?: continue
                    if (action.stackable) {
                        // 覚え書きは重なる。同じものを2組が出しても1枚にまとめる
                        notes.putIfAbsent(spec.actionId, spec)
                        continue
                    }
                    // 覆うものは先頭だけ。2つ目以降に置いても、裏に隠れて見えない
                    if (index != 0) continue
                    if (best == null || action.severity > best.action.severity) {
                        best = Decision.Act(rule, action, spec.params, clause.id)
                    }
                }
            }
        }

        val stacked = notes.values.toList()
        return when {
            best != null -> best.copy(overlays = stacked)
            // 覚え書きしか無いときは、いちばん軽いものを主に立てる。
            // 主が無いと何も出せない
            stacked.isNotEmpty() -> {
                val head = stacked.first()
                val action = ActionRegistry[head.actionId] ?: return Decision.Allow
                Decision.Act(
                    rule = rules.first { rule ->
                        rule.clauses.any { it.actions.any { a -> a.actionId == head.actionId } }
                    },
                    action = action,
                    params = head.params,
                    overlays = stacked.drop(1),
                )
            }
            else -> Decision.Allow
        }
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
            if (!rule.target.matches(ctx.packageName, tags, ctx.url)) continue
            for (clause in rule.clauses) {
                if (clause.mainAction == null) continue
                val at = nextChangeAt(clause.condition, ctx.forClause(rule, clause)) ?: return null
                if (earliest == null || at.isBefore(earliest)) earliest = at
            }
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
