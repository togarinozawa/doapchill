package com.dopachiru.core.model

import kotlinx.serialization.Serializable
import kotlin.math.ceil

/** 使い過ぎに気づいたときにすること。いくつでも重ねられる。 */
@Serializable
enum class OveruseReaction(val label: String) {
    /** 画面の上に数秒だけ薄く出す。止めない。 */
    EDGE("画面の端に出す"),

    /** 通知を出す。使っている画面には割り込まない。 */
    NOTIFY("通知を出す"),

    /** 画面を覆って問いかける。少し待てば「続ける」で戻れる。 */
    INTERRUPT("画面に割り込む"),
}

/**
 * ルールを作らなくても効く、使い過ぎの見張り。
 *
 * ## 何のためか
 *
 * ルールは「どれを・どこまで」を先に決めておくもので、**決めていないアプリには
 * 何も起きない**。けれど使い過ぎはたいてい、まだ縛ろうと思っていないアプリで起きる。
 * だから全部のアプリを見張り、**そのアプリのいつもの自分**と比べて長いときに知らせる。
 *
 * ## なぜ固定の目安ではなく平均か
 *
 * 地図の30分と SNS の30分は意味が違う。アプリごとに目安を書かせると、
 * 結局ルールを作るのと同じ手間になる。いつもの長さと比べれば、何も書かずに済む。
 * 履歴の少ないアプリ(入れたばかり)だけは比べる相手がないので、決めた分数を使う。
 */
@Serializable
data class OveruseSettings(
    /** 既定で入。ルールを作る前から効くのがこの見張りの役目なので。反応は既定で端に出すだけ。 */
    val enabled: Boolean = true,

    /** いつもの連続使用の何%を超えたら知らせるか。 */
    val factorPercent: Int = 200,

    /**
     * これより短いうちは知らせない(分)。
     *
     * いつもが2分のアプリで5分使っただけで知らせると、ただうるさい。
     */
    val floorMinutes: Int = 15,

    /** 履歴が少ないアプリの目安(分)。0 なら、履歴がたまるまで見張らない。 */
    val newAppMinutes: Int = 30,

    /** 一度知らせたあと、これだけ続いたらまた知らせる(分)。0 なら一続きに1回だけ。 */
    val repeatMinutes: Int = 15,

    val reactions: Set<OveruseReaction> = setOf(OveruseReaction.EDGE),

    /** 割り込む画面で「続ける」が押せるまでの秒数。 */
    val interruptSeconds: Int = 10,

    /** 見張らないアプリ。地図や音楽など、長く開いていて当然のもの。 */
    val excludePackages: Set<String> = emptySet(),
)

/** そのアプリのいつもの連続使用。 */
data class OveruseBaseline(
    val averageSec: Long,
    /** 平均に使った回数。 */
    val runs: Int,
)

/** 知らせる中身。 */
data class OveruseAlert(
    val currentMinutes: Int,
    val thresholdMinutes: Int,
    /** いつもの長さ(分)。履歴が足りず固定の目安で知らせたなら null。 */
    val averageMinutes: Int?,
) {
    fun message(appLabel: String): String = if (averageMinutes != null) {
        "${appLabel}をいま${currentMinutes}分続けて使っています。いつもは${averageMinutes}分ほどです。"
    } else {
        "${appLabel}をいま${currentMinutes}分続けて使っています。"
    }
}

/** 使い過ぎの判定。端末に触らない純粋な計算だけを置く。 */
object Overuses {

    /** 平均を出すのに要る回数。これより少なければ「履歴が少ないアプリ」。 */
    const val MIN_RUNS = 5

    /** 平均を出す範囲(日)。 */
    const val LOOKBACK_DAYS = 28

    /**
     * これより短い使用は平均に入れない(秒)。
     *
     * 通知を見て閉じただけの数秒が平均を引き下げると、普通に使っただけで
     * 「いつもの倍」になってしまう。
     */
    const val GLANCE_SEC = 60L

    /**
     * 区間のあいだがこれより短ければ、続けて使ったものとみなす(秒)。
     *
     * 通知を引き下ろした・別アプリでコードを確かめた、くらいで「一続き」が
     * 切れると、いつまでも長さが数えられない。
     */
    const val MERGE_GAP_SEC = 60L

    /** 区間を「一続き」にまとめる。返すのは (開始, 終了) の並び。 */
    fun runs(spans: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        val sorted = spans.filter { (s, e) -> e >= s }.sortedBy { it.first }
        val merged = ArrayList<Pair<Long, Long>>()
        for ((start, end) in sorted) {
            val last = merged.lastOrNull()
            if (last != null && start - last.second < MERGE_GAP_SEC) {
                merged[merged.lastIndex] = last.first to maxOf(last.second, end)
            } else {
                merged.add(start to end)
            }
        }
        return merged
    }

    /**
     * いつもの連続使用。回数が足りなければ null。
     *
     * @param beforeSec この時刻より前に終わった一続きだけを使う。いま続いている
     *   一続きを混ぜると、使えば使うほど「いつも」が伸びて知らせが遠のく。
     */
    fun baseline(spans: List<Pair<Long, Long>>, beforeSec: Long): OveruseBaseline? {
        val lengths = runs(spans)
            .filter { (_, end) -> end < beforeSec }
            .map { (start, end) -> end - start }
            .filter { it >= GLANCE_SEC }
        if (lengths.size < MIN_RUNS) return null
        return OveruseBaseline(averageSec = lengths.sum() / lengths.size, runs = lengths.size)
    }

    /**
     * いま続いている一続きの長さ(秒)。最後の区間が [nowSec] から離れていれば 0。
     *
     * @param spans 開いている最中の区間は、終わりを [nowSec] まで伸ばしておくこと。
     */
    fun currentRunSec(spans: List<Pair<Long, Long>>, nowSec: Long): Long {
        val last = runs(spans).lastOrNull() ?: return 0
        if (nowSec - last.second >= MERGE_GAP_SEC) return 0
        return (minOf(last.second, nowSec) - last.first).coerceAtLeast(0)
    }

    /** 知らせる閾値(分)。見張らないなら null。 */
    fun thresholdMinutes(settings: OveruseSettings, baseline: OveruseBaseline?): Int? {
        if (baseline == null) return settings.newAppMinutes.takeIf { it > 0 }
        val scaled = ceil(baseline.averageSec * settings.factorPercent / 100.0 / 60.0).toInt()
        return maxOf(settings.floorMinutes, scaled, 1)
    }

    /**
     * いま知らせるか。
     *
     * @param lastAlertMinutes この一続きで最後に知らせたときの長さ(分)。まだなら null。
     */
    fun check(
        settings: OveruseSettings,
        baseline: OveruseBaseline?,
        currentMinutes: Int,
        lastAlertMinutes: Int?,
    ): OveruseAlert? {
        if (!settings.enabled) return null
        val threshold = thresholdMinutes(settings, baseline) ?: return null
        if (currentMinutes < threshold) return null
        if (lastAlertMinutes != null) {
            if (settings.repeatMinutes <= 0) return null
            if (currentMinutes < lastAlertMinutes + settings.repeatMinutes) return null
        }
        return OveruseAlert(
            currentMinutes = currentMinutes,
            thresholdMinutes = threshold,
            averageMinutes = baseline?.let { ((it.averageSec + 30) / 60).toInt().coerceAtLeast(1) },
        )
    }
}
