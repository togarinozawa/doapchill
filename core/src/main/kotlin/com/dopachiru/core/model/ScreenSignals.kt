package com.dopachiru.core.model

/**
 * アプリの中の「どの画面か」を表す目印。
 *
 * アプリ全体ではなく、その中の特定の面だけを狙うために使う ──
 * Cho ら(CSCW 2021)の言う**機能ドリフト**(目的機能の隣の推薦機能に脱線する)と
 * **ラビットホール**(短尺推薦フィード)は、アプリ単位では捉えられない。
 * 「YouTube を止める」ではなく「YouTube のショートだけ止める」を書けるようにする。
 *
 * ## なぜ文字列の集合なのか
 * 取り方は端末で違う ── Android はアクセシビリティのノードツリー、ブラウザは URL。
 * その差を core に持ち込まないよう、**正規化した目印の名前**だけを受け渡す。
 * ここに定数として一覧を置いておくと、検出側(app)と条件側(core)が
 * 同じ綴りを使える。綴りがずれると黙って当たらなくなるので、生文字列は書かない。
 *
 * ## 脆さについて
 * ノードツリーはアプリの更新で変わる。目印が取れなくなったら
 * [EvalContext.screenSignals] が空になり、画面ルールは**当たらなくなる**
 * (塞ぎ続けるのではなく、素通しに倒す)。取りこぼしても閉じ込めない側に倒してある。
 */
object ScreenSignals {

    /** 短尺の縦スワイプ動画。YouTube ショート / TikTok の本体。 */
    const val SHORT_VIDEO = "short_video"

    /** Instagram リールなど、SNS 内の短尺フィード。 */
    const val REELS = "reels"

    /** おすすめ・発見タブ。自分でフォローしていないものが流れてくる面。 */
    const val EXPLORE = "explore"

    /** フォロー中のタイムライン / ホームフィード。無限スクロールの本体。 */
    const val HOME_FEED = "home_feed"

    /** 選べる目印の一覧。設定画面と雛形が参照する。 */
    val all: List<Signal> = listOf(
        Signal(SHORT_VIDEO, "ショート動画", "YouTube ショート・TikTok のような縦スワイプ動画"),
        Signal(REELS, "リール", "Instagram リールなどの短尺フィード"),
        Signal(EXPLORE, "おすすめ・発見", "フォローしていないものが流れてくるタブ"),
        Signal(HOME_FEED, "タイムライン", "ホームの無限スクロール"),
    )

    fun labelOf(id: String): String = all.firstOrNull { it.id == id }?.label ?: id

    data class Signal(val id: String, val label: String, val help: String)
}
