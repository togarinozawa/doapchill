package com.dopachiru.core.condition.types

import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params
import com.dopachiru.core.time.ResetPolicy
import java.time.LocalDateTime

/** 集計期間のうちにアプリを開いた回数が閾値を超えたら成立する。 */
object SessionCountCondition : ConditionType {
    const val KEY_COUNT = "count"
    const val KEY_PERIOD = "period"

    override val id = "session_count"
    override val displayName = "セッション回数"
    override val description = "期間内にアプリを開いた回数が指定を超えたら成立する。だらだら開き直す癖に効く。"

    override val params = listOf(
        ParamSpec.IntParam(KEY_COUNT, "開いた回数が", default = 5, min = 1, max = 200, unit = "回"),
        ParamSpec.ResetPolicyParam(KEY_PERIOD, "集計期間", default = ResetPolicy()),
    )

    override fun evaluate(p: Params, ctx: EvalContext): Boolean {
        val policy = p.resetPolicy(KEY_PERIOD)
        return ctx.usage.sessionCountIn(policy) >= p.int(KEY_COUNT, 5)
    }

    override fun summarize(p: Params): String =
        "${p.resetPolicy(KEY_PERIOD).describe()}で${p.int(KEY_COUNT, 5)}回以上"

    /**
     * 回数が増えるのはアプリを開いた瞬間だけで、それはイベントで拾える。
     * 時間で変わるのは集計期間が切り替わるときだけ。
     */
    override fun nextChangeAt(p: Params, ctx: EvalContext): LocalDateTime =
        p.resetPolicy(KEY_PERIOD).periodEnd(ctx.now)
}
