package com.dopachiru.core.action.types

import com.dopachiru.core.action.ActionType
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params

/**
 * 警告表示。半透明のオーバーレイを一定時間かぶせるが、下のアプリは操作できる。
 *
 * 下のアプリを操作させたまま重ねるので、SYSTEM_ALERT_WINDOW ではなく
 * TYPE_ACCESSIBILITY_OVERLAY で出す必要がある(risk-assessment.md A-1 を参照)。
 */
object WarnAction : ActionType {
    const val KEY_MESSAGE = "message"
    const val KEY_SECONDS = "seconds"
    const val KEY_REPEAT_MINUTES = "repeatMinutes"

    override val id = "warn"
    override val displayName = "警告表示"
    override val description = "画面の上に警告を重ねる。操作は止めないので、気づかせるだけの弱い措置。"
    override val severity = 10

    override val params = listOf(
        ParamSpec.TextParam(
            KEY_MESSAGE,
            "警告文",
            default = "そろそろやめる時間。",
            multiline = true,
        ),
        ParamSpec.IntParam(KEY_SECONDS, "表示時間", default = 5, min = 1, max = 60, unit = "秒"),
        ParamSpec.IntParam(
            KEY_REPEAT_MINUTES,
            "再表示の間隔",
            default = 5,
            min = 1,
            max = 120,
            unit = "分",
            help = "条件が成立し続けているあいだ、この間隔で出し直す",
        ),
    )

    override fun summarize(p: Params): String =
        "警告(${p.int(KEY_SECONDS, 5)}秒 / ${p.int(KEY_REPEAT_MINUTES, 5)}分ごと)"
}
