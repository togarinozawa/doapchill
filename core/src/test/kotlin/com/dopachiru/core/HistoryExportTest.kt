package com.dopachiru.core

import com.dopachiru.core.io.BlockEvent
import com.dopachiru.core.io.ChangeEvent
import com.dopachiru.core.io.DayEvent
import com.dopachiru.core.io.DeclarationEvent
import com.dopachiru.core.io.HistoryData
import com.dopachiru.core.io.HistoryExport
import com.dopachiru.core.io.UsageReport
import com.dopachiru.core.io.UsageSpan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 履歴の書き出し。AI 用(説明つき)とデータのみ(JSON)で、中身の線引きが崩れないこと。 */
class HistoryExportTest {

    private val zone = ZoneId.of("Asia/Tokyo")
    private val now = LocalDateTime.of(2026, 10, 2, 12, 0)

    @Before
    fun setUp() = DopaCore.registerAll()

    private fun at(day: Int, hour: Int): Long =
        LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toEpochSecond()

    private val history = HistoryData(
        blocks = listOf(
            BlockEvent(at(1, 23), "com.example.game", "夜のゲーム", "block", overridden = true, note = "対戦の途中だった|改行\nあり"),
            BlockEvent(at(1, 22), "com.example.game", "夜のゲーム", "block", overridden = false, note = ""),
        ),
        days = listOf(DayEvent(LocalDate.of(2026, 10, 1), blocks = 2, overrides = 1, screenMinutes = 190)),
        changes = listOf(ChangeEvent(at(1, 20), "UPDATE", "夜のゲーム", "緩めたい", "APPLIED")),
        declarations = listOf(DeclarationEvent(at(1, 19), "com.example.game", 30, 55, "1試合だけ")),
    )

    private val spans = listOf(UsageSpan("com.example.game", at(1, 22), at(1, 23)))

    private fun advice(h: HistoryData?) = UsageReport.build(
        spans = spans, labelOf = { it }, rules = emptyList(), tags = emptyMap(),
        now = now, days = 14, deviceName = "pixel", zone = zone, history = h,
    )

    @Test
    fun `AI 用は履歴の節と頼みごとが入る`() {
        val text = advice(history)
        assertTrue("ルールごとの押し切り" in text)
        assertTrue("| 夜のゲーム | 2回 | 1回 | 50% |" in text)
        assertTrue("強める / そのまま / 弱める" in text)
        assertTrue("ルールを変えた申請" in text)
        assertTrue("開く前の宣言" in text)
    }

    @Test
    fun `自由記述は表を壊さない`() {
        val text = advice(history)
        assertTrue("対戦の途中だった/改行 あり" in text)
    }

    @Test
    fun `履歴が無いときは従来どおりで、押し切りの頼みごとは出ない`() {
        val text = advice(null)
        assertFalse("ルールごとの押し切り" in text)
        assertFalse("強める / そのまま / 弱める" in text)
        assertTrue("Claude へ" in text)
    }

    @Test
    fun `データのみは説明も頼みごとも入らず、記録は欠けない`() {
        val text = HistoryExport.buildData(
            spans = spans, labelOf = { "ゲーム" },
            rules = listOf(HistoryExport.RuleSummary("夜のゲーム", true, "com.example.game", "22:00〜06:00", "閉じる")),
            history = history, now = now, days = 14, deviceName = "pixel", zone = zone,
        )
        assertFalse("Claude" in text)
        assertFalse("お願い" in text)
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals(2, root["blocks"]!!.jsonArray.size)
        assertEquals("true", root["blocks"]!!.jsonArray[0].jsonObject["overridden"]!!.jsonPrimitive.content)
        assertEquals(1, root["usage"]!!.jsonArray.size)
        assertEquals("ゲーム", root["usage"]!!.jsonArray[0].jsonObject["appLabel"]!!.jsonPrimitive.content)
        assertEquals("2026-10-01T23:00", root["blocks"]!!.jsonArray[0].jsonObject["at"]!!.jsonPrimitive.content)
        assertEquals(55, root["declarations"]!!.jsonArray[0].jsonObject["usedMinutes"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `期間より前の使用は JSON から落とし、期間にはみ出した分は切る`() {
        val old = UsageSpan("x", at(1, 0) - 30L * 24 * 3600, at(1, 0) - 30L * 24 * 3600 + 600)
        val text = HistoryExport.buildData(
            spans = listOf(old), labelOf = { it }, rules = emptyList(),
            history = HistoryData(), now = now, days = 14, deviceName = "", zone = zone,
        )
        assertEquals(0, Json.parseToJsonElement(text).jsonObject["usage"]!!.jsonArray.size)
    }
}
