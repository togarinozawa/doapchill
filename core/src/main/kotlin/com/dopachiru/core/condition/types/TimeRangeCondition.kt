package com.dopachiru.core.condition.types

import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params
import com.dopachiru.core.time.formatMinuteOfDay
import java.time.LocalDateTime

/** 一日のうち指定した時刻の範囲内にいれば成立する。 */
object TimeRangeCondition : ConditionType {
    const val KEY_START = "start"
    const val KEY_END = "end"

    override val id = "time_range"
    override val displayName = "時間帯"
    override val description = "指定した時刻の範囲内で成立する。終了が開始より前なら日をまたぐ範囲として扱う(例 22:00〜06:00)。"

    override val params = listOf(
        ParamSpec.TimeOfDayParam(KEY_START, "開始", default = 22 * 60),
        ParamSpec.TimeOfDayParam(KEY_END, "終了", default = 6 * 60),
    )

    override fun evaluate(p: Params, ctx: EvalContext): Boolean {
        val start = p.int(KEY_START)
        val end = p.int(KEY_END)
        val nowMinute = ctx.now.hour * 60 + ctx.now.minute
        return if (start <= end) {
            nowMinute >= start && nowMinute < end
        } else {
            // 日跨ぎ: 22:00〜06:00 なら 22:00以降 または 06:00未満
            nowMinute >= start || nowMinute < end
        }
    }

    override fun summarize(p: Params): String =
        "${formatMinuteOfDay(p.int(KEY_START))}〜${formatMinuteOfDay(p.int(KEY_END))}"

    /** 次に境界をまたぐ時刻。それまでは成否が変わらない。 */
    override fun nextChangeAt(p: Params, ctx: EvalContext): LocalDateTime {
        val nowMinute = ctx.now.hour * 60 + ctx.now.minute
        val midnight = ctx.now.toLocalDate().atStartOfDay()
        val boundaries = listOf(p.int(KEY_START), p.int(KEY_END))
            .map { if (it > nowMinute) midnight.plusMinutes(it.toLong()) else midnight.plusDays(1).plusMinutes(it.toLong()) }
        return boundaries.min()
    }
}
