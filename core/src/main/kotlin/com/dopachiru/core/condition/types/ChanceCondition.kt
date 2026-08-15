package com.dopachiru.core.condition.types

import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params

/**
 * 決めた確率でだけ成立する。
 *
 * 摩擦は「毎回」かけると嫌われる。次の投稿を見る前にリアクションを強制した実験では
 * 記憶も注意も改善したのに、**53%が苛立ち、33%は継続意欲が下がった**
 * (Design Frictions on Social Media, 2024)。著者らの提言は
 * 「頻度を下げる・ユーザーが頻度を調整できるようにする」。
 *
 * 効き目のもう半分は**慣れ**の側にある。同じ介入は露出1日ごとに効果が25%落ちる
 * (HabitLab)。毎回同じものが出ると、脳がそれを風景として処理しはじめる。
 * 出たり出なかったりするほうが、1回あたりの重みが残る。
 *
 * ## 同じセッションのあいだは答えを変えない
 * 評価のたびに引き直すと、ブロックが数秒おきに出たり消えたりする。
 * ルール・アプリ・セッションの3つから決まる種で引くので、
 * **一度の使用のあいだは同じ答え**になり、開き直せばまた引き直す。
 */
object ChanceCondition : ConditionType {
    const val KEY_PERCENT = "percent"

    override val id = "chance"
    override val displayName = "確率で"
    override val description =
        "決めた確率でだけ成立する。毎回だと慣れる・嫌われる介入を、たまにだけ出すために使う。"

    override val params = listOf(
        ParamSpec.IntParam(
            KEY_PERCENT,
            "成立する確率",
            default = 50,
            min = 1,
            max = 100,
            unit = "%",
            help = "同じ一度の使用のあいだは結果が変わらない。開き直すと引き直す",
        ),
    )

    override fun evaluate(p: Params, ctx: EvalContext): Boolean {
        val percent = p.int(KEY_PERCENT, 50).coerceIn(0, 100)
        if (percent >= 100) return true
        if (percent <= 0) return false
        return draw(ctx) < percent
    }

    /** 0..99 の安定した抽選値。 */
    private fun draw(ctx: EvalContext): Int {
        var hash = 1125899906842597L // 素数。初期値が 0 だと種が 0 のとき常に 0 になる
        hash = hash * 31 + ctx.currentRuleId
        hash = hash * 31 + ctx.sessionSeed
        for (char in ctx.packageName) hash = hash * 31 + char.code
        // 下位ビットは種の連番性を引きずるので、混ぜてから使う
        hash = hash xor (hash ushr 33)
        hash *= -0xae502812aa7333L
        hash = hash xor (hash ushr 29)
        return ((hash % 100) + 100).toInt() % 100
    }

    override fun summarize(p: Params): String = "${p.int(KEY_PERCENT, 50)}%の確率"
}
