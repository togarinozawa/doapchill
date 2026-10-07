package com.dopachiru.core.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/**
 * のぞきの決まり。「塞がれているものを◯分だけ開ける」を、何回・どの間隔で許すか。
 *
 * ## 何のためか
 *
 * 集中中やブロック中にも、連絡を確かめる・地図を一度見る、のような**一口だけ**用がある。
 * そのたびに集中を切り上げたり押し切ったりすると、出口が重すぎて
 * 「どうせ崩れたなら」と全部やめてしまう。軽い出口を別に置いて、崩さずに済ませる。
 *
 * 切り上げ・押し切りは残す。**のぞきが軽い出口、そちらが重い出口**。
 *
 * ## 回数と間隔を先に決める
 *
 * 長さだけ決めて回数を決めないと、5分を12回並べて1時間になる。予約の型
 * ([ReservationPolicy])と同じで、冷静なうちに3つまとめて決めておく。
 */
@Serializable
data class PeekAllowance(
    /** 1回に開ける長さ(分)。 */
    val minutes: Int = 5,

    /** 区切りの中で使える回数。集中なら1回の集中、ルールなら1日(朝4時区切り)。 */
    val maxCount: Int = 3,

    /** 前回ののぞきが終わってから、これだけあける(分)。 */
    val gapMinutes: Int = 30,

    /**
     * アプリごとに数えるか。false なら対象ぜんぶで1つの財布。
     *
     * 使用時間の「数える単位」と同じ形に揃えてある。
     */
    val perApp: Boolean = false,

    /** のぞくのに要る手間。BlockAction.Effort の値。 */
    val effort: String = "hold",
) {
    fun normalized(): PeekAllowance = copy(
        minutes = minutes.coerceIn(Peeks.MIN_MINUTES, Peeks.MAX_MINUTES),
        maxCount = maxCount.coerceIn(1, Peeks.MAX_COUNT),
        gapMinutes = gapMinutes.coerceIn(0, Peeks.MAX_GAP_MINUTES),
    )

    /** 設定画面に出す1行。 */
    fun describe(per: String): String = buildString {
        append("${minutes}分 / ${per}${maxCount}回まで")
        if (gapMinutes > 0) append(" / ${gapMinutes}分あける")
        append(if (perApp) " / アプリごと" else " / 対象ぜんぶで")
    }
}

/**
 * 実際にのぞいた1回。
 *
 * @param source どの封鎖・どのルールに対するのぞきか([Peeks.focusSource] / [Peeks.ruleSource])。
 *   回数はこの単位で数える ── 別の集中、別のルールの回数は混ぜない。
 */
@Serializable
data class Peek(
    val source: String,
    val packageName: String,
    val startSec: Long,
    val untilSec: Long,
) {
    fun isActiveAt(nowSec: Long): Boolean = nowSec in startSec until untilSec
}

/** のぞけるかの答え。断るときは理由まで返す(予約と同じく、何を待てばいいかが分かるように)。 */
sealed interface PeekCheck {
    /** のぞける。[remainingAfter] は使ったあとに残る回数。 */
    data class Ok(val remainingAfter: Int) : PeekCheck

    data class Refused(val reason: String) : PeekCheck
}

/** のぞきの決まりごと。端末側の保存方法に依存しないようここに置く。 */
object Peeks {
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 30
    const val MAX_COUNT = 20
    const val MAX_GAP_MINUTES = 6 * 60

    /**
     * 記録を残す長さ。ルールの区切りは1日なので、それより長く持つ必要はない。
     * 端末の時計が少しずれても数え漏れないよう、2日ぶん持つ。
     */
    private const val KEEP_SEC = 2L * 24 * 60 * 60

    fun focusSource(lockout: Lockout): String = "focus:" + lockout.uid

    /**
     * uid が空の古いルールは id で代える。id は端末ごとだが、のぞきの記録も
     * 端末ごとなので食い違わない。
     */
    fun ruleSource(rule: Rule): String = "rule:" + rule.uid.ifBlank { rule.id.toString() }

    /** そのアプリについて、いまのぞいている相手。判定はこの相手を飛ばす。 */
    fun activeSources(all: List<Peek>, packageName: String, nowSec: Long): Set<String> =
        all.filter { it.packageName == packageName && it.isActiveAt(nowSec) }
            .mapTo(HashSet()) { it.source }

    /** いまのぞいているもののうち、いちばん遅く終わる時刻。無ければ 0。 */
    fun activeUntil(all: List<Peek>, packageName: String, nowSec: Long): Long =
        all.filter { it.packageName == packageName && it.isActiveAt(nowSec) }
            .maxOfOrNull { it.untilSec } ?: 0L

    /**
     * のぞいてよいか。
     *
     * @param sinceSec 回数を数え始める時刻。集中なら始めた時刻、ルールなら [dayStart]。
     */
    fun check(
        allowance: PeekAllowance,
        all: List<Peek>,
        source: String,
        packageName: String,
        sinceSec: Long,
        nowSec: Long,
    ): PeekCheck {
        val a = allowance.normalized()
        val counted = all.filter {
            it.source == source &&
                it.startSec >= sinceSec &&
                (!a.perApp || it.packageName == packageName)
        }
        if (counted.size >= a.maxCount) {
            return PeekCheck.Refused("のぞけるのは${a.maxCount}回までです。もう使い切りました。")
        }
        val last = counted.maxOfOrNull { it.untilSec }
        if (last != null && a.gapMinutes > 0) {
            val readyAt = last + a.gapMinutes * 60L
            if (nowSec < readyAt) {
                val wait = ((readyAt - nowSec + 59) / 60).toInt()
                return PeekCheck.Refused("次にのぞけるのは、あと${wait}分たってから。")
            }
        }
        return PeekCheck.Ok(remainingAfter = a.maxCount - counted.size - 1)
    }

    fun start(allowance: PeekAllowance, source: String, packageName: String, nowSec: Long): Peek =
        Peek(
            source = source,
            packageName = packageName,
            startSec = nowSec,
            untilSec = nowSec + allowance.normalized().minutes * 60L,
        )

    /** 古い記録を落とす。 */
    fun prune(all: List<Peek>, nowSec: Long): List<Peek> =
        all.filter { it.untilSec >= nowSec - KEEP_SEC }

    /**
     * ルールののぞきを数え始める時刻。**朝4時**で戻る([OneShotLimit] と同じ区切り)。
     *
     * 日付で区切ると、夜更かしした日の0時に回数が戻って、同じ夜に倍のぞける。
     */
    fun dayStart(nowSec: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDateTime()
        val base = if (now.hour < OneShotLimit.DAY_ENDS_AT_HOUR) now.toLocalDate().minusDays(1) else now.toLocalDate()
        return base.atTime(OneShotLimit.DAY_ENDS_AT_HOUR, 0).atZone(zone).toEpochSecond()
    }
}
