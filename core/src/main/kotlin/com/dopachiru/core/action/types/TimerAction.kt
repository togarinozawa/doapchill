package com.dopachiru.core.action.types

import com.dopachiru.core.action.ActionType
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params

/**
 * 経過時間を出し続ける。止めも遮りもしない。
 *
 * アテンションキャプチャの11パターンのうち #10 は **"Time Fog"**
 * ── 経過時間の自覚を奪う設計(Monge Roffarello, Lukoff, De Russis, CHI 2023)。
 * 無限スクロールと自動再生が効くのは、いま何分経ったかが分からなくなるから。
 * その直接の対抗が、時計を画面に出し続けることになる。
 *
 * Tran ら(CHI 2019)は、**約30分で「時間を無駄にした」という嫌悪感が
 * 自然に生じる**("30-Minute Ick Factor")ことを見つけた。
 * 経過時間が見えていれば、この嫌悪感はもっと早く来る。
 * 止めに来る介入ではなく、**自分でやめる材料を渡す**措置。
 *
 * 主体感の面でも筋が通っている。Lukoff ら(CHI 2021)の YouTube 調査では、
 * **視聴履歴・統計は93%が「制御感が増す」**と答えた数少ない機能だった。
 */
object TimerAction : ActionType {
    const val KEY_AFTER_MINUTES = "afterMinutes"
    const val KEY_SHOW_TODAY = "showToday"

    override val id = "timer"
    override val displayName = "経過時間を出し続ける"
    override val description = "画面の隅に使用時間を出す。操作は一切止めない。いちばん弱い措置。"

    /** 警告(10)より下。何も遮らないので、他が成立していればそちらが勝つ。 */
    override val severity = 5

    override val params = listOf(
        ParamSpec.IntParam(
            KEY_AFTER_MINUTES,
            "何分経ってから出すか",
            default = 0,
            min = 0,
            max = 120,
            unit = "分",
            help = "0 なら開いた瞬間から。短い用事まで数えられたくなければ数分置く",
        ),
        ParamSpec.BoolParam(
            KEY_SHOW_TODAY,
            "今日の合計も出す",
            default = true,
            help = "いまのセッションだけでなく、今日そのアプリに使った合計も添える",
        ),
    )

    override fun summarize(p: Params): String {
        val after = p.int(KEY_AFTER_MINUTES, 0)
        return if (after == 0) "経過時間を常時表示" else "${after}分後から経過時間を表示"
    }
}
