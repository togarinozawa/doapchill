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
