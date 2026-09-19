package com.dopachiru.core.condition.types

import com.dopachiru.core.condition.ConditionGroup
import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.model.ScreenSignals
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params

/**
 * いま出ている画面が、指定した目印のどれかに当たれば成立する。
 *
 * アプリ全体ではなく**その中の特定の面**を狙う。Cho ら(CSCW 2021)の機能ドリフト・
 * ラビットホールは、アプリ単位では捉えられない ── 「YouTube を止める」ではなく
 * 「YouTube のショートだけ止める」を書くための条件。
 *
 * 目印が取れなければ空集合が来るので、何にも当たらない(＝素通し)。
 * アプリの更新で検出が壊れても、閉じ込める側ではなく緩む側に倒れる。
 */
object OnScreenCondition : ConditionType {
    const val KEY_SIGNALS = "signals"

    override val id = "on_screen"
    override val displayName = "特定の画面のとき"
    override val description =
        "ショート・リール・おすすめタブなど、アプリの中の特定の画面だけを狙う。取れないときは素通しになる。"

    override val group = ConditionGroup.TRIGGER
    override val example = "YouTube のショートの画面に入った瞬間だけ"

    override val params = listOf(
        ParamSpec.EnumParam(
            KEY_SIGNALS,
            "狙う画面",
            options = ScreenSignals.all.map { ParamSpec.EnumParam.Option(it.id, it.label) },
            default = ScreenSignals.SHORT_VIDEO,
            help = "取り方は端末しだいで、アプリの更新で外れることがあります",
        ),
    )

    override fun evaluate(p: Params, ctx: EvalContext): Boolean {
        val wanted = p.string(KEY_SIGNALS).ifBlank { return false }
        return wanted in ctx.screenSignals
    }

    override fun summarize(p: Params): String =
        "${ScreenSignals.labelOf(p.string(KEY_SIGNALS))}のとき"
}
