package com.dopachiru.core.engine

/**
 * いまカレンダー上どうなっているか。
 *
 * core を Android 非依存に保つための境界。予定の取得元(端末のカレンダー
 * Provider なのか、将来 API を直接叩くのか)は app 側の都合であって、
 * 条件やゲートの側は知らなくてよい。
 */
interface CalendarState {
    /** 何らかの予定の最中か。 */
    val busy: Boolean

    /** タイトルに [keyword] を含む予定の最中か。大文字小文字は区別しない。 */
    fun inEventMatching(keyword: String): Boolean

    /** いま入っている予定のタイトル。表示用。 */
    val currentTitles: List<String>

    companion object {
        /** 権限が無い、または連携していない状態。 */
        val NONE: CalendarState = object : CalendarState {
            override val busy = false
            override fun inEventMatching(keyword: String) = false
            override val currentTitles = emptyList<String>()
        }
    }
}
