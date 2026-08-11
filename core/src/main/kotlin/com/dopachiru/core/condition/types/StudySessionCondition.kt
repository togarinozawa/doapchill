package com.dopachiru.core.condition.types

import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params
import java.time.LocalDateTime

/**
 * 学習予定の最中かどうかで成立する。
 *
 * 予定は連携アプリから端末内ブロードキャストで受け取る。通信は一切しないので、
 * 機内モードにしても制限は外れない。
 *
 * 窓は開始・終了の時刻を自分で持っているので、終了の通知が来なくても
 * 時刻が来れば勝手に解ける。連携アプリが落ちても閉じ込められない。
 */
object StudySessionCondition : ConditionType {
    const val KEY_DURING_SESSION = "duringSession"

    override val id = "study_session"
    override val displayName = "学習予定"
    override val description = "連携アプリが入れた学習予定の最中(または予定が無いあいだ)に成立する。"

    override val params = listOf(
        ParamSpec.BoolParam(
            KEY_DURING_SESSION,
            "予定の最中に成立させる",
            default = true,
            help = "オフにすると、学習予定が入っていないあいだに成立する",
        ),
    )

    override fun evaluate(p: Params, ctx: EvalContext): Boolean =
        ctx.study.inSession == p.bool(KEY_DURING_SESSION, true)

    override fun nextChangeAt(p: Params, ctx: EvalContext): LocalDateTime? =
        ctx.study.nextBoundaryAfter(ctx.now)

    override fun summarize(p: Params): String =
        if (p.bool(KEY_DURING_SESSION, true)) "学習予定中" else "学習予定が無いあいだ"
}
