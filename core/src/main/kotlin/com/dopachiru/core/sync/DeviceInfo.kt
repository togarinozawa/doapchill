package com.dopachiru.core.sync

import kotlinx.serialization.Serializable

/**
 * 端末の名簿の1行。
 *
 * ## なぜ要るのか
 *
 * 「スマホから PC の予約を入れる」には、まず**どの端末があるのかを知る**必要が
 * あります。これまで `deviceId` は実績を端末ごとに分けるための見出しでしか
 * なかったので、どこにも一覧がありませんでした。
 *
 * 各端末が同期のたびに自分の行を書き、他の端末の行を読みます。
 * これで相手を選ぶ画面が作れます。
 *
 * ## 生きているかどうか
 *
 * [lastSeenSec] は**その端末が最後に同期した時刻**であって、いま起きているか
 * ではありません。PC を閉じてから頼みごとを積んでも、次に開いたときに届きます
 * ── 届かないのではなく遅れるだけ、という区別を画面でも保ちます。
 */
@Serializable
data class DeviceInfo(
    val deviceId: String,
    /** 人が読む名前。既定は deviceId のまま。 */
    val name: String = "",
    /** [AppInfo.ANDROID] / [AppInfo.WINDOWS]。 */
    val platform: String = "",
    /** アプリの版。食い違いの切り分け用。 */
    val version: String = "",
    /** 最後に同期した時刻。 */
    val lastSeenSec: Long = 0L,
) {
    val displayName: String get() = name.ifBlank { deviceId }

    /** 最後に同期してからの分。 */
    fun minutesSinceSeen(nowSec: Long): Long = ((nowSec - lastSeenSec) / 60).coerceAtLeast(0)

    /** ざっくりした生存の見立て。画面に出すためだけのもの。 */
    fun freshnessAt(nowSec: Long): String = when (val m = minutesSinceSeen(nowSec)) {
        in 0..3 -> "いま"
        in 4..59 -> "${m}分前"
        in 60..(60 * 24 - 1) -> "${m / 60}時間前"
        else -> "${m / (60 * 24)}日前"
    }

    companion object {
        const val PLATFORM_ANDROID = AppInfo.ANDROID
        const val PLATFORM_WINDOWS = AppInfo.WINDOWS
    }
}
