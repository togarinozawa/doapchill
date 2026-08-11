package com.dopachiru.core.condition.types

import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params
import java.time.LocalDateTime

/** そのアプリを連続して一定時間以上使っていれば成立する。 */
object ContinuousUsageCondition : ConditionType {
    const val KEY_MINUTES = "minutes"

    override val id = "continuous_usage"
    override val displayName = "連続使用時間"
    override val description = "アプリを開きっぱなしにしている時間が指定を超えたら成立する。いったん離れるとリセットされる。"

    override val params = listOf(
        ParamSpec.DurationParam(KEY_MINUTES, "連続使用が", default = 30, min = 1, max = 8 * 60),
    )

    override fun evaluate(p: Params, ctx: EvalContext): Boolean =
        ctx.usage.currentSessionMinutes >= p.int(KEY_MINUTES, 30)

    override fun summarize(p: Params): String = "連続${p.int(KEY_MINUTES, 30)}分以上"

    /**
     * 閾値に届くまでは成否が変わらない。届いたあとは、アプリを離れるまで変わらない
     * (離脱はイベントで拾えるので、時間で見に来る必要がない)。
     */
    override fun nextChangeAt(p: Params, ctx: EvalContext): LocalDateTime {
        val remaining = p.int(KEY_MINUTES, 30) - ctx.usage.currentSessionMinutes
        return if (remaining > 0) ctx.now.plusMinutes(remaining.toLong()) else ctx.now.plusDays(1)
    }
}
