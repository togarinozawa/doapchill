package com.dopachiru.core.condition.types

import com.dopachiru.core.condition.ConditionGroup
import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params
import java.time.LocalDateTime

/**
 * 使い始めてから [KEY_WINDOW_MINUTES] 分のうち、[KEY_BUDGET_MINUTES] 分を使い切ったら成立する。
 *
 * ## [UsageSinceBreakCondition] との違い ── 出し抜けるかどうか
 *
 * 「20分使ったら10分閉め出す」は、慣れると避けられる。19分で自分から閉じ、
 * 休憩ぶんだけ離れてから開き直せば、閉め出しに一度も当たらない。**早めに切り上げる
 * 練習ではなく、タイマーを読む練習**になってしまう。小賢しくやるほど得をする仕組みは、
 * それ自体が新しい癖になる。
 *
 * こちらは最初に触った時刻に窓を張り、**閉じても窓は消えない**。
 * 窓の中で持ち時間を使い切ったら、窓が明けるまで塞がったまま。早く閉じても
 * 残りが返ってくるわけではないので、出し抜く手が無い。
 *
 * ## 塞ぎ続けるために
 *
 * 持ち時間を使い切ったあとも、窓が明けるまで**成立したまま**になる。だから
 * 「使えなくする(条件が続くあいだ)」と組めば、残り時間はずっと閉まる。
 * 「閉じるだけ」では開き直せてしまうが、こちらは開き直しても条件がまだ立っている。
 *
 * ## 対象ぜんぶをまとめて数える
 *
 * ルールの対象がタグで括ってあれば**グループ合計**。X と YouTube と TikTok を
 * 渡り歩いても、窓も持ち時間も1つしかない。
 */
object WindowBudgetCondition : ConditionType {
    const val KEY_WINDOW_MINUTES = "windowMinutes"
    const val KEY_BUDGET_MINUTES = "budgetMinutes"

    override val id = "window_budget"
    override val displayName = "使い始めてからの持ち時間"
    override val description =
        "最初に触った時刻から数えて一定時間の窓を張り、その中の持ち時間を使い切ったら成立する。" +
            "閉じても窓は消えないので、ギリギリで閉じて数え直させる手が効かない。"

    override val group = ConditionGroup.USAGE
    /**
     * 新しくは選べません。[UsageBudgetCondition] にまとまりました。
     *
     * 実装を残してあるのは、**まだ移行していない保存を読むため**です。
     * 消すと、読み込みの途中で落ちた端末のルールが「知らない条件」になって
     * 成立しなくなります(= 黙って縛りが外れる)。
     */
    override val available = false
    override val example = "使い始めて1時間のうち15分を使い切ったら。閉じても窓は消えない"

    override val params = listOf(
        ParamSpec.DurationParam(
            KEY_WINDOW_MINUTES,
            "使い始めてから",
            default = 60,
            min = 5,
            max = 24 * 60,
            help = "この幅で窓を張る。窓が明けるまで、閉じても数え直しにならない",
        ),
        ParamSpec.DurationParam(
            KEY_BUDGET_MINUTES,
            "そのうち使っていいのは",
            default = 15,
            min = 1,
            max = 12 * 60,
            help = "使い切ったら、窓が明けるまで成立したまま。窓より短くすること",
        ),
    )

    override fun evaluate(p: Params, ctx: EvalContext): Boolean {
        val usage = ctx.windowUsageOf(ctx.currentRuleId, p.int(KEY_WINDOW_MINUTES, 60))
        return usage.open && usage.usedMinutes >= p.int(KEY_BUDGET_MINUTES, 15)
    }

    override fun summarize(p: Params): String =
        "使い始めて${p.int(KEY_WINDOW_MINUTES, 60)}分のうち${p.int(KEY_BUDGET_MINUTES, 15)}分を使い切った"

    /**
     * 窓が張られていなければ、持ち時間を使い切るまで最短でも持ち時間ぶんかかる。
     * 張られていれば、使い切る時刻か窓が明ける時刻の早いほう。
     */
    override fun nextChangeAt(p: Params, ctx: EvalContext): LocalDateTime {
        val budget = p.int(KEY_BUDGET_MINUTES, 15)
        val usage = ctx.windowUsageOf(ctx.currentRuleId, p.int(KEY_WINDOW_MINUTES, 60))
        if (!usage.open) return ctx.now.plusMinutes(budget.toLong())

        val remaining = budget - usage.usedMinutes
        // 使い切ったあとは、窓が明けるまで答えが変わらない
        if (remaining <= 0) return ctx.now.plusMinutes(usage.remainingMinutes.toLong())
        return ctx.now.plusMinutes(minOf(remaining, usage.remainingMinutes).toLong())
    }
}
