package com.dopachiru.core.engine

import java.time.LocalDateTime

/**
 * いま学習予定の最中かどうか。
 *
 * 予定の出どころ(別アプリからのブロードキャストなのか、将来別の何かなのか)は
 * app 側の都合なので、core はこの形しか知らない。[CalendarState] と同じ境界。
 */
interface StudyState {
    /** 学習の窓の中にいるか。 */
    val inSession: Boolean

    /** いま入っている窓の名前。表示用。 */
    val currentTitle: String?

    /**
     * [now] より後で、[inSession] が切り替わる最も早い時刻。
     *
     * これを答えられるおかげで、学習中は窓の終わりまで一度も起きずに済む。
     * 分からなければ null を返す(呼び出し側は短い間隔で見に来る)。
     */
    fun nextBoundaryAfter(now: LocalDateTime): LocalDateTime?

    companion object {
        /** 連携していない、または予定が1件も来ていない状態。 */
        val NONE: StudyState = object : StudyState {
            override val inSession = false
            override val currentTitle: String? = null
            override fun nextBoundaryAfter(now: LocalDateTime): LocalDateTime? = null
        }
    }
}
