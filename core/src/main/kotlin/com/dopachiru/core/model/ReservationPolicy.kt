package com.dopachiru.core.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/**
 * 予約できる枠の型。「何を・どれくらい・どの間隔で予約してよいか」を先に決めておく。
 *
 * ## 予約は「例外」であって「予定」ではない
 *
 * 予約が効くのは、**本来ダメな時間の中**でだけです。時間帯ルールや常時ブロックで
 * 塞いである相手に対して、「この枠だけは通す」と穴を開けるのが予約の役目
 * ([com.dopachiru.core.condition.types.ReservationCondition] と組む)。
 *
 * 塞いでいない相手を予約しても何も起きません ── いつでも開くので。
 * だから枠の型を作るときは、**先に塞ぐルールがあること**が前提になります。
 *
 * ## なぜ型を先に決めるのか
 *
 * 好きなアプリを好きなだけ予約できるなら、それは制限ではありません。
 * 欲しくなってから条件を決めると、欲しい側に有利な条件になります。
 *
 * 型は**冷静なうちに1回だけ**決める。予約するときは、その型から選ぶだけ
 * ── 選ぶ瞬間には、長さも間隔も回数もすでに決まっています。
 */
@Serializable
data class ReservationPolicy(
    val id: String,
    /** 画面に出す名前。「YouTube を見る」「ゲームをする」。 */
    val label: String,
    /** 何を通す枠か。塞いでいるルールの対象と揃える。 */
    val target: Target,

    /**
     * どのルールの穴を開ける枠か([Rule.uid])。
     *
     * 対象を手で選ばせるのをやめて**ルールから作る**ようにしたときに足した。
     * 手で選ばせると、塞いでいない相手の枠を作れてしまい、取っても何も起きない。
     * 空なら、この欄より前に手で作った型。
     */
    val ruleUid: String = "",

    /**
     * いまからこれだけ先にしか置けない(分)。
     *
     * 直前予約を封じるための待ち。0 にすると「いま開きたいから今すぐ予約」ができて、
     * 冷静な決定という予約の意味が消える。
     */
    val minLeadMinutes: Int = 60,

    /** 1回の枠の最大の長さ(分)。 */
    val maxDurationMinutes: Int = 60,

    /**
     * 枠と枠のあいだ、これだけ空ける(分)。
     *
     * これが無いと、最大の長さを数珠つなぎに並べて一日中使えてしまう。
     * 長さの上限とセットで初めて上限になる。
     */
    val minGapMinutes: Int = 180,

    /** 1日に取れる数。0 なら無制限。 */
    val maxPerDay: Int = 0,

    val enabled: Boolean = true,
) {
    /** 設定画面に出す1行。 */
    fun describe(): String = buildString {
        append(hours(minLeadMinutes) + "前から / ")
        append("1回" + hours(maxDurationMinutes) + "まで / ")
        append("間隔" + hours(minGapMinutes))
        if (maxPerDay > 0) append(" / 1日" + maxPerDay + "回まで")
    }

    private fun hours(minutes: Int): String =
        if (minutes >= 60 && minutes % 60 == 0) (minutes / 60).toString() + "時間"
        else if (minutes >= 60) (minutes / 60).toString() + "時間" + (minutes % 60) + "分"
        else minutes.toString() + "分"
}

/** 予約を取ってよいかの答え。断るときは**理由まで**返す。 */
sealed interface BookingCheck {
    data object Ok : BookingCheck

    /** 断った。[reason] はそのまま画面に出す文。 */
    data class Refused(val reason: String) : BookingCheck
}

/** 予約の決まりごと。端末側の保存方法に依存しないようここに置く。 */
object ReservationRules {
    /**
     * いまからこれだけ先の時刻からしか予約できない(既定)。
     *
     * 直前予約を封じるための最短の待ち。ここを 0 にすると「今すぐ予約」ができて、
     * 冷静な決定という予約の意味が消える。型ごとに変えられる。
     */
    const val MIN_LEAD_MINUTES = 60

    /** 予約の最短の長さ。 */
    const val MIN_DURATION_MINUTES = 5

    /** 予約の最長の長さ。これ以上はルールで決めるべきもの。 */
    const val MAX_DURATION_MINUTES = 8 * 60

    /**
     * その枠を取ってよいか。
     *
     * **断る理由を1つずつ返す。** まとめて「取れません」とだけ出すと、
     * 何を直せばいいのか分からないまま時刻をいじることになる。
     *
     * @param existing その型で既に取ってある枠。終わったものは呼ぶ側が落としておく。
     */
    fun check(
        policy: ReservationPolicy,
        existing: List<Reservation>,
        startSec: Long,
        endSec: Long,
        nowSec: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): BookingCheck {
        if (!policy.enabled) return BookingCheck.Refused("この枠はいま止めてあります。")

        val minutes = ((endSec - startSec) / 60).toInt()
        if (minutes < MIN_DURATION_MINUTES) {
            return BookingCheck.Refused("短すぎます。${MIN_DURATION_MINUTES}分から。")
        }
        if (minutes > policy.maxDurationMinutes) {
            return BookingCheck.Refused(
                "1回は${policy.maxDurationMinutes}分までです(いま${minutes}分)。",
            )
        }

        val earliest = nowSec + policy.minLeadMinutes * 60L
        if (startSec < earliest) {
            val short = ((earliest - startSec) / 60).toInt() + 1
            return BookingCheck.Refused(
                "直前すぎます。${policy.minLeadMinutes}分より先にしてください(あと${short}分ぶん後ろへ)。",
            )
        }

        // 重なりと間隔。間隔が無いと、上限の長さを数珠つなぎにして一日中使える
        val gapSec = policy.minGapMinutes * 60L
        for (other in existing) {
            val overlaps = startSec < other.endEpochSec && other.startEpochSec < endSec
            if (overlaps) return BookingCheck.Refused("すでに取ってある枠と重なっています。")
            val gap =
                if (startSec >= other.endEpochSec) startSec - other.endEpochSec
                else other.startEpochSec - endSec
            if (gap < gapSec) {
                return BookingCheck.Refused(
                    "前後の枠と${policy.minGapMinutes}分あけてください(いま${gap / 60}分)。",
                )
            }
        }

        if (policy.maxPerDay > 0) {
            val date = Instant.ofEpochSecond(startSec).atZone(zone).toLocalDate()
            val sameDay = existing.count {
                Instant.ofEpochSecond(it.startEpochSec).atZone(zone).toLocalDate() == date
            }
            if (sameDay >= policy.maxPerDay) {
                return BookingCheck.Refused("その日はもう${policy.maxPerDay}回取ってあります。")
            }
        }

        return BookingCheck.Ok
    }

    /**
     * その型で既に取ってある枠だけを拾う。終わったものは落とす。
     *
     * 型を消して作り直しても古い枠が残るので、**対象が重なるもの**も見る
     * ── id だけで見ると、同じアプリの枠が間隔の判定から漏れる。
     */
    fun bookedUnder(
        policy: ReservationPolicy,
        all: List<Reservation>,
        nowSec: Long,
    ): List<Reservation> = all.filter {
        !it.isPastAt(nowSec) &&
            (it.policyId == policy.id || RuleOverlap.overlaps(it.target, policy.target))
    }

    /** そのアプリを塞ぐルールがあるか。無ければ予約しても意味がない。 */
    fun isGuarded(policy: ReservationPolicy, rules: List<Rule>): Boolean =
        rules.any { it.enabled && RuleOverlap.overlaps(it.target, policy.target) }

    // ---- ルールから枠を出す --------------------------------------------

    /**
     * 予約すれば使えるようになるルールか。
     *
     * **塞いでいるだけでは足りません。** 予約で開くのは
     * [com.dopachiru.core.condition.types.ReservationCondition]
     * (「予約した時間の外」)を条件に持つルールだけ ── 持っていないルールは、
     * 枠を取っても素通りせず、取った本人が「効かない」と思うことになります。
     *
     * ここを対象の重なりで判定していたのが元の [isGuarded] で、あれは
     * 「塞ぐルールがあるか」しか見ていませんでした。並べる相手はこちらが正しい。
     */
    fun unlocksByReservation(rule: Rule): Boolean =
        rule.enabled && mentionsReservation(rule.condition)

    private fun mentionsReservation(node: ConditionNode): Boolean = when (node) {
        is ConditionNode.Leaf -> node.typeId == RESERVATION_CONDITION_ID
        is ConditionNode.AllOf -> node.children.any { mentionsReservation(it) }
        is ConditionNode.AnyOf -> node.children.any { mentionsReservation(it) }
        is ConditionNode.Not -> mentionsReservation(node.child)
    }

    /**
     * 条件の ID を直に書いてあるのは、[ReservationPolicy] が置いてある model から
     * condition を参照すると、model → condition → model の輪ができるため。
     * 変えるときは [com.dopachiru.core.condition.types.ReservationCondition] と揃えること。
     */
    private const val RESERVATION_CONDITION_ID = "outside_reservation"

    /**
     * その端末で、予約すれば使えるようになるルール。
     *
     * @param deviceId 空なら端末で絞らない(どの端末のぶんも出す)。
     */
    fun unlockableOn(rules: List<Rule>, deviceId: String): List<Rule> = rules.filter {
        unlocksByReservation(it) && (deviceId.isBlank() || it.appliesToDevice(deviceId))
    }

    /**
     * ルールに紐づく型を引く。無ければ既定値の型をその場で作る。
     *
     * **無いときに空を返さない**のは、ルールがあるのに枠が出ないと
     * 「予約できないアプリ」に見えるため。数字を触っていないだけで、
     * 予約そのものは既定値で取れるべき。
     */
    fun policyFor(rule: Rule, policies: List<ReservationPolicy>): ReservationPolicy =
        policies.firstOrNull { it.ruleUid.isNotBlank() && it.ruleUid == rule.uid }
            ?: policies.firstOrNull { it.ruleUid.isBlank() && RuleOverlap.overlaps(it.target, rule.target) }
            ?: defaultFor(rule)

    /** ルールから作る既定の型。保存はしない ── 数字を触ったときに初めて残る。 */
    fun defaultFor(rule: Rule): ReservationPolicy = ReservationPolicy(
        id = "rule:" + rule.uid.ifBlank { rule.id.toString() },
        label = rule.name,
        target = rule.target,
        ruleUid = rule.uid,
    )
}
