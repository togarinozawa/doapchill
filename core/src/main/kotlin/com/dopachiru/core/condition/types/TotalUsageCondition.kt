package com.dopachiru.core.condition.types

import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params
import com.dopachiru.core.time.ResetPolicy
import java.time.LocalDateTime

/** 集計期間のうちの合計使用時間が閾値を超えたら成立する。 */
object TotalUsageCondition : ConditionType {
    const val KEY_MINUTES = "minutes"
    const val KEY_PERIOD = "period"

    override val id = "total_usage"
    override val displayName = "合計使用時間"
    override val description = "期間内の合計使用時間が指定を超えたら成立する。リセット時刻と周期を変えられる。"

    override val params = listOf(
        ParamSpec.DurationParam(KEY_MINUTES, "合計使用が", default = 60, min = 1, max = 24 * 60),
        ParamSpec.ResetPolicyParam(KEY_PERIOD, "集計期間", default = ResetPolicy()),
    )

    override fun evaluate(p: Params, ctx: EvalContext): Boolean {
        val policy = p.resetPolicy(KEY_PERIOD)
        return ctx.usage.usageMinutesIn(policy) >= p.int(KEY_MINUTES, 60)
    }

    override fun summarize(p: Params): String =
        "${p.resetPolicy(KEY_PERIOD).describe()}で合計${p.int(KEY_MINUTES, 60)}分以上"

    /**
     * 使い続けたと仮定して閾値に届く時刻か、集計期間が切り替わる時刻の早いほう。
     * 実際には前面にいないぶん遅れて届くので、無駄に起きることはあっても遅れることはない。
     */
    override fun nextChangeAt(p: Params, ctx: EvalContext): LocalDateTime {
        val policy = p.resetPolicy(KEY_PERIOD)
        val periodEnd = policy.periodEnd(ctx.now)
        val remaining = p.int(KEY_MINUTES, 60) - ctx.usage.usageMinutesIn(policy)
        if (remaining <= 0) return periodEnd
        return minOf(ctx.now.plusMinutes(remaining.toLong()), periodEnd)
    }
}
