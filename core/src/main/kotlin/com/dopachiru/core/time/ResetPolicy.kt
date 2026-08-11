package com.dopachiru.core.time

import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 使用時間・回数の集計をリセットする周期。
 *
 * dopa.md の「一日だけでなく半日とかリセット時間も柔軟にできたら」に対応する。
 *  - periodMinutes=1440, anchorMinuteOfDay=240 → 毎日 4:00 リセット
 *  - periodMinutes=720,  anchorMinuteOfDay=240 → 4:00 と 16:00 の 1日2回リセット
 *
 * アンカーは毎日引き直すので、1440 を割り切れない周期を指定した場合は
 * その日の最後の期間だけが短くなる。挙動が読めるほうを優先している。
 */
@Serializable
data class ResetPolicy(
    val periodMinutes: Int = 24 * 60,
    val anchorMinuteOfDay: Int = 4 * 60,
) {
    init {
        require(periodMinutes in 1..(24 * 60)) { "periodMinutes は 1..1440 の範囲" }
        require(anchorMinuteOfDay in 0 until 24 * 60) { "anchorMinuteOfDay は 0..1439 の範囲" }
    }

    /** now が属する集計期間の開始時刻。 */
    fun periodStart(now: LocalDateTime): LocalDateTime {
        val todayAnchor = now.toLocalDate().atStartOfDay().plusMinutes(anchorMinuteOfDay.toLong())
        val base = if (now.isBefore(todayAnchor)) todayAnchor.minusDays(1) else todayAnchor
        val elapsed = ChronoUnit.MINUTES.between(base, now)
        return base.plusMinutes((elapsed / periodMinutes) * periodMinutes)
    }

    /** now が属する集計期間の終了時刻(次のリセット時刻)。 */
    fun periodEnd(now: LocalDateTime): LocalDateTime {
        val start = periodStart(now)
        val nextAnchor = start.toLocalDate().atStartOfDay()
            .plusMinutes(anchorMinuteOfDay.toLong())
            .let { if (it.isAfter(start)) it else it.plusDays(1) }
        val naturalEnd = start.plusMinutes(periodMinutes.toLong())
        // 日をまたぐ手前でアンカーに切り詰める
        return if (naturalEnd.isAfter(nextAnchor)) nextAnchor else naturalEnd
    }

    fun describe(): String {
        val anchor = formatMinuteOfDay(anchorMinuteOfDay)
        return when (periodMinutes) {
            24 * 60 -> "毎日 $anchor 起点"
            60 -> "1時間ごと($anchor 起点)"
            else -> {
                val h = periodMinutes / 60
                val m = periodMinutes % 60
                val span = buildString {
                    if (h > 0) append("${h}時間")
                    if (m > 0) append("${m}分")
                }
                "${span}ごと($anchor 起点)"
            }
        }
    }
}

/** 0..1439 の「その日の何分目か」を "HH:mm" にする。 */
fun formatMinuteOfDay(minuteOfDay: Int): String {
    val m = ((minuteOfDay % (24 * 60)) + 24 * 60) % (24 * 60)
    return "%02d:%02d".format(m / 60, m % 60)
}
