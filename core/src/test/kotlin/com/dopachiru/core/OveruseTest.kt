package com.dopachiru.core

import com.dopachiru.core.model.OveruseBaseline
import com.dopachiru.core.model.OveruseSettings
import com.dopachiru.core.model.Overuses
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** ルールなしの使い過ぎの見張り。 */
class OveruseTest {

    private val settings = OveruseSettings(factorPercent = 200, floorMinutes = 15, newAppMinutes = 30, repeatMinutes = 15)

    /** 10分ずつ、1時間おきに n 回。 */
    private fun history(n: Int, minutes: Long = 10, from: Long = 0) =
        List(n) { i -> (from + i * 3600L) to (from + i * 3600L + minutes * 60) }

    @Test
    fun `近い区間は一続きにまとめる`() {
        val runs = Overuses.runs(listOf(0L to 300L, 330L to 600L, 2000L to 2100L))
        assertEquals(listOf(0L to 600L, 2000L to 2100L), runs)
    }

    @Test
    fun `ちらっと見ただけの使用は平均に入れない`() {
        val spans = history(5) + List(10) { i -> (100_000L + i * 3600) to (100_000L + i * 3600 + 20) }
        val base = assertNotNull(Overuses.baseline(spans, beforeSec = 1_000_000))
        assertEquals(600, base.averageSec)
        assertEquals(5, base.runs)
    }

    @Test
    fun `回数が足りなければ平均は出さない`() {
        assertNull(Overuses.baseline(history(4), beforeSec = 1_000_000))
    }

    @Test
    fun `いま続いている一続きは平均に混ぜない`() {
        val spans = history(5) + (50_000L to 60_000L)
        val base = assertNotNull(Overuses.baseline(spans, beforeSec = 55_000))
        assertEquals(600, base.averageSec)
    }

    @Test
    fun `いつもの倍を超えたら知らせる`() {
        val base = OveruseBaseline(averageSec = 12 * 60, runs = 10)
        assertNull(Overuses.check(settings, base, currentMinutes = 23, lastAlertMinutes = null))
        val alert = assertNotNull(Overuses.check(settings, base, currentMinutes = 24, lastAlertMinutes = null))
        assertEquals(12, alert.averageMinutes)
    }

    @Test
    fun `いつもが短くても下限までは知らせない`() {
        val base = OveruseBaseline(averageSec = 2 * 60, runs = 10)
        assertNull(Overuses.check(settings, base, currentMinutes = 14, lastAlertMinutes = null))
        assertNotNull(Overuses.check(settings, base, currentMinutes = 15, lastAlertMinutes = null))
    }

    @Test
    fun `履歴が少ないアプリは決めた分数で知らせる`() {
        assertNull(Overuses.check(settings, null, currentMinutes = 29, lastAlertMinutes = null))
        val alert = assertNotNull(Overuses.check(settings, null, currentMinutes = 30, lastAlertMinutes = null))
        assertNull(alert.averageMinutes)
        assertNull(Overuses.check(settings.copy(newAppMinutes = 0), null, 300, null))
    }

    @Test
    fun `繰り返しは決めた間隔ごと`() {
        val base = OveruseBaseline(averageSec = 10 * 60, runs = 10)
        assertNull(Overuses.check(settings, base, currentMinutes = 34, lastAlertMinutes = 20))
        assertNotNull(Overuses.check(settings, base, currentMinutes = 35, lastAlertMinutes = 20))
        assertNull(Overuses.check(settings.copy(repeatMinutes = 0), base, 90, lastAlertMinutes = 20))
    }

    @Test
    fun `切ってあれば知らせない`() {
        assertNull(Overuses.check(settings.copy(enabled = false), null, 999, null))
    }

    @Test
    fun `いまの一続きの長さ`() {
        val spans = listOf(0L to 600L, 620L to 1200L)
        assertEquals(1200, Overuses.currentRunSec(spans, nowSec = 1200))
        // 離れてから戻ってきていなければ0
        assertEquals(0, Overuses.currentRunSec(spans, nowSec = 1300))
    }
}
