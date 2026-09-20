package com.dopachiru.core.condition.types

import com.dopachiru.core.condition.ConditionGroup
import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.engine.BudgetQuery
import com.dopachiru.core.engine.BudgetReset
import com.dopachiru.core.engine.CountBy
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params
import com.dopachiru.core.time.ResetPolicy
import java.time.LocalDateTime

/**
 * 使いすぎたら成立する。**使いすぎを止めるルールの本体。**
 *
 * ## 決めるのは2つだけ
 *
 *  1. **何分使ったら**
 *  2. **どこで数え直すか**
 *
 * 前にあった3つ(休憩をはさむまでの使用時間・使い始めてからの持ち時間・
 * 合計使用時間)は、2つ目が違うだけの同じものでした。並べて置くと選べないので、
 * 1つにまとめて中で選ばせます。古いルールは読み込むときに自動で移ります。
 *
 * ## 数え直しかたで性格が変わる
 *
 * - [BudgetReset.AWAY] 連続で離れたら ── 素直だが、**閾値の手前で自分から
 *   閉じれば当たらない**。慣れるとタイマーを読む練習になる
 * - [BudgetReset.WINDOW] 使い始めてからの窓 ── 閉じても窓は消えないので、
 *   出し抜く手が無い。「2時間使ったら1時間休憩」はこれ(窓3時間・持ち時間2時間)
 * - [BudgetReset.PERIOD] 決まった区切り ── 「1日2時間まで」
 *
 * ## 休憩の長さはここで決まります
 *
 * 使い切ったあと、次に数え直されるまでが休憩です。**別に「何分閉める」を
 * 書く必要はありません** ── 書けるようにしてあった頃は、2か所の数字がずれて
 * 「明けた直後にまた閉まる」が起きていました。
 *
 * ## 数える単位
 *
 * [CountBy.GROUP] なら対象ぜんぶで1つの財布、[CountBy.APP] ならアプリごと。
 * 揃えるまでここは条件ごとにバラバラで、「合計使用時間」だけが前面のアプリ1つを
 * 数えていました。
 */
object UsageBudgetCondition : ConditionType {
    const val KEY_BUDGET_MINUTES = "budgetMinutes"
    const val KEY_COUNT_BY = "countBy"
    const val KEY_RESET = "reset"
    const val KEY_AWAY_MINUTES = "awayMinutes"
    const val KEY_WINDOW_MINUTES = "windowMinutes"
    const val KEY_PERIOD = "period"

    override val id = "usage_budget"
    override val displayName = "使いすぎたら"
    override val description =
        "決めた分を使い切ったら成立する。どこで数え直すか(離れたら・窓・区切り)で性格が変わる。" +
            "使い切ったあと数え直されるまでが休憩になるので、閉める長さを別に書く必要はない。"

    override val group = ConditionGroup.USAGE
    override val example = "2時間使ったら。窓3時間なら、残りの1時間が休憩になる"

    override val params = listOf(
        ParamSpec.DurationParam(
            KEY_BUDGET_MINUTES,
            "使っていいのは",
            default = 120,
            min = 1,
            max = 24 * 60,
        ),
        ParamSpec.EnumParam(
            KEY_COUNT_BY,
            "数える単位",
            options = CountBy.entries.map { ParamSpec.EnumParam.Option(it.name, it.label) },
            default = CountBy.GROUP.name,
            help = "対象ぜんぶで1つの財布にするか、アプリごとに分けるか",
        ),
        ParamSpec.EnumParam(
            KEY_RESET,
            "どこで数え直すか",
            options = BudgetReset.entries.map { ParamSpec.EnumParam.Option(it.name, it.label) },
            default = BudgetReset.WINDOW.name,
            help = "使い切ったあと、ここで数え直されるまでが休憩になる",
        ),
        ParamSpec.DurationParam(
            KEY_AWAY_MINUTES,
            "(連続で離れたら)離れる時間",
            default = 30,
            min = 1,
            max = 12 * 60,
            help = "対象をどれも触らない時間がこれだけ続いたら、そこから数え直す",
        ),
        ParamSpec.DurationParam(
            KEY_WINDOW_MINUTES,
            "(窓)窓の幅",
            default = 180,
            min = 5,
            max = 24 * 60,
            help = "使っていい分より長くすること。差が休憩になる(窓3時間・持ち2時間 なら1時間休み)",
        ),
        ParamSpec.ResetPolicyParam(
            KEY_PERIOD,
            "(区切り)区切りかた",
            default = ResetPolicy(),
        ),
    )

    fun queryOf(p: Params, ctx: EvalContext): BudgetQuery = BudgetQuery(
        ruleId = ctx.currentRuleId,
        clauseId = ctx.currentClauseId,
        packageName = ctx.packageName,
        countBy = countBy(p),
        reset = reset(p),
        awayMinutes = p.int(KEY_AWAY_MINUTES, 30),
        windowMinutes = p.int(KEY_WINDOW_MINUTES, 180),
        period = p.resetPolicy(KEY_PERIOD),
    )

    private fun countBy(p: Params): CountBy =
        runCatching { CountBy.valueOf(p.string(KEY_COUNT_BY, CountBy.GROUP.name)) }
            .getOrDefault(CountBy.GROUP)

    private fun reset(p: Params): BudgetReset =
        runCatching { BudgetReset.valueOf(p.string(KEY_RESET, BudgetReset.WINDOW.name)) }
            .getOrDefault(BudgetReset.WINDOW)

    override fun evaluate(p: Params, ctx: EvalContext): Boolean {
        val budget = p.int(KEY_BUDGET_MINUTES, 120)
        if (budget <= 0) return false
        return ctx.budgetUsageOf(queryOf(p, ctx)).usedMinutes >= budget
    }

    override fun summarize(p: Params): String {
        val budget = span(p.int(KEY_BUDGET_MINUTES, 120))
        val unit = if (countBy(p) == CountBy.APP) "アプリごとに" else ""
        val tail = when (reset(p)) {
            BudgetReset.AWAY -> "(" + span(p.int(KEY_AWAY_MINUTES, 30)) + "離れたら数え直し)"
            BudgetReset.WINDOW -> "(" + span(p.int(KEY_WINDOW_MINUTES, 180)) + "の窓)"
            BudgetReset.PERIOD -> "(" + p.resetPolicy(KEY_PERIOD).describe() + ")"
        }
        return unit + budget + "使ったら" + tail
    }

    private fun span(minutes: Int): String = when {
        minutes >= 60 && minutes % 60 == 0 -> (minutes / 60).toString() + "時間"
        minutes >= 60 -> (minutes / 60).toString() + "時間" + (minutes % 60) + "分"
        else -> minutes.toString() + "分"
    }

    /**
     * 使い切るまでの最短か、数え直しの時刻の早いほう。
     *
     * 使い切ったあとは、数え直されるまで答えが変わらない。
     */
    override fun nextChangeAt(p: Params, ctx: EvalContext): LocalDateTime {
        val budget = p.int(KEY_BUDGET_MINUTES, 120)
        val usage = ctx.budgetUsageOf(queryOf(p, ctx))
        val remaining = budget - usage.usedMinutes
        if (remaining <= 0) return ctx.now.plusMinutes(usage.remainingMinutes.toLong().coerceAtLeast(1))
        if (!usage.open) return ctx.now.plusMinutes(remaining.toLong())
        return ctx.now.plusMinutes(minOf(remaining, usage.remainingMinutes.coerceAtLeast(1)).toLong())
    }
}
