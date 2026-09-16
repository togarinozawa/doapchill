package com.dopachiru.core.engine

/**
 * いま張られている「窓」の中身。[UsageWindows.current] が返す。
 *
 * @param usedSeconds 窓の中で対象を使った合計。
 * @param remainingSeconds 窓が明けるまで。0 なら窓は張られていない。
 */
data class WindowUsage(
    val usedSeconds: Long,
    val remainingSeconds: Long,
) {
    /** 窓が張られているか。明けていれば false。 */
    val open: Boolean get() = remainingSeconds > 0

    /** 使った分。切り捨て。 */
    val usedMinutes: Int get() = (usedSeconds / 60).toInt()

    /**
     * 明けるまでの分。**切り上げる** ── 切り捨てると、残り30秒を「あと0分」と
     * 出したまま開かない時間ができて、壊れているように見える。
     */
    val remainingMinutes: Int get() = ((remainingSeconds + 59) / 60).toInt()

    companion object {
        val NONE = WindowUsage(0, 0)
    }
}

/**
 * 「使い始めた時点から数えて N分。そのうち m分使ったら、残りは通さない」を数える。
 *
 * ## なぜ [UsageSpans] では足りないのか
 *
 * 「n分使ったらm分閉め出す」([UsageSinceBreak][UsageSpans.minutesSinceBreak] + 閉め出し)は、
 * **慣れると出し抜ける**。閾値に届く手前で自分から閉じ、休憩ぶんだけ離れてから
 * 開き直せば、閉め出しに一度も当たらないまま使い続けられる。小賢しくやるほど得をする
 * ── 仕組みが「早めに切り上げる練習」ではなく「タイマーを読む練習」を鍛えてしまう。
 *
 * こちらは**壁時計に釘を打つ**。最初に触った時刻に窓を張り、その窓は閉じても消えない。
 * 窓の中で持ち時間を使い切ったら、窓が明けるまで塞がる。早めに閉じても、
 * 残り時間が返ってくるわけではない ── 出し抜く手が無い。
 *
 * ## 窓の張り直し
 *
 * 窓が明けたあと、次に触った時刻に新しい窓を張る。触らずにいるあいだは窓が無いので、
 * 何時間離れていようと持ち時間は満タンから始まる ── 罰ではなく持ち時間なので、
 * 使っていない人を待たせる理由が無い。
 */
object UsageWindows {

    /**
     * いまの窓を割り出す。
     *
     * @param spans (開始秒, 終了秒) の並び。順番は問わない。開いている最中の区間は
     *   呼ぶ側が [nowSec] まで伸ばしておくこと。
     * @param windowSec 窓の幅(秒)。0 以下なら窓なし。
     * @param nowSec いまの時刻。
     */
    fun current(spans: List<Pair<Long, Long>>, windowSec: Long, nowSec: Long): WindowUsage {
        if (windowSec <= 0) return WindowUsage.NONE
        val sorted = spans.filter { (start, end) -> end > start }.sortedBy { it.first }
        if (sorted.isEmpty()) return WindowUsage.NONE

        // 窓は「使い始め」に張られる。前の窓が明けたあと最初に触った時刻で張り直す
        var anchor = sorted.first().first
        for ((start, _) in sorted) {
            if (start >= anchor + windowSec) anchor = start
        }
        var end = anchor + windowSec

        // 開いたまま窓が明けた場合。張りっぱなしにすると、開き続けている限り
        // 二度と窓が更新されず、持ち時間が無限になる。明けた時点から張り直す
        while (nowSec >= end && sorted.any { it.second > end }) {
            anchor = end
            end = anchor + windowSec
        }

        if (nowSec >= end) return WindowUsage.NONE

        val until = minOf(nowSec, end)
        val used = sorted.sumOf { (start, endOfSpan) ->
            (minOf(endOfSpan, until) - maxOf(start, anchor)).coerceAtLeast(0)
        }
        return WindowUsage(usedSeconds = used, remainingSeconds = end - nowSec)
    }

    /** [current] を分で指定する版。 */
    fun current(spans: List<Pair<Long, Long>>, windowMinutes: Int, nowSec: Long): WindowUsage =
        current(spans, windowMinutes * 60L, nowSec)
}
