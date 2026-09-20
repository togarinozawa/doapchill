package com.dopachiru.core.io

import com.dopachiru.core.action.ActionRegistry
import com.dopachiru.core.model.ConditionTree
import com.dopachiru.core.model.Rule
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

/** 1回ぶんの使用。端末ごとの記録の持ち方に依存しないよう、ここでは素の値で受ける。 */
data class UsageSpan(val app: String, val startSec: Long, val endSec: Long)

/**
 * 使用実績を、人にも Claude にも読める1枚の Markdown にする。
 *
 * ## 何のためか
 *
 * 「使用状況をファイルにして Claude に食わせて、最適なルールを作ってほしい」
 * ── そのための書き出し。こちらが読むものなので機械向けの JSON でもよかったが、
 * **Markdown にしてある**。渡す前に本人が中身を目で確かめられるほうがいい
 * ── 何時に何を使ったかは、そこそこ個人的な記録なので。
 *
 * ## 何時に使ったかが要る
 *
 * 合計だけ見ても「1日2時間」としか言えない。**時間帯の山**が見えて初めて
 * 「22時から伸びる」「昼休みの直後に開き直している」のような、条件に落とせる話になる。
 * だから時間帯の表が中心にある。
 *
 * ## いまのルールも一緒に出す
 *
 * 既にあるものを知らずに提案すると、同じルールをもう1本足すことになる。
 */
object UsageReport {

    /** 表に出すアプリの数。全部出すと読む気が失せる。 */
    private const val TOP_APPS = 12

    /** 時間帯の表に出すアプリの数。横に24列あるので、これ以上は読めない。 */
    private const val TOP_APPS_HOURLY = 8

    private val WEEKDAYS = listOf("月", "火", "水", "木", "金", "土", "日")

    fun build(
        spans: List<UsageSpan>,
        labelOf: (String) -> String,
        rules: List<Rule>,
        tags: Map<String, Set<String>>,
        now: LocalDateTime,
        days: Int,
        deviceName: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val nowSec = now.atZone(zone).toEpochSecond()
        val fromSec = nowSec - days * 24L * 3600
        val clipped = spans.mapNotNull { clip(it, fromSec, nowSec) }

        val out = StringBuilder()
        out.appendLine("# ドパチル 使用状況")
        out.appendLine()
        out.appendLine("- 端末: " + deviceName.ifBlank { "(名前なし)" })
        out.appendLine(
            "- 期間: 直近 " + days + " 日(" +
                now.toLocalDate().minusDays(days.toLong()) + " 〜 " + now.toLocalDate() + ")",
        )
        out.appendLine("- 書き出し: " + now)
        out.appendLine()
        out.appendLine("> **これは個人の記録です。** 公開リポジトリや不特定の相手に渡さないでください。")
        out.appendLine()

        if (clipped.isEmpty()) {
            out.appendLine("記録がありません。しばらく使ってから書き出してください。")
            return out.toString()
        }

        appendTotals(out, clipped, labelOf, days)
        appendHourly(out, clipped, labelOf, zone)
        appendWeekly(out, clipped, labelOf, zone)
        appendRules(out, rules, tags)
        appendAsk(out)
        return out.toString()
    }

    /** 期間の外にはみ出した部分を落とす。落としきったら null。 */
    private fun clip(span: UsageSpan, fromSec: Long, toSec: Long): UsageSpan? {
        val start = maxOf(span.startSec, fromSec)
        val end = minOf(span.endSec, toSec)
        return if (end > start) UsageSpan(span.app, start, end) else null
    }

    // ---- アプリ別 ------------------------------------------------------

    private fun appendTotals(
        out: StringBuilder,
        spans: List<UsageSpan>,
        labelOf: (String) -> String,
        days: Int,
    ) {
        val byApp = spans.groupBy { it.app }
        val ranked = byApp.entries
            .map { (app, list) -> app to list.sumOf { it.endSec - it.startSec } }
            .sortedByDescending { it.second }

        out.appendLine("## アプリ別")
        out.appendLine()
        out.appendLine("| アプリ | 合計 | 1日平均 | 開いた回数 | 最長の一続き | 1回の平均 |")
        out.appendLine("|---|---|---|---|---|---|")
        for ((app, totalSec) in ranked.take(TOP_APPS)) {
            val list = byApp.getValue(app)
            val longest = list.maxOf { it.endSec - it.startSec }
            out.appendLine(
                "| " + labelOf(app) + " | " + hm(totalSec) + " | " + hm(totalSec / days) +
                    " | " + list.size + "回 | " + hm(longest) + " | " + hm(totalSec / list.size) + " |",
            )
        }
        if (ranked.size > TOP_APPS) {
            val rest = ranked.drop(TOP_APPS)
            out.appendLine("| ほか" + rest.size + "個 | " + hm(rest.sumOf { it.second }) + " | | | | |")
        }
        out.appendLine()
    }

    // ---- 時間帯 --------------------------------------------------------

    /**
     * 時間帯ごとの合計。
     *
     * 区間が時をまたぐので、**またいだぶんを切って両方に足す**。
     * 開始時刻だけで数えると、22:40 から1時間使ったぶんが全部22時に載り、
     * 深夜の山が消える ── いちばん見たい形が見えなくなる。
     */
    private fun appendHourly(
        out: StringBuilder,
        spans: List<UsageSpan>,
        labelOf: (String) -> String,
        zone: ZoneId,
    ) {
        out.appendLine("## 時間帯(分)")
        out.appendLine()
        out.appendLine("合計だけでは条件に落とせません。**山が出ている時間**が、そのまま時間帯ルールの候補です。")
        out.appendLine()

        val header = (0..23).joinToString(" | ") { it.toString().padStart(2, '0') }
        out.appendLine("| アプリ | " + header + " |")
        out.appendLine("|---|" + "---|".repeat(24))

        out.appendLine("| **全部** | " + hourlyOf(spans, zone).joinToString(" | ") { cell(it) } + " |")

        for (app in topApps(spans, TOP_APPS_HOURLY)) {
            val hours = hourlyOf(spans.filter { it.app == app }, zone)
            out.appendLine("| " + labelOf(app) + " | " + hours.joinToString(" | ") { cell(it) } + " |")
        }
        out.appendLine()
    }

    /** 0時から23時までの、分の配列。 */
    internal fun hourlyOf(spans: List<UsageSpan>, zone: ZoneId): IntArray {
        val seconds = LongArray(24)
        for (span in spans) {
            var cursor = span.startSec
            while (cursor < span.endSec) {
                val at = Instant.ofEpochSecond(cursor).atZone(zone)
                // この時のおしまい。またいだら、そこで切って次の時へ渡す
                val hourEnd = at.withMinute(0).withSecond(0).withNano(0).plusHours(1).toEpochSecond()
                val until = minOf(hourEnd, span.endSec)
                seconds[at.hour] += until - cursor
                cursor = until
            }
        }
        return IntArray(24) { (seconds[it] / 60.0).roundToInt() }
    }

    /** 0 は空欄にする。0 が24個並ぶと、山が読み取れない。 */
    private fun cell(minutes: Int): String = if (minutes == 0) "" else minutes.toString()

    private fun topApps(spans: List<UsageSpan>, count: Int): List<String> =
        spans.groupBy { it.app }
            .entries.map { (app, list) -> app to list.sumOf { it.endSec - it.startSec } }
            .sortedByDescending { it.second }
            .take(count)
            .map { it.first }

    // ---- 曜日 ----------------------------------------------------------

    private fun appendWeekly(
        out: StringBuilder,
        spans: List<UsageSpan>,
        labelOf: (String) -> String,
        zone: ZoneId,
    ) {
        out.appendLine("## 曜日(分)")
        out.appendLine()
        out.appendLine("| アプリ | " + WEEKDAYS.joinToString(" | ") + " |")
        out.appendLine("|---|" + "---|".repeat(7))
        out.appendLine("| **全部** | " + weeklyOf(spans, zone).joinToString(" | ") { cell(it) } + " |")

        for (app in topApps(spans, TOP_APPS_HOURLY)) {
            val week = weeklyOf(spans.filter { it.app == app }, zone)
            out.appendLine("| " + labelOf(app) + " | " + week.joinToString(" | ") { cell(it) } + " |")
        }
        out.appendLine()
    }

    /** 月曜から日曜までの、分の配列。日をまたぐぶんも切って足す。 */
    internal fun weeklyOf(spans: List<UsageSpan>, zone: ZoneId): IntArray {
        val seconds = LongArray(7)
        for (span in spans) {
            var cursor = span.startSec
            while (cursor < span.endSec) {
                val at = Instant.ofEpochSecond(cursor).atZone(zone)
                val dayEnd = at.toLocalDate().plusDays(1).atStartOfDay(zone).toEpochSecond()
                val until = minOf(dayEnd, span.endSec)
                seconds[at.dayOfWeek.value - 1] += until - cursor
                cursor = until
            }
        }
        return IntArray(7) { (seconds[it] / 60.0).roundToInt() }
    }

    // ---- いまの決まりごと ----------------------------------------------

    private fun appendRules(out: StringBuilder, rules: List<Rule>, tags: Map<String, Set<String>>) {
        out.appendLine("## いまのルール")
        out.appendLine()
        if (rules.isEmpty()) {
            out.appendLine("まだ1本もありません。")
        } else {
            out.appendLine("| 名前 | 対象 | 条件 | 動作 | 有効 |")
            out.appendLine("|---|---|---|---|---|")
            for (rule in rules) {
                val target = buildList {
                    if (rule.target.matchAll) add("全部")
                    addAll(rule.target.packages)
                    rule.target.tags.forEach { add("#" + it) }
                    addAll(rule.target.sites)
                }.joinToString("・").ifBlank { "(空)" }
                // 組ごとに1行。1組しか無ければ今までと同じ見た目になる
                for (clause in rule.clauses) {
                    val spec = clause.mainAction
                    val action = spec?.let {
                        ActionRegistry[it.actionId]?.summarize(it.params) ?: it.actionId
                    } ?: "(なし)"
                    out.appendLine(
                        "| " + rule.name + " | " + target + " | " +
                            ConditionTree.describe(clause.condition) + " | " + action + " | " +
                            (if (rule.enabled) "はい" else "いいえ") + " |",
                    )
                }
            }
        }
        out.appendLine()

        if (tags.isNotEmpty()) {
            out.appendLine("### タグ")
            out.appendLine()
            val byTag = HashMap<String, MutableList<String>>()
            tags.forEach { (app, set) ->
                set.forEach { byTag.getOrPut(it) { mutableListOf() }.add(app) }
            }
            byTag.toSortedMap().forEach { (tag, apps) ->
                out.appendLine("- **" + tag + "**: " + apps.sorted().joinToString("、"))
            }
            out.appendLine()
        }
    }

    /** 渡した相手(Claude)への注文。**人が書き足さなくて済むように**ここに入れておく。 */
    private fun appendAsk(out: StringBuilder) {
        out.appendLine("---")
        out.appendLine()
        out.appendLine("## Claude へ")
        out.appendLine()
        out.appendLine("この記録から、ドパチルのルール案を作ってください。お願いしたいこと:")
        out.appendLine()
        out.appendLine("1. **時間帯の山と、1回の長さ**を根拠にすること。合計だけで決めない")
        out.appendLine("2. 上の「いまのルール」と**重ならない**こと。既にあるものは作り直さない")
        out.appendLine("3. いきなり強くしないこと。**短く始めて足せる**形にする")
        out.appendLine("4. 「なぜこの数字なのか」を1行ずつ添えること")
        out.appendLine()
        out.appendLine(
            "取り込める形(`.rules`)で出してもらえれば、ルールタブの「取り込む」から入れられます。" +
                "使える条件と動作の目録は、ルールの書き出しに入っています。",
        )
        out.appendLine()
    }

    // ---- 小物 ----------------------------------------------------------

    /** 秒を「1時間20分」の形に。 */
    internal fun hm(seconds: Long): String {
        val minutes = (seconds / 60.0).roundToInt()
        if (minutes < 60) return minutes.toString() + "分"
        return (minutes / 60).toString() + "時間" +
            if (minutes % 60 == 0) "" else (minutes % 60).toString() + "分"
    }
}
