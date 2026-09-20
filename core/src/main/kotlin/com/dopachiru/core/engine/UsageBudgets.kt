package com.dopachiru.core.engine

import com.dopachiru.core.time.ResetPolicy
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 何をひとまとめに数えるか。
 *
 * 揃えるまで、ここは条件ごとにバラバラでした ── 「休憩をはさむまで」と
 * 「使い始めてからの持ち時間」はルールの対象をまとめて数え、「合計使用時間」は
 * **前面のアプリ1つだけ**を数えていた。同じ画面に並んでいて数え方が違うのは、
 * 気づけないほうの罠です。選べる形にして表に出しました。
 */
@Serializable
enum class CountBy(val label: String, val help: String) {
    /** ルールの対象ぜんぶで1つの財布。タグで括っていればグループ合計。 */
    GROUP("対象ぜんぶで合計", "SNS を渡り歩いても財布は1つ"),

    /** アプリごとに別の財布。 */
    APP("アプリごとに別々", "YouTube に2時間、X に2時間、と別々に数える"),
}

/**
 * 数えた分をどこでリセットするか。
 *
 * **ここが「使いすぎを止める」の本体**です。何分使ったら閉めるかより、
 * どこで数え直すかのほうが挙動を決めます。
 */
@Serializable
enum class BudgetReset(val label: String, val help: String) {
    /**
     * 対象をどれも触っていない時間が続いたら数え直す。
     *
     * いちばん素直だが、**閾値の手前で自分から閉じれば当たらない**。
     * 慣れると「タイマーを読む練習」になります。
     */
    AWAY("連続で離れたら", "対象をどれも触らない時間が続いたら、そこから数え直す"),

    /**
     * 最初に触った時刻に窓を張り、窓が明けるまで数え直さない。
     *
     * 閉じても窓は消えないので、ギリギリで閉じて数え直させる手が効きません。
     * 「2時間使ったら1時間休憩」は、幅3時間の窓に持ち時間2時間と同じこと。
     */
    WINDOW("使い始めてからの窓", "最初に触った時刻から数え、窓が明けるまで数え直さない"),

    /**
     * カレンダーを等間隔に切って、その区切りの中で数える。
     *
     * 「1日に2時間まで」「半日ごとに1時間まで」。
     */
    PERIOD("決まった区切りで", "毎日4時起点、半日ごと、のように区切って数える"),
}

/** 数えかたの指定。端末側が実測から答えを作るために要る。 */
data class BudgetQuery(
    val ruleId: Long,
    /** ルールの中のどの組か。**組ごとに別の財布**にするための鍵。 */
    val clauseId: Int,
    /** いま前面のアプリ。[CountBy.APP] のときはこれだけ数える。 */
    val packageName: String,
    val countBy: CountBy,
    val reset: BudgetReset,
    /** [BudgetReset.AWAY] のとき、これだけ離れたら数え直す。 */
    val awayMinutes: Int = 30,
    /** [BudgetReset.WINDOW] のとき、窓の幅。 */
    val windowMinutes: Int = 180,
    /** [BudgetReset.PERIOD] のとき、区切りかた。 */
    val period: ResetPolicy = ResetPolicy(),
)

/**
 * 使った分とリセットまでの時間を、3つのリセットのしかたで同じ形に揃えて出す。
 *
 * **計算はここに1つだけ置いてあります。** Android と Windows で実測の取り方は
 * 違いますが、区間の並びに落とせば数え方は同じです。端末側は区間を集めるところ
 * までをやり、あとはここに渡します。
 */
object UsageBudgets {

    /**
     * @param spans (開始秒, 終了秒) の並び。重なりは無い前提(前面は常に1つ)。
     *   端末をまたいで合算するときは、先に和集合を取ってから渡すこと。
     */
    fun compute(
        query: BudgetQuery,
        spans: List<Pair<Long, Long>>,
        now: LocalDateTime,
        nowSec: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): WindowUsage = when (query.reset) {
        BudgetReset.AWAY -> away(spans, query.awayMinutes, nowSec)
        BudgetReset.WINDOW -> UsageWindows.current(spans, query.windowMinutes, nowSec)
        BudgetReset.PERIOD -> period(spans, query.period, now, nowSec, zone)
    }

    /**
     * 直近の空白より後に使った分。
     *
     * 残りは「いま離れ続けたら、あと何分で数え直しになるか」。数えているものが
     * 無ければ 0 ── 待つ必要が無いので。
     */
    private fun away(spans: List<Pair<Long, Long>>, awayMinutes: Int, nowSec: Long): WindowUsage {
        val used = UsageSpans.secondsSinceBreak(spans, awayMinutes * 60L, nowSec)
        if (used <= 0L) return WindowUsage.NONE
        val lastEnd = spans.filter { it.second > it.first }.maxOfOrNull { it.second } ?: nowSec
        val idle = (nowSec - lastEnd).coerceAtLeast(0L)
        return WindowUsage(
            usedSeconds = used,
            remainingSeconds = (awayMinutes * 60L - idle).coerceAtLeast(0L),
        )
    }

    /**
     * いまの区切りの中で使った分。
     *
     * 区切りの手前にはみ出した区間は**はみ出したぶんだけ**落とします。
     * 丸ごと落とすと、区切りをまたいで使い続けたときに直前の分が消えます。
     */
    private fun period(
        spans: List<Pair<Long, Long>>,
        policy: ResetPolicy,
        now: LocalDateTime,
        nowSec: Long,
        zone: ZoneId,
    ): WindowUsage {
        val startSec = policy.periodStart(now).atZone(zone).toEpochSecond()
        val endSec = policy.periodEnd(now).atZone(zone).toEpochSecond()

        var used = 0L
        for ((start, end) in spans) {
            if (end <= start) continue
            val from = maxOf(start, startSec)
            val to = minOf(end, nowSec)
            if (to > from) used += to - from
        }
        return WindowUsage(
            usedSeconds = used,
            remainingSeconds = (endSec - nowSec).coerceAtLeast(0L),
        )
    }
}
