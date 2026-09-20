package com.dopachiru.core.action.types

import com.dopachiru.core.action.ActionType
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params

/**
 * 開くとき「何をしに開いたか」を1行書かせ、そのあいだ隅に出し続ける。
 *
 * 実行意図(if-then プラン)の実装。Gollwitzer & Sheeran(2006, 94研究)の
 * メタ分析で、実行意図は目標達成に d=0.65、**作業からの脱線を防ぐ場面では d=0.77**
 * と最大の効果を持つ。あなたの「ラジオのつもりが映像を見る」「検索のつもりが
 * タイムラインを汚染する」は、まさにこの脱線。開く瞬間に目的を言語化し、
 * それを**視界に留め続ける**ことで、脱線したときに自分で気づけるようにする。
 *
 * Socialize(TiiS 2021)は同じ発想で、事前に定義した「代替の意図」を提示して
 * 起動リマインダーの70%が習慣時間を削減した。
 *
 * ## 塞がない
 * これは止める措置ではない。書かせて、出し続けるだけ。severity は低く、
 * 何かを塞ぐルールが同時に成立していればそちらが勝つ。書いた目的は
 * 経過時間(Time Fog 対策)と並べて隅に出す。
 */
object IntentionAction : ActionType {
    const val KEY_PROMPT = "prompt"
    const val KEY_SUGGESTIONS = "suggestions"
    const val KEY_SHOW_TIMER = "showTimer"

    override val id = "intention"
    override val displayName = "目的を書いて出し続ける"
    override val description =
        "開くとき「何をしに開いたか」を1行書かせ、そのあいだ画面の隅に出し続ける。止めはしない。"

    /** 経過時間表示(5)より上、警告(10)より下。何も遮らない。 */
    override val severity = 8

    // 画面を覆わないので、ほかの措置と一緒に出しておける
    override val stackable = true

    override val params = listOf(
        ParamSpec.TextParam(
            KEY_PROMPT,
            "書かせる問い",
            default = "何をしに開いた?",
            help = "開いた瞬間に出す問いかけ",
        ),
        ParamSpec.TextParam(
            KEY_SUGGESTIONS,
            "選ばせる候補",
            default = "",
            multiline = true,
            help = "改行で分けると、書く代わりに1タップで選べる候補になります。空なら自由入力だけ",
        ),
        ParamSpec.BoolParam(
            KEY_SHOW_TIMER,
            "経過時間も添える",
            default = true,
            help = "書いた目的の横に、開いてからの時間を出します(Time Fog 対策)",
        ),
    )

    override fun summarize(p: Params): String = "目的を書いて出し続ける"
}
