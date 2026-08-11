package com.dopachiru.core.time

/** java.time.DayOfWeek の value に合わせて 月=1 .. 日=7。 */
val ALL_DAYS: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7)

private val LABELS = listOf("月", "火", "水", "木", "金", "土", "日")

fun dayLabel(day: Int): String = LABELS.getOrElse(day - 1) { "?" }

/** 曜日の集合を短い日本語にする。 */
fun describeDays(days: Set<Int>): String {
    val sorted = days.sorted()
    return when {
        sorted.isEmpty() -> "曜日未選択"
        sorted.size == 7 -> "毎日"
        sorted == listOf(1, 2, 3, 4, 5) -> "平日"
        sorted == listOf(6, 7) -> "土日"
        else -> sorted.joinToString("") { dayLabel(it) }
    }
}
