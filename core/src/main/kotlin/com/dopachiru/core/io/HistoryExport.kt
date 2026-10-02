package com.dopachiru.core.io

import com.dopachiru.core.action.ActionRegistry
import com.dopachiru.core.model.ConditionTree
import com.dopachiru.core.model.Rule
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 閉じる画面を出した1回。 */
data class BlockEvent(
    val atSec: Long,
    val app: String,
    val ruleName: String,
    val actionId: String,
    /** 押し切って使ってしまったか。 */
    val overridden: Boolean,
    /** 「代わりに何をしたか」。 */
    val note: String,
)

/** 1日ぶんの集計。 */
data class DayEvent(val day: LocalDate, val blocks: Int, val overrides: Int, val screenMinutes: Int)

/** ルールを変える申請。緩める動きが見えるので、AI に渡す価値が高い。 */
data class ChangeEvent(
    val atSec: Long,
    val kind: String,
    val ruleName: String,
    val reason: String,
    val status: String,
)

/** 「開く前に宣言」した持ち時間。 */
data class DeclarationEvent(
    val atSec: Long,
    val app: String,
    val budgetMinutes: Int,
    val usedMinutes: Int,
    val reason: String,
)

/**
 * 使用時間のほかに、**破った記録**をまとめたもの。
 *
 * 使用時間だけでは「何分使ったか」しか言えない。ルールを徐々に強める・弱める助言には、
 * どのルールが何回押し切られたか、緩める申請をしていたか、が要る。
 */
data class HistoryData(
    val blocks: List<BlockEvent> = emptyList(),
    val days: List<DayEvent> = emptyList(),
    val changes: List<ChangeEvent> = emptyList(),
    val declarations: List<DeclarationEvent> = emptyList(),
) {
    val isEmpty: Boolean get() = blocks.isEmpty() && days.isEmpty() && changes.isEmpty() && declarations.isEmpty()
}

/**
 * 書き出しの形式。
 *
 * - [ADVICE]: 読みやすい Markdown に、AI への頼みごとを添える。そのまま貼れば相談になる
 * - [DATA]: 説明も頼みごとも無い、記録だけの JSON。自分で加工する・別の道具に渡す用
 */
enum class ExportFormat(val label: String, val extension: String, val mime: String) {
    ADVICE("AI に相談する用(説明と頼みごと付き)", "md", "text/markdown"),
    DATA("データだけ(JSON)", "json", "application/json"),
}

/** [ExportFormat.DATA] の中身。時刻は端末のローカル時刻の ISO 8601。 */
@Serializable
private data class DataDoc(
    val exportedAt: String,
    val periodDays: Int,
    val device: String,
    val usage: List<UsageRow>,
    val rules: List<RuleRow>,
    val blocks: List<BlockRow>,
    val days: List<DayRow>,
    val changes: List<ChangeRow>,
    val declarations: List<DeclarationRow>,
)

@Serializable
private data class UsageRow(val app: String, val appLabel: String, val start: String, val end: String)

@Serializable
private data class RuleRow(
    val name: String,
    val enabled: Boolean,
    val target: String,
    val condition: String,
    val action: String,
)

@Serializable
private data class BlockRow(
    val at: String,
    val app: String,
    val appLabel: String,
    val rule: String,
    val action: String,
    val overridden: Boolean,
    val note: String,
)

@Serializable
private data class DayRow(val date: String, val blocks: Int, val overrides: Int, val screenMinutes: Int)

@Serializable
private data class ChangeRow(
    val at: String,
    val kind: String,
    val rule: String,
    val reason: String,
    val status: String,
)

@Serializable
private data class DeclarationRow(
    val at: String,
    val app: String,
    val appLabel: String,
    val budgetMinutes: Int,
    val usedMinutes: Int,
    val reason: String,
)

object HistoryExport {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /** ルールの要約。呼び出し側が文字にして渡す。 */
    data class RuleSummary(
        val name: String,
        val enabled: Boolean,
        val target: String,
        val condition: String,
        val action: String,
    )

    /** 組ごとに1件。対象・条件・動作を、Markdown の表と同じ言い回しで文字にする。 */
    fun summarize(rules: List<Rule>): List<RuleSummary> = rules.flatMap { rule ->
        val target = buildList {
            if (rule.target.matchAll) add("全部")
            addAll(rule.target.packages)
            rule.target.tags.forEach { add("#" + it) }
            addAll(rule.target.sites)
        }.joinToString("・").ifBlank { "(空)" }
        rule.clauses.map { clause ->
            val spec = clause.mainAction
            val action = spec?.let { ActionRegistry[it.actionId]?.summarize(it.params) ?: it.actionId } ?: "(なし)"
            RuleSummary(rule.name, rule.enabled, target, ConditionTree.describe(clause.condition), action)
        }
    }

    fun buildData(
        spans: List<UsageSpan>,
        labelOf: (String) -> String,
        rules: List<RuleSummary>,
        history: HistoryData,
        now: LocalDateTime,
        days: Int,
        deviceName: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        fun at(sec: Long): String = Instant.ofEpochSecond(sec).atZone(zone).toLocalDateTime().toString()
        val fromSec = now.atZone(zone).toEpochSecond() - days * 24L * 3600
        val doc = DataDoc(
            exportedAt = now.toString(),
            periodDays = days,
            device = deviceName,
            usage = spans.filter { it.endSec > fromSec }
                .map { UsageRow(it.app, labelOf(it.app), at(maxOf(it.startSec, fromSec)), at(it.endSec)) },
            rules = rules.map { RuleRow(it.name, it.enabled, it.target, it.condition, it.action) },
            blocks = history.blocks.map {
                BlockRow(at(it.atSec), it.app, labelOf(it.app), it.ruleName, it.actionId, it.overridden, it.note)
            },
            days = history.days.map { DayRow(it.day.toString(), it.blocks, it.overrides, it.screenMinutes) },
            changes = history.changes.map { ChangeRow(at(it.atSec), it.kind, it.ruleName, it.reason, it.status) },
            declarations = history.declarations.map {
                DeclarationRow(at(it.atSec), it.app, labelOf(it.app), it.budgetMinutes, it.usedMinutes, it.reason)
            },
        )
        return json.encodeToString(doc)
    }

    /** 閉じた記録の一覧に出す件数。長すぎると肝心の集計が埋もれる。 */
    private const val RECENT_BLOCKS = 40

    /**
     * 履歴の節。[UsageReport.build] が、使用時間の表のあとに差し込む。
     *
     * 先に集計(ルールごとの押し切り率・押し切りやすい時間帯)を出し、個別の記録は
     * そのあとに最近のぶんだけ置く。AI は表から傾向を読み、メモは理由の裏づけに使う。
     */
    internal fun appendHistory(
        out: StringBuilder,
        history: HistoryData,
        labelOf: (String) -> String,
        zone: ZoneId,
    ) {
        if (history.isEmpty) return

        if (history.blocks.isNotEmpty()) {
            out.appendLine("## ルールごとの押し切り")
            out.appendLine()
            out.appendLine("押し切り率が高いルールは強すぎる(または的外れ)、低いのに回数が多いルールは効いています。")
            out.appendLine()
            out.appendLine("| ルール | 閉じた回数 | 押し切り | 押し切り率 |")
            out.appendLine("|---|---|---|---|")
            history.blocks.groupBy { it.ruleName }
                .entries.sortedByDescending { (_, v) -> v.size }
                .forEach { (rule, list) ->
                    val over = list.count { it.overridden }
                    out.appendLine("| ${cellText(rule)} | ${list.size}回 | ${over}回 | ${over * 100 / list.size}% |")
                }
            out.appendLine()

            val overrides = history.blocks.filter { it.overridden }
            if (overrides.isNotEmpty()) {
                val perHour = IntArray(24)
                overrides.forEach { perHour[Instant.ofEpochSecond(it.atSec).atZone(zone).hour]++ }
                out.appendLine("## 押し切りの時間帯(回数)")
                out.appendLine()
                out.appendLine("| " + (0..23).joinToString(" | ") { it.toString().padStart(2, '0') } + " |")
                out.appendLine("|" + "---|".repeat(24))
                out.appendLine("| " + perHour.joinToString(" | ") { if (it == 0) "" else it.toString() } + " |")
                out.appendLine()
            }

            out.appendLine("## 最近の閉じた記録(新しい順、最大${RECENT_BLOCKS}件)")
            out.appendLine()
            out.appendLine("| 日時 | アプリ | ルール | 結果 | 代わりにしたこと |")
            out.appendLine("|---|---|---|---|---|")
            history.blocks.sortedByDescending { it.atSec }.take(RECENT_BLOCKS).forEach {
                val t = Instant.ofEpochSecond(it.atSec).atZone(zone).toLocalDateTime()
                out.appendLine(
                    "| ${t.toLocalDate()} ${t.toLocalTime().withSecond(0).withNano(0)} | ${labelOf(it.app)} | " +
                        "${cellText(it.ruleName)} | ${if (it.overridden) "押し切った" else "やめた"} | ${cellText(it.note)} |",
                )
            }
            out.appendLine()
        }

        if (history.days.isNotEmpty()) {
            out.appendLine("## 日ごと")
            out.appendLine()
            out.appendLine("押し切りが0回の日が「守れた日」です。")
            out.appendLine()
            out.appendLine("| 日 | 閉じた | 押し切り | 画面を見た時間 |")
            out.appendLine("|---|---|---|---|")
            history.days.sortedBy { it.day }.forEach {
                out.appendLine("| ${it.day} | ${it.blocks}回 | ${it.overrides}回 | ${UsageReport.hm(it.screenMinutes * 60L)} |")
            }
            out.appendLine()
        }

        if (history.changes.isNotEmpty()) {
            out.appendLine("## ルールを変えた申請")
            out.appendLine()
            out.appendLine("緩める申請が続いていないか、見てください。")
            out.appendLine()
            out.appendLine("| 日 | 種類 | ルール | 理由 | 状態 |")
            out.appendLine("|---|---|---|---|---|")
            history.changes.sortedByDescending { it.atSec }.forEach {
                val d = Instant.ofEpochSecond(it.atSec).atZone(zone).toLocalDate()
                out.appendLine("| $d | ${it.kind} | ${cellText(it.ruleName)} | ${cellText(it.reason)} | ${it.status} |")
            }
            out.appendLine()
        }

        if (history.declarations.isNotEmpty()) {
            out.appendLine("## 開く前の宣言")
            out.appendLine()
            out.appendLine("宣言した分数と、実際に使った分数の差が、見積もりの甘さです。")
            out.appendLine()
            out.appendLine("| 日 | アプリ | 宣言 | 実際 | 理由 |")
            out.appendLine("|---|---|---|---|---|")
            history.declarations.sortedByDescending { it.atSec }.forEach {
                val d = Instant.ofEpochSecond(it.atSec).atZone(zone).toLocalDate()
                out.appendLine("| $d | ${labelOf(it.app)} | ${it.budgetMinutes}分 | ${it.usedMinutes}分 | ${cellText(it.reason)} |")
            }
            out.appendLine()
        }
    }

    /** 表の1マスに入れる自由記述。改行と縦棒は表を壊すので潰す。 */
    private fun cellText(s: String): String = s.replace("|", "/").replace(Regex("\\s*\\R\\s*"), " ").trim()
}
