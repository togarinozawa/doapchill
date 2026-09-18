package com.dopachiru.core.model

import com.dopachiru.core.time.ALL_DAYS
import com.dopachiru.core.time.describeDays
import com.dopachiru.core.time.formatMinuteOfDay
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 自分で始めなくても始まる集中。
 *
 * ## なぜ要るのか
 *
 * [Focus] は「思い立った瞬間に始める」ものです。ところが**思い立てないこと自体が
 * 中身**であるような状態がある ── 朝起きてしばらく、何を見るでもなくスマホを眺め、
 * 時間が惜しいと分かっているのに動き出せない、というあれです。
 *
 * そこに手を出すには、集中が**自分の起動を待たない**必要があります。
 * 起動の手間がかかる道具は、起動の手間を払えないときにちょうど効きません。
 *
 * ## 塞ぐだけでは半分
 *
 * 塞ぐのは**麻酔を取り上げる**ことで、**始動をくれる**わけではありません。
 * だから [steps] を持たせてあります ── 始まった画面に、前もって書いておいた
 * 小さな行動を1つだけ出す。決めるのは前の晩の自分(やる気がある側)で、
 * 朝の自分は選ばずに済みます。選択肢を出すと、選べないので固まります。
 *
 * ## 起点が2つあるわけ
 *
 * [FocusAnchor.AFTER_WAKE] は**その日はじめて端末に触った時刻**から数えます。
 * 起床が7時でも10時でも同じように効くのが取り柄で、朝の無気力にはこちらが素直です。
 *
 * [FocusAnchor.AT_CLOCK] は壁時計。「9時には机にいたいから8時半に塞ぐ」のような、
 * **予定の側から決まる**ぶんに使います。寝坊した日は空振りしますが、
 * それは予定が先にある種類の用事なので、空振りでよい。
 *
 * ## 昼を過ぎたら、もう朝ではない
 *
 * [byMinuteOfDay] を過ぎたら、その日はもう始めません。これが無いと、
 * 午後2時にはじめて端末を触った日に「朝の集中」が始まります。
 */
@Serializable
data class FocusSchedule(
    val uid: String = "",
    val enabled: Boolean = true,

    /** 画面に出す名前。「朝」「昼休みのあと」など。 */
    val label: String = "朝",

    val anchor: FocusAnchor = FocusAnchor.AFTER_WAKE,

    /**
     * [FocusAnchor.AFTER_WAKE] なら「はじめて触ってから何分後」、
     * [FocusAnchor.AT_CLOCK] なら「その日の何分目(0時起点)」。
     */
    val offsetMinutes: Int = 20,

    /** この時刻を過ぎたら、その日はもう始めない(0時起点の分)。 */
    val byMinuteOfDay: Int = 12 * 60,

    /** 効かせる曜日。空なら効かない(全部外したのと同じ)。 */
    val days: Set<Int> = ALL_DAYS,

    /** 長さ。 */
    val minutes: Int = 20,

    /**
     * 最初の一歩。改行で分けると、日替わりで1つずつ出ます。
     *
     * 空なら普通の集中として始まります(壁だけ)。
     */
    val steps: String = "",
) {
    /** その日の何分目に始まるか。[FocusAnchor.AFTER_WAKE] は触った時刻が要る。 */
    fun startMinuteOfDay(firstTouchMinuteOfDay: Int?): Int? = when (anchor) {
        FocusAnchor.AT_CLOCK -> offsetMinutes
        FocusAnchor.AFTER_WAKE -> firstTouchMinuteOfDay?.plus(offsetMinutes)
    }

    /** 設定画面に出す1行。 */
    fun describe(): String {
        val when_ = when (anchor) {
            FocusAnchor.AT_CLOCK -> formatMinuteOfDay(offsetMinutes)
            FocusAnchor.AFTER_WAKE -> "はじめて触ってから${offsetMinutes}分後"
        }
        return describeDays(days) + " " + when_ + "から" + minutes + "分"
    }

    /**
     * その日に出す一歩。
     *
     * 日付で回すので、**同じ日のうちは何度読んでも同じ**。出るたびに変わると、
     * 画面が描き直されるだけで別の指示になります。
     */
    fun stepFor(date: LocalDate): String {
        val lines = steps.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return ""
        return lines[(date.toEpochDay().mod(lines.size))]
    }
}

/** 集中が始まる起点。 */
enum class FocusAnchor {
    /** その日はじめて端末に触った時刻から数える。起床時刻がぶれても効く。 */
    AFTER_WAKE,

    /** 壁時計。予定の側から決まるぶんに使う。 */
    AT_CLOCK,
}

/** 予定された集中の判定。端末側の保存方法に依存しないようここに置く。 */
object FocusSchedules {

    /** 一度に持てる数。増やしても使い切れず、選ぶのが手間になるだけ。 */
    const val MAX = 4

    /**
     * いま始めるべきか。
     *
     * @param firstTouchMinuteOfDay その日はじめて端末に触った時刻(0時起点の分)。
     *   まだ触っていなければ null。
     * @param lastRunDate 最後に走らせた日。**同じ日に二度走らせないための鍵**で、
     *   これが無いと集中が明けた瞬間にまた始まって永久に閉まります。
     */
    fun isDue(
        schedule: FocusSchedule,
        now: LocalDateTime,
        firstTouchMinuteOfDay: Int?,
        lastRunDate: LocalDate?,
    ): Boolean {
        if (!schedule.enabled) return false
        if (schedule.days.isEmpty()) return false
        if (now.dayOfWeek.value !in schedule.days) return false
        if (lastRunDate == now.toLocalDate()) return false

        val nowMinute = now.hour * 60 + now.minute
        // 昼を過ぎたら、もう朝ではない
        if (nowMinute > schedule.byMinuteOfDay) return false

        val start = schedule.startMinuteOfDay(firstTouchMinuteOfDay) ?: return false
        if (start > schedule.byMinuteOfDay) return false
        return nowMinute >= start
    }

    /**
     * 次に見に来ればよい時刻(0時起点の分)。まだ今日のぶんが残っていなければ null。
     *
     * 電池のために、始まるまで寝ていられる長さを返します。
     */
    fun nextDueMinuteOfDay(
        schedules: List<FocusSchedule>,
        now: LocalDateTime,
        firstTouchMinuteOfDay: Int?,
        lastRunDateOf: (FocusSchedule) -> LocalDate?,
    ): Int? {
        val nowMinute = now.hour * 60 + now.minute
        return schedules.asSequence()
            .filter { it.enabled && it.days.isNotEmpty() && now.dayOfWeek.value in it.days }
            .filter { lastRunDateOf(it) != now.toLocalDate() }
            .mapNotNull { it.startMinuteOfDay(firstTouchMinuteOfDay)?.takeIf { m -> m <= it.byMinuteOfDay } }
            .filter { it >= nowMinute }
            .minOrNull()
    }

    /** 朝のぶんの雛形。書き換えて使う前提の、当たりさわりのない既定。 */
    fun morningDefault(): FocusSchedule = FocusSchedule(
        label = "朝",
        anchor = FocusAnchor.AFTER_WAKE,
        offsetMinutes = 20,
        byMinuteOfDay = 12 * 60,
        minutes = 20,
        steps = "顔を洗う\n机に3分だけ座る\nカーテンを開けて外を見る",
    )
}
