package com.dopachiru.core.engine

import java.time.LocalDateTime

/**
 * 1回のルール評価に渡される「今の状況」。
 *
 * 新しい条件が新しい種類の入力を必要とするようになったら、ここにフィールドを1つ足す。
 * 条件の側は必要なものだけを読むので、既存の条件には影響しない。
 */
data class EvalContext(
    /** 評価時刻(端末のローカル時刻)。 */
    val now: LocalDateTime,

    /** 今フォアグラウンドにあるアプリのパッケージ名。 */
    val packageName: String,

    /** そのアプリの使用実績。 */
    val usage: UsageSnapshot,

    /** カレンダーの状況。権限が無ければ [CalendarState.NONE]。 */
    val calendar: CalendarState = CalendarState.NONE,

    /** 学習予定の状況。連携していなければ [StudyState.NONE]。 */
    val study: StudyState = StudyState.NONE,

    /** 端末が省電力モードか。 */
    val powerSaveMode: Boolean = false,

    /**
     * 「開く前に宣言させる」で申告した残り時間(分)。
     * 宣言していなければ null、宣言ぶんを使い切っていれば 0 以下。
     */
    val declaredRemainingMinutes: Int? = null,
)
