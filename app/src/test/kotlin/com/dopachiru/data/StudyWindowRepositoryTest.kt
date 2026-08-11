package com.dopachiru.data

import com.dopachiru.data.db.StudyWindowDao
import com.dopachiru.data.db.StudyWindowEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 学習予定の窓の時刻計算。
 *
 * ここが実際より後ろの時刻を答えると、そのぶんブロックの開始も解除も遅れる。
 * 「窓の境界ちょうどで切り替わる」ことを固定しておく。
 */
class StudyWindowRepositoryTest {

    private class StubDao : StudyWindowDao {
        var stored: List<StudyWindowEntity> = emptyList()
        override suspend fun activeSince(sinceEpochSec: Long) = stored
        override suspend fun upsertAll(windows: List<StudyWindowEntity>) {
            stored = windows
        }

        override suspend fun deleteAll() {
            stored = emptyList()
        }

        override suspend fun purgeBefore(beforeEpochSec: Long) = Unit
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val repo = StudyWindowRepository(StubDao(), scope)

    /**
     * 実行した日を基準にする。
     *
     * 日付を固定すると、[StudyWindowRepository.replaceAll] が
     * 「24時間より古い窓は捨てる」ので、日が変わった翌日から落ちるようになる。
     */
    private val today: LocalDateTime = LocalDate.now().atStartOfDay()

    private fun sec(at: LocalDateTime): Long = at.atZone(ZoneId.systemDefault()).toEpochSecond()
    private fun ms(at: LocalDateTime): Long = sec(at) * 1000

    /** 10:00〜11:00 に1件だけ予定がある状態。 */
    private fun oneWindow() = listOf(
        StudyWindowRepository.Window(
            id = "w1",
            startSec = sec(today.withHour(10)),
            endSec = sec(today.withHour(11)),
            title = "数学 演習",
        )
    )

    @Test
    fun `窓の中と外を取り違えない`() {
        repo.replaceAll(oneWindow())

        assertFalse(repo.inSession(ms(today.withHour(9).withMinute(59))))
        assertTrue(repo.inSession(ms(today.withHour(10))))
        assertTrue(repo.inSession(ms(today.withHour(10).withMinute(59))))
        // 終了時刻ちょうどは、もう外
        assertFalse(repo.inSession(ms(today.withHour(11))))
    }

    @Test
    fun `窓の前なら開始時刻を答える`() {
        repo.prepMinutes = 0 // 助走枠そのものは別のテストで見る
        repo.replaceAll(oneWindow())
        val now = today.withHour(9)

        assertEquals(
            today.withHour(10),
            repo.state(ms(now)).nextBoundaryAfter(now),
        )
    }

    @Test
    fun `窓の中なら終了時刻を答える`() {
        repo.replaceAll(oneWindow())
        val now = today.withHour(10).withMinute(30)
        val state = repo.state(ms(now))

        assertTrue(state.inSession)
        assertEquals("数学 演習", state.currentTitle)
        assertEquals(today.withHour(11), state.nextBoundaryAfter(now))
    }

    @Test
    fun `予定が複数あれば一番近い境界を答える`() {
        repo.prepMinutes = 0
        repo.replaceAll(
            oneWindow() + StudyWindowRepository.Window(
                id = "w2",
                startSec = sec(today.withHour(14)),
                endSec = sec(today.withHour(15)),
            )
        )
        val now = today.withHour(12)

        assertEquals(today.withHour(14), repo.state(ms(now)).nextBoundaryAfter(now))
    }

    @Test
    fun `もう境界が無ければ翌日まで眠ってよい`() {
        repo.replaceAll(oneWindow())
        val now = today.withHour(20)

        // ここで null を返すと、次の同期が来るまで短い間隔で起き続けてしまう
        assertEquals(
            today.plusDays(1).toLocalDate().atStartOfDay(),
            repo.state(ms(now)).nextBoundaryAfter(now),
        )
    }

    @Test
    fun `一度も同期が来ていなくても止まらない`() {
        val now = today.withHour(12)
        val state = repo.state(ms(now))

        assertFalse(state.inSession)
        assertNull(state.currentTitle)
        assertEquals(
            today.plusDays(1).toLocalDate().atStartOfDay(),
            state.nextBoundaryAfter(now),
        )
    }

    @Test
    fun `短くして送り直せば即座に解ける`() {
        repo.replaceAll(oneWindow())
        val now = today.withHour(10).withMinute(30)
        assertTrue(repo.inSession(ms(now)))

        // 早く終わったので、終了時刻を今にして送り直す
        repo.replaceAll(
            listOf(
                StudyWindowRepository.Window(
                    id = "w1",
                    startSec = sec(today.withHour(10)),
                    endSec = sec(now),
                )
            )
        )
        assertFalse(repo.inSession(ms(now)))
    }

    // ------------------------------------------------------------------
    // 助走枠。予定の時間帯だけ塞いでも「始まる前に沈んで予定ごと潰す」失敗は防げない

    @Test
    fun `予定の手前が助走枠になる`() {
        repo.prepMinutes = 30
        repo.replaceAll(oneWindow())

        // 31分前 → まだ何でもない
        val early = today.withHour(9).withMinute(29)
        repo.state(ms(early)).let {
            assertFalse(it.inPrep)
            assertFalse(it.inSession)
        }
        // 30分前ちょうど → 助走枠に入る
        repo.state(ms(today.withHour(9).withMinute(30))).let {
            assertTrue(it.inPrep)
            assertFalse(it.inSession)
        }
        // 開始時刻 → 助走は終わり、予定中になる
        repo.state(ms(today.withHour(10))).let {
            assertFalse(it.inPrep)
            assertTrue(it.inSession)
        }
    }

    @Test
    fun `助走枠の始まりでも起こしてもらえる`() {
        repo.prepMinutes = 30
        repo.replaceAll(oneWindow())
        val now = today.withHour(8)

        // ここで開始時刻を返すと、助走枠に入ったことに気づけない
        assertEquals(
            today.withHour(9).withMinute(30),
            repo.state(ms(now)).nextBoundaryAfter(now),
        )
    }

    @Test
    fun `助走を切ると予定の時間帯だけになる`() {
        repo.prepMinutes = 0
        repo.replaceAll(oneWindow())

        assertFalse(repo.state(ms(today.withHour(9).withMinute(45))).inPrep)
        val now = today.withHour(8)
        assertEquals(today.withHour(10), repo.state(ms(now)).nextBoundaryAfter(now))
    }

    // ------------------------------------------------------------------
    // 中断。押し切りを塞いだ代わりの出口であり、閉じ込め事故の出口でもある

    @Test
    fun `中断すると待たずにその場で解ける`() {
        repo.replaceAll(oneWindow())
        val now = today.withHour(10).withMinute(30)
        assertTrue(repo.inSession(ms(now)))

        repo.endNow("w1", ms(now))

        // 連携アプリからの送り直しを待たない。待つと出口として機能しない
        assertFalse(repo.inSession(ms(now)))
    }

    @Test
    fun `まだ始まっていない予定を中断すると助走枠ごと消える`() {
        repo.prepMinutes = 30
        repo.replaceAll(oneWindow())
        val now = today.withHour(9).withMinute(40)
        assertTrue(repo.state(ms(now)).inPrep)

        repo.endNow("w1", ms(now))

        assertFalse(repo.state(ms(now)).inPrep)
        assertFalse(repo.inSession(ms(today.withHour(10).withMinute(30))))
    }

    @Test
    fun `中断の宛先は助走中でも取れる`() {
        repo.prepMinutes = 30
        repo.replaceAll(oneWindow())

        assertEquals("w1", repo.currentWindow(ms(today.withHour(9).withMinute(45)))?.id)
        assertEquals("w1", repo.currentWindow(ms(today.withHour(10).withMinute(30)))?.id)
        assertNull(repo.currentWindow(ms(today.withHour(8)))?.id)
    }

    @Test
    fun `終わりが始まりより前の窓は捨てる`() {
        repo.replaceAll(
            listOf(
                StudyWindowRepository.Window(
                    id = "broken",
                    startSec = sec(today.withHour(11)),
                    endSec = sec(today.withHour(10)),
                ),
                StudyWindowRepository.Window(id = "", startSec = 0, endSec = 100),
            )
        )
        assertFalse(repo.inSession(ms(today.withHour(10).withMinute(30))))
    }
}
