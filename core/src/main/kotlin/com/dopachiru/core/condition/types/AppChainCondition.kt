package com.dopachiru.core.condition.types

import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params

/**
 * 直前に使っていたアプリで成立する。
 *
 * 習慣的な起動の多くは単独では起きず、**アプリからアプリへの連鎖**として起きる
 * (Monge Roffarello & De Russis, TiiS 2021 の言う App Habits)。
 * 同じ研究では、セッションの82.94%が予測可能なパターンに収まり、
 * 望まない習慣の60%が SNS だった。
 *
 * 「LINE を閉じた流れで X を開く」のような、自分でも気づいていない導線に
 * 名指しで介入するための条件。同じアプリでも「ホームから意図して開いた」ときは
 * 素通しにできるので、必要な用事まで塞がずに済む。
 */
object AppChainCondition : ConditionType {
    const val KEY_PACKAGES = "packages"
    const val KEY_FROM_HOME = "fromHome"

    override val id = "app_chain"
    override val displayName = "直前に使っていたアプリ"
    override val description =
        "選んだアプリから流れてきたときだけ成立する。「LINE のあと X を開く」のような連鎖を狙い撃つ。"

    override val params = listOf(
        ParamSpec.PackagesParam(
            KEY_PACKAGES,
            "直前のアプリ",
            help = "ここで選んだアプリを使った直後だけ成立する",
        ),
        ParamSpec.BoolParam(
            KEY_FROM_HOME,
            "ホームから開いたときも成立させる",
            default = false,
            help = "オンにすると、直前に何も使っていない場合(ホーム経由)も含める",
        ),
    )

    override fun evaluate(p: Params, ctx: EvalContext): Boolean {
        val previous = ctx.previousPackage
            ?: return p.bool(KEY_FROM_HOME, false)
        return previous in p.stringSet(KEY_PACKAGES)
    }

    override fun summarize(p: Params): String {
        val count = p.stringSet(KEY_PACKAGES).size
        val home = if (p.bool(KEY_FROM_HOME, false)) "・ホーム" else ""
        return if (count == 0) "直前のアプリ(未選択)" else "${count}個のアプリ${home}の直後"
    }
}
