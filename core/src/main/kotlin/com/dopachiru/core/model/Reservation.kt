package com.dopachiru.core.model

import kotlinx.serialization.Serializable

/**
 * 「この時間だけ使う」と先に決めておく予約。
 *
 * ## 何のためか
 * 予約は**冷静なうちに決める**ための仕組み。開いた瞬間に「何分使う」と決める
 * [com.dopachiru.core.action.types.DeclareAction] は、そのときすでに欲求が
 * 立ち上がっている。予約は違う ── 使いたくなる前に「21時から30分だけ」と枠を取り、
 * その外では塞ぐ。Sticky Goals(Lee ら, CHI 2021)の事前コミットメントにあたる。
 *
 * ## 直前予約を封じる
 * [ReservationRules.MIN_LEAD_MINUTES] より手前の時刻には予約できない
 * (端末側の予約 UI が弾く)。いま開きたいから今すぐ予約する、では
 * 冷静な決定にならない。少し先にしか置けないからこそ、
 * 「よし、この時間で済ませよう」と予定になる。
 *
 * ## 枠を逃しても損しない
 * 予約は「使ってよい時間」であって「使わなければ損をする時間」ではない。
 * 後者にすると ACDP #7(Playing by Appointment)そのものになり、
 * 枠を守るために不本意に開くようになる。枠を流しても罰は無い。
 */
@Serializable
data class Reservation(
    val id: Long = 0L,

    /** 端末をまたいで一意な ID。同期の鍵。 */
    val uid: String = "",

    /** 何を使ってよい枠か。ルールの対象と同じ書き方。 */
    val target: Target,

    val startEpochSec: Long,
    val endEpochSec: Long,

    /** 何のための枠か。任意。 */
    val note: String = "",

    /**
     * どの型([ReservationPolicy])から取った枠か。空なら型より前に取ったもの。
     *
     * 間隔と1日の回数を数えるときの鍵。型を消して作り直しても古い枠が残るので、
     * 数えるときは対象の重なりも見る([ReservationRules.bookedUnder])。
     */
    val policyId: String = "",

    /**
     * どの端末の枠か(deviceId の集合)。空ならどの端末でも。
     *
     * **スマホから PC の枠を取る**のがこれの主な使い道。冷静なうちに決めるという
     * 予約の性質からして、決める端末と使う端末が別でも構わない ── むしろ
     * 「PC の前に座る前に決める」ほうが、予約の趣旨に合っている。[DeviceScope]
     */
    val devices: Set<String> = DeviceScope.EVERYWHERE,
) {
    fun coversAt(nowSec: Long): Boolean = nowSec in startEpochSec until endEpochSec

    /** その端末で効かせるか。 */
    fun appliesToDevice(deviceId: String): Boolean = DeviceScope.appliesTo(devices, deviceId)

    /** まだ始まっていない予約か。 */
    fun isUpcomingAt(nowSec: Long): Boolean = nowSec < startEpochSec

    /** もう終わった予約か。掃除の対象。 */
    fun isPastAt(nowSec: Long): Boolean = nowSec >= endEpochSec
}

/** 予約の判定。端末側の保存方法に依存しないようここに置く。 */
object Reservations {

    /**
     * そのアプリが、いま予約された時間帯の中に居るか。
     *
     * 対象が当たる予約が1つでも「いま有効」なら真。
     */
    fun covers(
        all: List<Reservation>,
        packageName: String,
        tagsOfApp: Set<String>,
        nowSec: Long,
        url: String? = null,
        deviceId: String = "",
    ): Boolean = all.any {
        it.coversAt(nowSec) &&
            it.appliesToDevice(deviceId) &&
            it.target.matches(packageName, tagsOfApp, url)
    }

    /** 終わった予約を落とす。 */
    fun prune(all: List<Reservation>, nowSec: Long): List<Reservation> =
        all.filterNot { it.isPastAt(nowSec) }
}
