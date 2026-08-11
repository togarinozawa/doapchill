package com.dopachiru.core.condition.types

import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params

/**
 * カレンダーに予定が入っているかどうかで成立する。
 *
 * 予定は端末のカレンダー Provider から読む。Google カレンダーを端末に同期していれば
 * その予定がそのまま見えるので、OAuth も通信も要らない。
 */
object CalendarBusyCondition : ConditionType {
    const val KEY_DURING_EVENT = "duringEvent"
    const val KEY_KEYWORD = "keyword"

    override val id = "calendar_busy"
    override val displayName = "カレンダー予定"
    override val description = "予定の最中(または予定が無いあいだ)に成立する。予定名で絞り込める。"

    override val params = listOf(
        ParamSpec.TextParam(
            KEY_KEYWORD,
            "予定名に含む語",
            default = "",
            help = "空にすると、予定の有無だけを見る。例: #集中",
        ),
        ParamSpec.BoolParam(
            KEY_DURING_EVENT,
            "予定の最中に成立させる",
            default = true,
            help = "オフにすると、その予定が入っていないあいだに成立する",
        ),
    )

    override fun evaluate(p: Params, ctx: EvalContext): Boolean {
        val keyword = p.string(KEY_KEYWORD)
        val matched =
            if (keyword.isBlank()) ctx.calendar.busy else ctx.calendar.inEventMatching(keyword)
        return matched == p.bool(KEY_DURING_EVENT, true)
    }

    override fun summarize(p: Params): String {
        val keyword = p.string(KEY_KEYWORD)
        val target = if (keyword.isBlank()) "予定" else "「$keyword」の予定"
        return if (p.bool(KEY_DURING_EVENT, true)) "${target}中" else "${target}が無いあいだ"
    }
}
