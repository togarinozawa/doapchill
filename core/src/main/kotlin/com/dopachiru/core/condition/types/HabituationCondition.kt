package com.dopachiru.core.condition.types

import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params

/**
 * そのルールが効かなくなってきたときに成立する。
 *
 * どんな介入も慣れる。固定の介入は**露出1日ごとに効果が25%落ちる**(HabitLab)。
 * GoalKeeper の著者も「単一の強度を選ぶのではなく、強度を適応的に変えて
 * 段階的に強い介入へ導け」と結んでいる。
 *
 * 慣れたかどうかは、押し切った回数で測れる。守れているうちは何も起きず、
 * 効かなくなってきたときにだけ次の手が出る ── 最初から強くしないための条件。
 *
 * ## 使い方
 * 同じ対象に2つルールを置く。
 *  - 軽いほう(警告・遅延): 条件は普通に
 *  - 重いほう(封印): これを足して「◯回押し切ったら」
 *
 * 依存傾向が高い人ほど強い介入を拒む(GoalKeeper: 41.7%が最弱を選好)ので、
 * 強いほうを既定にせず、必要になってから出すほうが結局続く。
 */
object HabituationCondition : ConditionType {
    const val KEY_OVERRIDES = "overrides"

    override val id = "habituation"
    override val displayName = "このルールに慣れてきたら"
    override val description =
        "このルールを最近何回も押し切っているときだけ成立する。効かなくなってから強める段取りに使う。"

    override val params = listOf(
        ParamSpec.IntParam(
            KEY_OVERRIDES,
            "直近1週間の押し切り回数",
            default = 3,
            min = 1,
            max = 50,
            unit = "回",
            help = "この回数以上押し切っていたら成立する",
        ),
    )

    override fun evaluate(p: Params, ctx: EvalContext): Boolean {
        if (ctx.currentRuleId == 0L) return false
        return ctx.overrideCountOf(ctx.currentRuleId) >= p.int(KEY_OVERRIDES, 3)
    }

    override fun summarize(p: Params): String =
        "直近1週間に${p.int(KEY_OVERRIDES, 3)}回以上押し切っている"
}
