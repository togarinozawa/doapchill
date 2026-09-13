package com.dopachiru.core.condition.types

import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params

/**
 * 予約された時間帯の外なら成立する = 予約の外では塞ぐ。
 *
 * 予約([com.dopachiru.core.model.Reservation])は「冷静なうちに、この時間だけ使うと
 * 先に決めておく」もの。この条件を完全封印と組むと、**予約した枠でしか開けない
 * アプリ**になる。枠は少し先にしか取れない(直前予約は封じる)ので、
 * 「よし、この時間で済ませよう」という予定に変わる。
 *
 * パラメータは持たない ── どの予約が効くかは対象アプリで決まり、
 * いま枠の中か外かは [EvalContext.withinReservation] が端末側から運んでくる。
 */
object ReservationCondition : ConditionType {
    override val id = "outside_reservation"
    override val displayName = "予約した時間の外"
    override val description =
        "先に取った予約の時間帯だけ開けるようにする。予約の外では成立する(＝塞ぐ)。予約は少し先にしか取れない。"

    override val params: List<ParamSpec> = emptyList()

    override fun evaluate(p: Params, ctx: EvalContext): Boolean = !ctx.withinReservation

    override fun summarize(p: Params): String = "予約した時間の外"

    // 予約の開始・終了で切り替わるが、その時刻は端末側が知っている。
    // ここでは分からないので null を返し、呼び出し側は短い間隔で見に来る。
}
