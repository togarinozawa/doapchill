package com.dopachiru.data

import com.dopachiru.data.db.BlockLogDao
import com.dopachiru.data.db.BlockLogEntity
import com.dopachiru.data.db.DayStatDao
import com.dopachiru.data.db.DayStatEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/** 連続達成日数・ブロック履歴・育成段階のもとになる集計。 */
class StatsRepository(
    private val dayStatDao: DayStatDao,
    private val blockLogDao: BlockLogDao,
) {
    val recentDays: Flow<List<DayStatEntity>> = dayStatDao.observeRecent(120)

    val recentLogs: Flow<List<BlockLogEntity>> = blockLogDao.observeRecent(100)

    /** まだ「代わりに何をしたか」を書いていない直近のブロック。 */
    val latestUnwritten: Flow<BlockLogEntity?> = blockLogDao.observeLatestWithoutNote()

    /** 今日から遡って「守れた日」が何日続いているか。 */
    val streak: Flow<Int> = dayStatDao.observeRecent(400).map { days -> computeStreak(days) }

    /** その日の集計。無ければ null。 */
    suspend fun dayStat(epochDay: Long): DayStatEntity? = dayStatDao.get(epochDay)

    /** その日の行が無ければ作る。常駐サービスから定期的に呼ぶ。 */
    suspend fun ensureToday() {
        val today = LocalDate.now().toEpochDay()
        if (dayStatDao.get(today) == null) {
            dayStatDao.upsert(DayStatEntity(epochDay = today))
        }
    }

    suspend fun recordBlockShown(
        packageName: String,
        ruleId: Long,
        ruleName: String,
        actionId: String,
    ): Long {
        val today = LocalDate.now().toEpochDay()
        val stat = dayStatDao.get(today) ?: DayStatEntity(epochDay = today)
        dayStatDao.upsert(stat.copy(blockShownCount = stat.blockShownCount + 1))
        return blockLogDao.insert(
            BlockLogEntity(
                packageName = packageName,
                ruleId = ruleId,
                ruleName = ruleName,
                actionId = actionId,
                atEpochSec = System.currentTimeMillis() / 1000,
            )
        )
    }

    /** ブロックを押し切って使ってしまった。連続記録が途切れる。 */
    suspend fun recordOverride(logId: Long) {
        val today = LocalDate.now().toEpochDay()
        val stat = dayStatDao.get(today) ?: DayStatEntity(epochDay = today)
        dayStatDao.upsert(stat.copy(overrideCount = stat.overrideCount + 1))
        blockLogDao.markOverridden(logId)
    }

    suspend fun writeNote(logId: Long, note: String) = blockLogDao.setNote(logId, note)

    suspend fun updateTotalScreenMinutes(minutes: Int) {
        val today = LocalDate.now().toEpochDay()
        val stat = dayStatDao.get(today) ?: DayStatEntity(epochDay = today)
        dayStatDao.upsert(stat.copy(totalScreenMinutes = minutes))
    }

    companion object {
        /**
         * 記録のある日だけを見て、今日(または昨日)から連続して守れている日数を返す。
         *
         * 今日まだ記録が無い状態で 0 に落とすと体感が悪いので、
         * 今日の行が無ければ昨日を起点にする。
         */
        fun computeStreak(days: List<DayStatEntity>): Int {
            if (days.isEmpty()) return 0
            val byDay = days.associateBy { it.epochDay }
            val today = LocalDate.now().toEpochDay()
            var cursor = if (byDay.containsKey(today)) today else today - 1
            var streak = 0
            while (true) {
                val stat = byDay[cursor] ?: break
                if (!stat.kept) break
                streak++
                cursor--
            }
            return streak
        }
    }
}

/** 連続達成日数に応じて育つもの。 */
enum class GrowthStage(val minStreak: Int, val label: String, val art: String) {
    SEED(0, "たね", "•"),
    SPROUT(3, "めばえ", "ᵕ"),
    LEAF(7, "わかば", "🌱"),
    BUD(14, "つぼみ", "🌿"),
    FLOWER(30, "はな", "🌸"),
    TREE(60, "き", "🌳"),
    FOREST(100, "もり", "🌲🌳🌲");

    companion object {
        fun of(streak: Int): GrowthStage = entries.last { streak >= it.minStreak }

        /** 次の段階までの残り日数。最終段階なら null。 */
        fun nextIn(streak: Int): Int? {
            val next = entries.firstOrNull { it.minStreak > streak } ?: return null
            return next.minStreak - streak
        }
    }
}
