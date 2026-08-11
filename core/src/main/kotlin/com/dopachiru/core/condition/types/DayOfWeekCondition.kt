package com.dopachiru.core.condition.types

import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params
import com.dopachiru.core.time.ALL_DAYS
import com.dopachiru.core.time.describeDays
import java.time.LocalDateTime

/** 指定した曜日であれば成立する。 */
object DayOfWeekCondition : ConditionType {
    const val KEY_DAYS = "days"

    override val id = "day_of_week"
    override val displayName = "曜日"
    override val description = "選んだ曜日のあいだだけ成立する。"

    override val params = listOf(
        ParamSpec.DayOfWeekParam(KEY_DAYS, "対象の曜日", default = ALL_DAYS),
    )

    override fun evaluate(p: Params, ctx: EvalContext): Boolean =
        ctx.now.dayOfWeek.value in p.intSet(KEY_DAYS, ALL_DAYS)

    override fun summarize(p: Params): String = describeDays(p.intSet(KEY_DAYS))

    /** 日付が変わるまでは成否が変わらない。 */
    override fun nextChangeAt(p: Params, ctx: EvalContext): LocalDateTime =
        ctx.now.toLocalDate().plusDays(1).atStartOfDay()
}
