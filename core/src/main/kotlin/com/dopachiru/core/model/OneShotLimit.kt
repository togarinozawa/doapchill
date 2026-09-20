package com.dopachiru.core.model

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.condition.types.WindowBudgetCondition
import com.dopachiru.core.param.Params
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * その場で決める「◯時間使ったら◯分休憩」。
 *
 * ## なぜ普通のルールと分けるのか
 *
 * 分けていません ── **中身はただのルール**です([WindowBudgetCondition] +
 * 「条件のあいだ使えなくする」)。違うのは、作り方と、期限が入っていること。
 *
 * 分ける必要が無いのに入口を分けてあるのは、**作るときの気分が違う**からです。
 * 普通のルールは「これから先ずっとどうするか」を決める。こちらは
 * 「今日はこれで行く」を決める。残り続けると分かっているものは、作る前に
 * 「一生守れるか」を考えることになり、その場では作られません。
 * 明日消えると分かっていれば、いま決められます。
 *
 * ## なぜ窓([WindowBudgetCondition])なのか
 *
 * 「2時間使ったら1時間休憩」は、**幅3時間の窓に持ち時間2時間**と同じです。
 * 使い切れば残りの1時間は閉まり、窓が明ければまた2時間使えます。
 *
 * 「2時間使ったら」を素直に数えると、1時間59分で閉じて数え直させる手が効きます。
 * 窓は最初に触った時刻に張られ、閉じても消えないので、その手が効きません。
 */
object OneShotLimit {

    /** その場の枠の既定。「2時間使ったら1時間休憩」。 */
    const val DEFAULT_USE_MINUTES = 120
    const val DEFAULT_REST_MINUTES = 60

    const val MIN_USE_MINUTES = 5
    const val MAX_USE_MINUTES = 12 * 60
    const val MIN_REST_MINUTES = 5
    const val MAX_REST_MINUTES = 12 * 60

    /**
     * 「今日」の終わり。**日付が変わった瞬間ではなく、翌朝4時**。
     *
     * 深夜1時に作った枠が1時間で消えるのでは、その場の枠にならない。
     * 生活の区切りは日付の区切りより後ろにある。
     */
    const val DAY_ENDS_AT_HOUR = 4

    /** 今日いっぱいの期限(エポック秒)。 */
    fun endOfDay(now: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Long {
        val base: LocalDate = if (now.hour < DAY_ENDS_AT_HOUR) now.toLocalDate() else now.toLocalDate().plusDays(1)
        return base.atTime(DAY_ENDS_AT_HOUR, 0).atZone(zone).toEpochSecond()
    }

    /**
     * 枠をルールに組む。
     *
     * @param useMinutes 使っていい時間。
     * @param restMinutes 使い切ったあと閉まる時間。
     * @param expiresAtSec 消える時刻。0 なら消えない(普通のルールと同じ)。
     */
    fun build(
        target: Target,
        useMinutes: Int = DEFAULT_USE_MINUTES,
        restMinutes: Int = DEFAULT_REST_MINUTES,
        expiresAtSec: Long = 0L,
        devices: Set<String> = DeviceScope.EVERYWHERE,
        name: String = "",
    ): Rule {
        val use = useMinutes.coerceIn(MIN_USE_MINUTES, MAX_USE_MINUTES)
        val rest = restMinutes.coerceIn(MIN_REST_MINUTES, MAX_REST_MINUTES)
        return Rule(
            name = name.ifBlank { label(use, rest) },
            target = target,
            condition = ConditionNode.Leaf(
                WindowBudgetCondition.id,
                Params.of(
                    // 窓は「使う + 休む」。休みを窓の残りとして取るので、
                    // 別に休憩用の条件を足す必要がない
                    WindowBudgetCondition.KEY_WINDOW_MINUTES to (use + rest),
                    WindowBudgetCondition.KEY_BUDGET_MINUTES to use,
                ),
            ),
            // 「閉じるだけ」では開き直せる。条件が続くあいだ塞ぎ続ける側を使う
            actionId = BlockAction.id,
            actionParams = Params.EMPTY,
            expiresAtSec = expiresAtSec,
            devices = devices,
        )
    }

    /** 「2時間使ったら1時間休憩」。 */
    fun label(useMinutes: Int, restMinutes: Int): String =
        span(useMinutes) + "使ったら" + span(restMinutes) + "休憩"

    private fun span(minutes: Int): String = when {
        minutes >= 60 && minutes % 60 == 0 -> (minutes / 60).toString() + "時間"
        minutes >= 60 -> (minutes / 60).toString() + "時間" + (minutes % 60) + "分"
        else -> minutes.toString() + "分"
    }
}
