package com.dopachiru.core.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「今日だけ」のルール。
 *
 * ## 中身はただのルール
 *
 * 違うのは期限([Rule.expiresAtSec])が入っていることだけで、作れるものは
 * 普通のルールと同じです(雛形からでも、ゼロから組んでも)。前は
 * 「◯時間使ったら◯分休憩」しか作れず、今日だけ別の縛りにしたいときに困った。
 *
 * ## なぜ入口を分けるのか
 *
 * **作るときの気分が違う**からです。普通のルールは「これから先ずっとどうするか」を
 * 決める。こちらは「今日はこれで行く」を決める。残り続けると分かっているものは、
 * 作る前に「一生守れるか」を考えることになり、その場では作られません。
 * 明日消えると分かっていれば、いま決められます。
 */
object OneShotLimit {

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

    /** 作ったルールを今日だけのものにする。 */
    fun forToday(rule: Rule, now: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Rule =
        rule.copy(expiresAtSec = endOfDay(now, zone))
}
