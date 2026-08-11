package com.dopachiru.core

import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params
import com.dopachiru.core.time.ResetPolicy
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals

class ResetPolicyTest {

    @Test
    fun `毎日4時起点の期間が正しく求まる`() {
        val policy = ResetPolicy(periodMinutes = 24 * 60, anchorMinuteOfDay = 4 * 60)

        // 4時より後 → その日の4時が起点
        assertEquals(
            LocalDateTime.of(2026, 8, 6, 4, 0),
            policy.periodStart(LocalDateTime.of(2026, 8, 6, 23, 30)),
        )
        // 4時より前 → 前日の4時が起点(深夜のスマホは前日の使用時間に乗る)
        assertEquals(
            LocalDateTime.of(2026, 8, 5, 4, 0),
            policy.periodStart(LocalDateTime.of(2026, 8, 6, 2, 0)),
        )
    }

    @Test
    fun `半日周期は一日に二回リセットされる`() {
        val policy = ResetPolicy(periodMinutes = 12 * 60, anchorMinuteOfDay = 4 * 60)

        assertEquals(
            LocalDateTime.of(2026, 8, 6, 4, 0),
            policy.periodStart(LocalDateTime.of(2026, 8, 6, 10, 0)),
        )
        assertEquals(
            LocalDateTime.of(2026, 8, 6, 16, 0),
            policy.periodStart(LocalDateTime.of(2026, 8, 6, 20, 0)),
        )
        assertEquals(
            LocalDateTime.of(2026, 8, 5, 16, 0),
            policy.periodStart(LocalDateTime.of(2026, 8, 6, 3, 0)),
        )
    }

    @Test
    fun `割り切れない周期では最後の期間だけが短くなる`() {
        // 5時間周期・4時起点 → 4:00, 9:00, 14:00, 19:00, 0:00 と刻み、
        // 0:00 の期間だけ次のアンカー(4:00)で切り詰められて4時間になる
        val policy = ResetPolicy(periodMinutes = 5 * 60, anchorMinuteOfDay = 4 * 60)

        assertEquals(
            LocalDateTime.of(2026, 8, 6, 19, 0),
            policy.periodStart(LocalDateTime.of(2026, 8, 6, 22, 0)),
        )
        assertEquals(
            LocalDateTime.of(2026, 8, 7, 0, 0),
            policy.periodEnd(LocalDateTime.of(2026, 8, 6, 22, 0)),
        )

        // 日をまたいだ先の端数期間
        assertEquals(
            LocalDateTime.of(2026, 8, 7, 0, 0),
            policy.periodStart(LocalDateTime.of(2026, 8, 7, 2, 0)),
        )
        assertEquals(
            LocalDateTime.of(2026, 8, 7, 4, 0),
            policy.periodEnd(LocalDateTime.of(2026, 8, 7, 2, 0)),
        )
    }

    @Test
    fun `パラメータのデフォルト値から初期値が作れる`() {
        val specs = listOf(
            ParamSpec.IntParam("count", "回数", default = 5),
            ParamSpec.DayOfWeekParam("days", "曜日", default = setOf(1, 3, 5)),
            ParamSpec.ResetPolicyParam("period", "集計期間", default = ResetPolicy(720, 240)),
            ParamSpec.TextParam("note", "メモ", default = "書く"),
            ParamSpec.BoolParam("flag", "フラグ", default = true),
        )
        val params = Params.defaultsOf(specs)

        assertEquals(5, params.int("count"))
        assertEquals(setOf(1, 3, 5), params.intSet("days"))
        assertEquals(ResetPolicy(720, 240), params.resetPolicy("period"))
        assertEquals("書く", params.string("note"))
        assertEquals(true, params.bool("flag"))

        // JSON を経由しても壊れない
        assertEquals(params, Params.decode(params.encode()))
    }
}
