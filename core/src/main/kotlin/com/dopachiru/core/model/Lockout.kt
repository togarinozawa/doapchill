package com.dopachiru.core.model

import kotlinx.serialization.Serializable

/**
 * ルールを破った罰として、いま閉まっている封鎖。
 *
 * ルールと違って条件を持たない。「いつまで」「どの範囲」だけの、
 * 時間が来れば勝手に解ける単純な状態。だからこそ持ち主が落ちても閉じ込めない。
 *
 * 閉める範囲を [Target] で持つので、ルールが後から書き換わっても
 * 科した時点の範囲のまま閉まり続ける。罰の重さが後出しで変わらない。
 */
@Serializable
data class Lockout(
    val id: Long = 0L,

    /**
     * 端末をまたいで一意な ID。同期の鍵。
     *
     * 罰にも要る(同じ罰が両方の端末で二重に科されないように)が、
     * 効いてくるのは自分で始めた集中のほう ── スマホで始めた集中が
     * PC でも閉まってほしい、という話は端末をまたぐ。
     */
    val uid: String = "",

    val target: Target,
    val untilEpochSec: Long,

    /** 何を破った罰か、あるいは何のための集中か。封鎖画面に出す。 */
    val reason: String,

    val createdAtEpochSec: Long,

    /**
     * 途中で終わらせる手段。**null なら手段は無い。**
     *
     * 罰と、自分で始めた集中を分けるのがこの欄。
     * 罰は時間が来るまで開かない(解禁券でも解けない)。
     * 自分で始めたものには出口が要る ── 自分で30分と決めただけのものを
     * 罰と同じ強さで閉じると、次から怖くて始められなくなる。
     */
    val earlyExit: EarlyExit? = null,
) {
    fun isActiveAt(nowSec: Long): Boolean = nowSec < untilEpochSec

    fun remainingMinutesAt(nowSec: Long): Int =
        ((untilEpochSec - nowSec + 59) / 60).coerceAtLeast(0).toInt()

    /** 自分で始めたものか。罰なら false。 */
    val isChosen: Boolean get() = earlyExit != null

    /** いま無料で取り消せるか(押し間違いのための短い猶予)。 */
    fun canCancelFreelyAt(nowSec: Long): Boolean =
        earlyExit != null && nowSec < earlyExit.freeUntilEpochSec
}

/**
 * 自分で始めた封鎖を、時間より前に終わらせるための条件。
 *
 * 手間とポイントの**両方**を課す。片方だけでは足りない ──
 * 手間だけなら慣れるし、ポイントだけなら貯まっていれば素通りできる。
 */
@Serializable
data class EarlyExit(
    /** BlockAction.Effort の値。3秒長押しか、言葉の打ち込み。 */
    val effort: String = "hold",

    /** 途中でやめるときに払うポイント。 */
    val points: Int = 0,

    /**
     * ここまでは無料で取り消せる。
     *
     * ホーム画面に置いたショートカットは**押し間違える**。
     * 押し間違いまで有料にすると、置くこと自体が怖くなる。
     */
    val freeUntilEpochSec: Long = 0L,
)

/** 封鎖の一覧に対する判定。端末側の保存方法に依存しないよう、ここに置いてある。 */
object Lockouts {

    /**
     * そのアプリに効いている封鎖のうち、いちばん遅くまで続くもの。
     *
     * 複数の罰が重なったとき、短いほうで解けてしまっては罰にならない。
     */
    fun activeFor(
        all: List<Lockout>,
        packageName: String,
        tagsOfApp: Set<String>,
        nowSec: Long,
        url: String? = null,
    ): Lockout? = all
        .filter { it.isActiveAt(nowSec) && it.target.matches(packageName, tagsOfApp, url) }
        .maxByOrNull { it.untilEpochSec }

    /** 期限の切れたものを落とす。 */
    fun prune(all: List<Lockout>, nowSec: Long): List<Lockout> =
        all.filter { it.isActiveAt(nowSec) }
}
