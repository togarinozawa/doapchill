package com.dopachiru.core.gate

import com.dopachiru.core.engine.CalendarState
import com.dopachiru.core.time.ALL_DAYS
import com.dopachiru.core.time.describeDays
import com.dopachiru.core.time.formatMinuteOfDay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalDateTime

/**
 * 設定変更を通しにくくするための関門。
 *
 * ルールの変更は即時反映されず、[ChangeRequest] として起票されたうえで
 * すべての Gate を通過して初めて適用される。
 */
@Serializable
sealed interface Gate {
    /** 通過済みかどうかを記録するときのキー。同じ種類は1つまで。 */
    val key: String

    /** 設定画面に出す説明。 */
    fun describe(): String

    /** パスワードを入力させる。ハッシュはアプリ設定側に1つだけ持つ。 */
    @Serializable
    @SerialName("password")
    data object Password : Gate {
        override val key get() = "password"
        override fun describe() = "パスワードを入力する"
    }

    /** ミニゲームをクリアさせる。 */
    @Serializable
    @SerialName("miniGame")
    data class MiniGame(val gameId: String = "arithmetic", val rounds: Int = 5) : Gate {
        override val key get() = "miniGame"
        override fun describe() = "ミニゲームを${rounds}問クリアする"
    }

    /** 起票から一定時間置く。時間の経過だけで自動的に通過する。 */
    @Serializable
    @SerialName("cooldown")
    data class Cooldown(val minutes: Int = 60) : Gate {
        override val key get() = "cooldown"
        override fun describe(): String =
            if (minutes >= 60) "起票から${minutes / 60}時間待つ" else "起票から${minutes}分待つ"
    }

    /**
     * 変更を適用できる曜日と時刻帯を絞る。
     *
     * 終了が開始より前なら日をまたぐ範囲として扱う。曜日は「開始時刻がその曜日か」で
     * 判定するので、たとえば「金 22:00〜02:00」は土曜の未明まで有効。
     */
    @Serializable
    @SerialName("timeWindow")
    data class TimeWindow(
        val startMinuteOfDay: Int = 8 * 60,
        val endMinuteOfDay: Int = 21 * 60,
        val days: Set<Int> = ALL_DAYS,
    ) : Gate {
        override val key get() = "timeWindow"
        override fun describe(): String =
            "${describeDays(days)}の " +
                "${formatMinuteOfDay(startMinuteOfDay)}〜${formatMinuteOfDay(endMinuteOfDay)} " +
                "のあいだだけ変更できる"

        /** [now] がこの窓の中か。 */
        fun contains(now: LocalDateTime): Boolean {
            val minute = now.hour * 60 + now.minute
            val wraps = startMinuteOfDay > endMinuteOfDay
            val inTime = if (wraps) {
                minute >= startMinuteOfDay || minute < endMinuteOfDay
            } else {
                minute >= startMinuteOfDay && minute < endMinuteOfDay
            }
            if (!inTime) return false

            // 日跨ぎの後半(0:00〜終了)にいるときは、前日が対象曜日かを見る
            val effectiveDay = if (wraps && minute < endMinuteOfDay) {
                now.minusDays(1).dayOfWeek.value
            } else {
                now.dayOfWeek.value
            }
            return effectiveDay in days
        }
    }

    /**
     * カレンダーに特定の予定が入っているあいだだけ変更できる。
     *
     * 「#可変」という予定を自分で入れた時間帯にだけ設定をいじれる、という使い方を想定している。
     * 予定を入れる行為そのものが事前のコミットメントになるので、
     * 衝動的な変更をカレンダー側で先回りして縛れる。
     */
    @Serializable
    @SerialName("calendarWindow")
    data class CalendarWindow(val keyword: String = "#可変") : Gate {
        override val key get() = "calendarWindow"
        override fun describe() = "カレンダーに「$keyword」の予定が入っているあいだだけ変更できる"

        fun isOpen(calendar: CalendarState): Boolean = calendar.inEventMatching(keyword)
    }

    /** なぜ変えたいのかを書かせる。書いた内容は履歴に残る。 */
    @Serializable
    @SerialName("writeReason")
    data class WriteReason(val minLength: Int = 30) : Gate {
        override val key get() = "writeReason"
        override fun describe() = "変更したい理由を${minLength}文字以上書く"
    }
}

/** Gate の通過判定。 */
object GatePolicy {

    /**
     * まだ通過していない Gate を返す。空なら適用できる。
     *
     * Cooldown / TimeWindow / CalendarWindow は状況で自動的に判定されるので、
     * [clearedKeys] に入っていなくても条件を満たしていれば通過扱いになる。
     * 逆に、いったん条件を満たしても外れれば再び塞がる。
     */
    fun remaining(
        gates: List<Gate>,
        clearedKeys: Set<String>,
        createdAt: LocalDateTime,
        now: LocalDateTime,
        calendar: CalendarState = CalendarState.NONE,
    ): List<Gate> = gates.filterNot { gate ->
        when (gate) {
            is Gate.Cooldown ->
                Duration.between(createdAt, now).toMinutes() >= gate.minutes

            is Gate.TimeWindow -> gate.contains(now)

            is Gate.CalendarWindow -> gate.isOpen(calendar)

            else -> gate.key in clearedKeys
        }
    }

    fun isReady(
        gates: List<Gate>,
        clearedKeys: Set<String>,
        createdAt: LocalDateTime,
        now: LocalDateTime,
        calendar: CalendarState = CalendarState.NONE,
    ): Boolean = remaining(gates, clearedKeys, createdAt, now, calendar).isEmpty()
}
