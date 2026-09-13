package com.dopachiru.core.engine

/**
 * 使用区間の並びから「休憩をはさむまでの使用時間」を数える。
 *
 * 実測の取り方(Android の AccessibilityService か、Windows の前面ウィンドウか)は
 * 端末ごとに違うが、数え方は同じなのでここに1つだけ置いてある。
 * 純粋な計算なので、端末に触らずに試せる。
 */
object UsageSpans {

    /**
     * 直近の空白より後に使った合計(秒)。
     *
     * 空白とは、対象をどれも触っていない [breakSec] 以上の時間。
     * アプリを渡り歩いても切れない ── そこが「連続使用時間」との違いで、
     * X → YouTube → X と回しても、休憩を取るまでは足され続ける。
     *
     * @param spans (開始秒, 終了秒) の並び。順番は問わない。重なりは無い前提
     *   (前面のアプリは常に1つなので、実測では重ならない)。
     * @param nowSec いまの時刻。最後に触ってからここまでが空いていれば 0 を返す。
     */
    fun secondsSinceBreak(spans: List<Pair<Long, Long>>, breakSec: Long, nowSec: Long): Long {
        if (breakSec <= 0) return 0
        val sorted = spans.filter { (start, end) -> end > start }.sortedBy { it.first }
        if (sorted.isEmpty()) return 0

        // いま現に空いているなら、後ろを見るまでもなく数え直し
        if (nowSec - sorted.maxOf { it.second } >= breakSec) return 0

        var total = 0L
        var earliestStart = Long.MAX_VALUE
        for (index in sorted.indices.reversed()) {
            val (start, end) = sorted[index]
            // 1つ後ろの区間との間が空いていれば、そこが休憩。これより前は数えない
            if (earliestStart != Long.MAX_VALUE && earliestStart - end >= breakSec) break
            total += end - start
            if (start < earliestStart) earliestStart = start
        }
        return total
    }

    /** [secondsSinceBreak] を分で。切り捨てる。 */
    fun minutesSinceBreak(spans: List<Pair<Long, Long>>, breakMinutes: Int, nowSec: Long): Int =
        (secondsSinceBreak(spans, breakMinutes * 60L, nowSec) / 60).toInt()
}
