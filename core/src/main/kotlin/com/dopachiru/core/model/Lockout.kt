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
    val target: Target,
    val untilEpochSec: Long,

    /** 何を破った罰か。封鎖画面に出す。 */
    val reason: String,

    val createdAtEpochSec: Long,
) {
    fun isActiveAt(nowSec: Long): Boolean = nowSec < untilEpochSec

    fun remainingMinutesAt(nowSec: Long): Int =
        ((untilEpochSec - nowSec + 59) / 60).coerceAtLeast(0).toInt()
}

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
