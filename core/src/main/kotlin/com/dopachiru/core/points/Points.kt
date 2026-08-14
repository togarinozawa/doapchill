package com.dopachiru.core.points

import kotlinx.serialization.Serializable

/** ポイントが動いた理由。表示と、二重加点を防ぐ鍵づくりに使う。 */
@Serializable
enum class PointReason(val label: String) {
    /** ブロック画面から引き返した。 */
    BACKED_OFF("引き返した"),

    /** その日を押し切りゼロで終えた。 */
    CLEAN_DAY("違反ゼロの一日"),

    /** 学習予定を最後まで走り切った。 */
    STUDY_DONE("学習予定を完走"),

    /** ブロックを押し切った。 */
    OVERRIDE("押し切った"),

    /** 宣言した時間を超えた。 */
    DECLARE_OVERRUN("宣言を超えた"),

    /** 警告を無視して使い続けた。 */
    WARN_IGNORED("警告を無視した"),

    /** 解禁券を買った。 */
    PASS_BOUGHT("解禁券"),

    /** 開発ツールなどから手で動かした。 */
    MANUAL("手動"),
    ;

    /** 罰として科されるものか。連続記録を切るかどうかの判断に使う。 */
    val isViolation: Boolean
        get() = this == OVERRIDE || this == DECLARE_OVERRUN || this == WARN_IGNORED
}

/** ポイントが動いた1件。 */
@Serializable
data class PointEvent(
    val id: Long = 0L,
    val delta: Int,
    val reason: PointReason,
    val note: String = "",
    val atEpochSec: Long,
)

/**
 * ポイントの使い道と相場。端末ごとの設定。
 *
 * 使い道を2つとも切れば「増減を数えるだけ」になる。切っても加点・減点は
 * 記録し続けるので、あとから使い道を入れたときに残高がゼロから始まらない。
 */
@Serializable
data class PointPolicy(
    /** ポイントそのものを使うか。切ると画面からも消える。 */
    val enabled: Boolean = true,

    /** 押し切るのにポイントを払わせる。足りなければ押し切れない。 */
    val chargeOverride: Boolean = true,

    /** 解禁券を買えるようにする。 */
    val passEnabled: Boolean = true,

    /** 解禁券の値段。 */
    val passCost: Int = 30,

    /** 解禁券1枚で止まる時間(分)。 */
    val passMinutes: Int = 15,

    // ---- 既定の増減。ルール側で上書きできる ----

    /** 破ったときの既定の増減。 */
    val defaultBreakPoints: Int = -10,

    /** 引き返したときの既定の増減。 */
    val defaultKeepPoints: Int = 1,

    /** 違反ゼロで一日を終えたときの加点。 */
    val cleanDayPoints: Int = 20,

    /** 学習予定を完走したときの加点。 */
    val studyDonePoints: Int = 10,

    /**
     * 残高の下限。
     *
     * 際限なくマイナスに沈むと、そこから何をしても押し切れないまま
     * 「もうどうにでもなれ」に振り切ってしまう。底を作って戻れるようにする。
     */
    val floor: Int = -100,
) {
    /** 使い道が1つも無い = 増減を数えるだけの状態か。 */
    val recordOnly: Boolean get() = !chargeOverride && !passEnabled

    /** 押し切りにいくら要るか。払わせない設定なら 0。 */
    fun overrideCost(breakPoints: Int?): Int {
        if (!enabled || !chargeOverride) return 0
        val points = breakPoints ?: defaultBreakPoints
        return if (points < 0) -points else 0
    }

    /** 破ったときの増減。 */
    fun breakDelta(breakPoints: Int?): Int = breakPoints ?: defaultBreakPoints

    /** 引き返したときの増減。 */
    fun keepDelta(keepPoints: Int?): Int = keepPoints ?: defaultKeepPoints

    companion object {
        val DEFAULT = PointPolicy()
    }
}
