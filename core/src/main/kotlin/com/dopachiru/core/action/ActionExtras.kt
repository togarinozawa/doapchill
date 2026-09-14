package com.dopachiru.core.action

import com.dopachiru.core.param.Params

/**
 * 措置に「重ねる」ちょっとした振る舞い。
 *
 * 「閉じる」の本体(完全封印 / 閉め出す)とは別に、その前後にそっと足したいものが
 * ある ── いまは「閉じる前にそっと知らせる」だけ。どの閉じ方でも同じキーで読める
 * ように、ここに1か所だけ置く。閉じ方ごとに別の名前で持つと、端末側が2通り読む
 * ことになる。
 */
object ActionExtras {

    /**
     * 閉じる前に、薄い予告を出す秒数。0 なら出さない。
     *
     * いきなり画面を奪うと、書きかけや操作中のものが飛ぶ。数秒だけ「もう閉じるよ」と
     * そっと出してから閉じると、手を止める間ができる。完全封印にも閉め出しにも効く。
     */
    const val KEY_PREWARN_SECONDS = "prewarnSeconds"

    /** 予告の既定の長さ。長すぎると回避の隙になるので短め。 */
    const val DEFAULT_PREWARN_SECONDS = 3

    const val MAX_PREWARN_SECONDS = 30

    fun prewarnSeconds(params: Params): Int =
        params.int(KEY_PREWARN_SECONDS, 0).coerceIn(0, MAX_PREWARN_SECONDS)
}
