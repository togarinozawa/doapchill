package com.dopachiru.core.model

import kotlinx.serialization.Serializable

/**
 * ルールを破った / 守ったときのポイントの増減。
 *
 * ルールの「条件 × アクション」とは別の軸。アクションは**その場で**何を出すかを、
 * こちらは**そのあと**ポイントがどう動くかを決める。
 *
 * ## 封鎖を重ねる仕組みは持たない
 *
 * 以前はここに「破ったら追加でどれだけ・どこを閉めるか」も持たせていたが、
 * **押し切ることと破ることは同じ出来事**なので、そのあとにもう一段
 * 閉める長さを別に決めさせても、二重に設定させるだけで分かりやすくならない。
 * 閉める長さそのものが要るなら、措置([com.dopachiru.core.action.types.LockoutAction]
 * =「しばらく閉め出す」)の側で選ぶ。
 *
 * ポイントの増減を null にしておけるのが肝で、そのときは設定の既定値が使われる。
 * 0 を既定にしてしまうと、この機能より前に作ったルールだけポイントが動かない
 * 状態で取り残される ── 既存のルールにも黙って効いてほしいので、
 * 「指定なし = 設定に従う」を表せる形にしてある。
 */
@Serializable
data class Consequence(
    /** 破ったときのポイント増減。ふつうは負。null なら設定の既定値。 */
    val breakPoints: Int? = null,

    /** 引き返したときのポイント増減。ふつうは正。null なら設定の既定値。 */
    val keepPoints: Int? = null,
) {
    fun summarize(): String {
        val points = breakPoints?.takeIf { it != 0 }?.let { if (it < 0) "${-it}ポイント払う" else "+${it}ポイント" }
        return points ?: "設定どおり"
    }

    companion object {
        val NONE = Consequence()
    }
}
