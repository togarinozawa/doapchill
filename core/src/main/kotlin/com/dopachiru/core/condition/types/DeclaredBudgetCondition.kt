package com.dopachiru.core.condition.types

import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params
import java.time.LocalDateTime

/**
 * 開く前に宣言した持ち時間を使い切っていれば成立する。
 *
 * 宣言そのものを求めるのは DeclareAction の役目。この条件は
 * 「宣言を破ったときだけ更に強い措置を足す」といった組み合わせのために置いてある。
 */
object DeclaredBudgetCondition : ConditionType {
    const val KEY_TREAT_UNDECLARED_AS_EXCEEDED = "undeclaredCounts"

    override val id = "declared_budget"
    override val displayName = "宣言した時間の超過"
    override val description = "開く前に自分で申告した持ち時間を使い切っていたら成立する。"

    override val params = listOf(
        ParamSpec.BoolParam(
            KEY_TREAT_UNDECLARED_AS_EXCEEDED,
            "まだ宣言していない場合も成立させる",
            default = false,
        ),
    )

    override fun evaluate(p: Params, ctx: EvalContext): Boolean {
        val remaining = ctx.declaredRemainingMinutes
            ?: return p.bool(KEY_TREAT_UNDECLARED_AS_EXCEEDED, false)
        return remaining <= 0
    }

    override fun summarize(p: Params): String =
        if (p.bool(KEY_TREAT_UNDECLARED_AS_EXCEEDED, false)) "宣言を超過 / 未宣言" else "宣言を超過"

    /** 残り時間が尽きるまでは変わらない。宣言していなければ時間では変わらない。 */
    override fun nextChangeAt(p: Params, ctx: EvalContext): LocalDateTime {
        val remaining = ctx.declaredRemainingMinutes ?: return ctx.now.plusDays(1)
        return if (remaining > 0) ctx.now.plusMinutes(remaining.toLong()) else ctx.now.plusDays(1)
    }
}
