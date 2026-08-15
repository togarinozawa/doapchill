package com.dopachiru.core.action

import com.dopachiru.core.engine.EvalContext

/**
 * 同じ文が続かないように、候補から1つ選ぶ。
 *
 * 固定の介入は**露出1日ごとに効果が25%落ちる**(HabitLab, CSCW 2018)。
 * 逆に27種を回したときは1日あたり**-34%**まで伸びた。
 * 出す文を変えるだけでも、脳が風景として処理しはじめるのを遅らせられる。
 *
 * ただしローテーションは離脱を倍増させる(7日残存率 68% → 39%)。
 * **「なぜ毎回違うのか」を説明すると 80% まで戻る**ので、
 * 画面の側で理由を一言添えること([EXPLANATION] を使う)。
 *
 * 選び方はセッションごとに固定。同じ使用のあいだに文が入れ替わると、
 * 読んでいる最中に変わって落ち着かない。
 */
object Rotation {

    /**
     * ローテーションの理由。介入画面に添える。
     * これが無いと、変わること自体が不信感になる。
     */
    const val EXPLANATION = "同じ言葉だと慣れるので、毎回変えています"

    /** 改行で区切られた候補。空行は捨てる。 */
    fun optionsOf(text: String): List<String> =
        text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 候補から1つ選ぶ。候補が1つ以下ならそのまま返す。
     *
     * 種はルール・アプリ・セッションから作るので、同じ使用のあいだは変わらず、
     * 開き直すと変わる。
     */
    fun pick(text: String, ctx: EvalContext, fallback: String = ""): String {
        val options = optionsOf(text)
        if (options.isEmpty()) return fallback
        if (options.size == 1) return options[0]
        return options[index(ctx, options.size)]
    }

    /** 候補が複数あるか。画面に理由を添えるかどうかの判断に使う。 */
    fun rotates(text: String): Boolean = optionsOf(text).size > 1

    private fun index(ctx: EvalContext, size: Int): Int {
        var hash = 1125899906842597L
        hash = hash * 31 + ctx.currentRuleId
        hash = hash * 31 + ctx.sessionSeed
        for (char in ctx.packageName) hash = hash * 31 + char.code
        hash = hash xor (hash ushr 33)
        hash *= -0xae502812aa7333L
        hash = hash xor (hash ushr 29)
        return (((hash % size) + size) % size).toInt()
    }
}
