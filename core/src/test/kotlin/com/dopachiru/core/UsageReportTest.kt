package com.dopachiru.core

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.io.UsageReport
import com.dopachiru.core.io.UsageSpan
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import org.junit.Before
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 使用実績の書き出し。
 *
 * 肝は **時をまたいだぶんを両方に足すこと**。開始時刻だけで数えると、
 * 22:40 から1時間使ったぶんが全部22時に載って、深夜の山が消える ──
 * この書き出しでいちばん見たいものが見えなくなる。
 */
class UsageReportTest {

    private val zone = ZoneId.of("Asia/Tokyo")

    @Before
    fun setUp() = DopaCore.registerAll()

    /** その日のその時刻の epoch 秒。2026-09-18 は金曜。 */
    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 9, day, hour, minute).atZone(zone).toEpochSecond()

    // ---- 時間帯 --------------------------------------------------------

    @Test
    fun `時をまたいだら両方に足す`() {
        // 22:40 から 23:20 まで。22時に20分、23時に20分
        val spans = listOf(UsageSpan("x", at(18, 22, 40), at(18, 23, 20)))
        val hours = UsageReport.hourlyOf(spans, zone)
        assertEquals(20, hours[22])
        assertEquals(20, hours[23])
    }

    @Test
    fun `何時間もまたげる`() {
        // 21:00 から 翌 1:00 まで。21・22・23・0 に60分ずつ
        val spans = listOf(UsageSpan("x", at(18, 21), at(19, 1)))
        val hours = UsageReport.hourlyOf(spans, zone)
        assertEquals(60, hours[21])
        assertEquals(60, hours[22])
        assertEquals(60, hours[23])
        assertEquals(60, hours[0])
        assertEquals(0, hours[2])
    }

    @Test
    fun `同じ時のぶんは足し合わせる`() {
        val spans = listOf(
            UsageSpan("x", at(18, 12, 0), at(18, 12, 10)),
            UsageSpan("x", at(19, 12, 30), at(19, 12, 45)),
        )
        assertEquals(25, UsageReport.hourlyOf(spans, zone)[12])
    }

    @Test
    fun `記録が無ければ全部ゼロ`() {
        assertTrue(UsageReport.hourlyOf(emptyList(), zone).all { it == 0 })
    }

    // ---- 曜日 ----------------------------------------------------------

    @Test
    fun `曜日に振り分ける`() {
        // 2026-09-18 は金曜(index 4)
        val spans = listOf(UsageSpan("x", at(18, 10), at(18, 11)))
        assertEquals(60, UsageReport.weeklyOf(spans, zone)[4])
    }

    @Test
    fun `日をまたいだら両方の曜日に足す`() {
        // 金 23:30 から 土 0:30。金に30分、土に30分
        val spans = listOf(UsageSpan("x", at(18, 23, 30), at(19, 0, 30)))
        val week = UsageReport.weeklyOf(spans, zone)
        assertEquals(30, week[4])
        assertEquals(30, week[5])
    }

    // ---- 長さの書き方 --------------------------------------------------

    @Test
    fun `時間と分に直す`() {
        assertEquals("0分", UsageReport.hm(0))
        assertEquals("45分", UsageReport.hm(45 * 60))
        assertEquals("1時間", UsageReport.hm(60 * 60))
        assertEquals("2時間20分", UsageReport.hm(140 * 60))
    }

    // ---- まるごと ------------------------------------------------------

    private val now = LocalDateTime.of(2026, 9, 19, 12, 0)

    private fun report(spans: List<UsageSpan>, rules: List<Rule> = emptyList()): String =
        UsageReport.build(
            spans = spans,
            labelOf = { if (it == "com.google.android.youtube") "YouTube" else it },
            rules = rules,
            tags = mapOf("com.google.android.youtube" to setOf("動画")),
            now = now,
            days = 14,
            deviceName = "Pixel",
            zone = zone,
        )

    @Test
    fun `記録が無ければそう言う`() {
        val text = report(emptyList())
        assertTrue(text.contains("記録がありません"))
        // 空の表を並べても読む意味が無い
        assertFalse(text.contains("## 時間帯"))
    }

    @Test
    fun `アプリの名前で出す`() {
        // パッケージ名のままでは、渡された側も本人も読めない
        val text = report(listOf(UsageSpan("com.google.android.youtube", at(18, 22), at(18, 23))))
        assertTrue(text.contains("YouTube"))
    }

    @Test
    fun `期間の外は数えない`() {
        // 14日より前のものが混ざっても、期間のぶんだけを見る
        val old = UsageSpan("x", at(1, 10), at(1, 12))
        val recent = UsageSpan("x", at(18, 10), at(18, 11))
        val text = report(listOf(old, recent))
        // 合計1時間。古い2時間を足していたら3時間になる
        assertTrue(text.contains("| x | 1時間 |"), text.lines().first { it.startsWith("| x") })
    }

    @Test
    fun `個人の記録だと断る`() {
        // 公開リポジトリに置かれるのがいちばん困る
        assertTrue(report(listOf(UsageSpan("x", at(18, 10), at(18, 11)))).contains("個人の記録"))
    }

    @Test
    fun `いまのルールを載せる`() {
        val rule = Rule(
            id = 1,
            name = "夜はYouTubeを閉じる",
            target = Target(packages = setOf("com.google.android.youtube")),
            condition = ConditionNode.AllOf(emptyList()),
            actionId = BlockAction.id,
            actionParams = Params.defaultsOf(BlockAction.params),
        )
        val text = report(listOf(UsageSpan("x", at(18, 10), at(18, 11))), listOf(rule))
        assertTrue(text.contains("夜はYouTubeを閉じる"))
        // 既にあるものを知らせないと、同じルールをもう1本作らせてしまう
        assertTrue(text.contains("## いまのルール"))
    }

    @Test
    fun `タグも載せる`() {
        val text = report(listOf(UsageSpan("com.google.android.youtube", at(18, 10), at(18, 11))))
        assertTrue(text.contains("**動画**"))
    }

    @Test
    fun `何をしてほしいかを書いておく`() {
        // 本人が毎回書き足さなくて済むように、注文まで入れておく
        val text = report(listOf(UsageSpan("x", at(18, 10), at(18, 11))))
        assertTrue(text.contains("## Claude へ"))
        assertTrue(text.contains(".rules"))
    }
}
