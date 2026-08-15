package com.dopachiru.core.condition.types

import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params

/**
 * 閉じてすぐ開き直したときに成立する。
 *
 * Tran ら(CHI 2019)が挙げた起動トリガーのうち、いちばん質が悪いのが
 * **"Nothing Specific"** ── 目的も無く、ほとんど反射で掴む確認行動。
 * 「さっき見たばかり」は、その反射をいちばん素直に捉えられる合図になる。
 *
 * 用事があって開いた1回目は通し、意味のない2回目だけを止められる。
 * 合計時間や回数の上限と違って、**開いた瞬間に**判定できるのが利点。
 */
object QuickReopenCondition : ConditionType {
    const val KEY_WITHIN_MINUTES = "withinMinutes"

    override val id = "quick_reopen"
    override val displayName = "閉じてすぐ開き直した"
    override val description =
        "前に閉じてから短い時間で開き直したときに成立する。目的の無い確認行動を捉える。"

    override val params = listOf(
        ParamSpec.IntParam(
            KEY_WITHIN_MINUTES,
            "この時間以内に開き直したら",
            default = 5,
            min = 1,
            max = 120,
            unit = "分",
        ),
    )

    override fun evaluate(p: Params, ctx: EvalContext): Boolean {
        // 記録が無い = 初めて開いた。確認行動とは言えないので成立させない
        val gap = ctx.usage.minutesSinceLastSession ?: return false
        return gap <= p.int(KEY_WITHIN_MINUTES, 5)
    }

    override fun summarize(p: Params): String =
        "${p.int(KEY_WITHIN_MINUTES, 5)}分以内に開き直した"
}
