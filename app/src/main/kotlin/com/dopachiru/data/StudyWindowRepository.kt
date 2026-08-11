package com.dopachiru.data

import com.dopachiru.core.engine.StudyState
import com.dopachiru.data.db.StudyWindowDao
import com.dopachiru.data.db.StudyWindowEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 連携アプリから受け取った学習予定を保持する。
 *
 * 通信は一切しない。端末内ブロードキャストで届いたものを、そのまま Room に写して
 * メモリから引くだけ。機内モードでも制限は外れないし、外から解除される経路も無い。
 *
 * 窓は開始・終了の時刻を自分で持っているので、送り主のアプリが落ちて
 * 「終わった」の連絡が来なくなっても、時刻が過ぎれば勝手に解ける。
 */
class StudyWindowRepository(
    private val dao: StudyWindowDao,
    private val scope: CoroutineScope,
) {

    data class Window(
        val id: String,
        val startSec: Long,
        val endSec: Long,
        val title: String = "",
        val goalId: String = "",
        val kind: String = "",
    )

    @Volatile
    private var windows: List<Window> = emptyList()

    /** 最後に同期が届いた時刻。0 なら一度も来ていない。設定画面の表示用。 */
    @Volatile
    var lastSyncAtMs: Long = 0L
        private set

    suspend fun warmUp() {
        val nowSec = System.currentTimeMillis() / 1000
        dao.purgeBefore(nowSec - RETENTION_SEC)
        windows = dao.activeSince(nowSec - RETENTION_SEC).map { it.toWindow() }.sortedBy { it.startSec }
        if (windows.isNotEmpty()) lastSyncAtMs = System.currentTimeMillis()
    }

    /**
     * 届いた内容で丸ごと置き換える。
     *
     * 差分ではなく全置換なのは、届く順番を気にしなくてよくするため。
     * 予定を早く切り上げたいときも「短くした窓を送り直す」だけでよく、
     * 解除専用の経路を別に持たなくて済む。
     *
     * メモリは即時に入れ替える。判定はここから引くので、DB の書き込みを待たない。
     */
    fun replaceAll(incoming: List<Window>) {
        val nowSec = System.currentTimeMillis() / 1000
        val cleaned = incoming
            .filter { it.id.isNotBlank() && it.endSec > it.startSec }
            .filter { it.endSec > nowSec - RETENTION_SEC }
            .sortedBy { it.startSec }

        windows = cleaned
        lastSyncAtMs = System.currentTimeMillis()

        scope.launch {
            dao.deleteAll()
            if (cleaned.isNotEmpty()) {
                dao.upsertAll(cleaned.map { it.toEntity(nowSec) })
            }
        }
    }

    fun inSession(nowMs: Long = System.currentTimeMillis()): Boolean {
        val sec = nowMs / 1000
        return windows.any { sec >= it.startSec && sec < it.endSec }
    }

    /** いまの学習状況。 */
    fun state(nowMs: Long = System.currentTimeMillis()): StudyState {
        val snapshot = windows
        val sec = nowMs / 1000
        val current = snapshot.firstOrNull { sec >= it.startSec && sec < it.endSec }

        return object : StudyState {
            override val inSession: Boolean = current != null
            override val currentTitle: String? = current?.title?.takeIf { it.isNotBlank() }

            override fun nextBoundaryAfter(now: LocalDateTime): LocalDateTime? {
                val zone = ZoneId.systemDefault()
                val nowSec = now.atZone(zone).toEpochSecond()
                var earliest = Long.MAX_VALUE
                for (w in snapshot) {
                    if (w.startSec in (nowSec + 1) until earliest) earliest = w.startSec
                    if (w.endSec in (nowSec + 1) until earliest) earliest = w.endSec
                }
                // これから境界が無い = 次の同期が来るまで状況は変わらない。
                // 同期が届いたときは受信側が判定をキックするので、ここは長く眠ってよい。
                if (earliest == Long.MAX_VALUE) return now.toLocalDate().plusDays(1).atStartOfDay()
                return Instant.ofEpochSecond(earliest).atZone(zone).toLocalDateTime()
            }
        }
    }

    /** 設定画面の表示用。これからの予定。 */
    fun upcoming(nowMs: Long = System.currentTimeMillis(), limit: Int = 10): List<Window> {
        val sec = nowMs / 1000
        return windows.filter { it.endSec > sec }.take(limit)
    }

    private fun Window.toEntity(receivedAtSec: Long) = StudyWindowEntity(
        id = id,
        startEpochSec = startSec,
        endEpochSec = endSec,
        title = title,
        goalId = goalId,
        kind = kind,
        receivedAtEpochSec = receivedAtSec,
    )

    private fun StudyWindowEntity.toWindow() = Window(
        id = id,
        startSec = startEpochSec,
        endSec = endEpochSec,
        title = title,
        goalId = goalId,
        kind = kind,
    )

    private companion object {
        /** 終わった窓をどれだけ残すか。再起動直後の復元にしか使わないので短くてよい。 */
        const val RETENTION_SEC = 24L * 60 * 60
    }
}
