package com.dopachiru.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY enabled DESC, id ASC")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules ORDER BY enabled DESC, id ASC")
    suspend fun getAll(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun getById(id: Long): RuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: RuleEntity): Long

    @Update
    suspend fun update(rule: RuleEntity)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE rules SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, now: Long)
}

@Dao
interface AppTagDao {
    @Query("SELECT * FROM app_tags")
    fun observeAll(): Flow<List<AppTagEntity>>

    @Query("SELECT * FROM app_tags")
    suspend fun getAll(): List<AppTagEntity>

    @Query("SELECT DISTINCT tag FROM app_tags ORDER BY tag")
    fun observeTags(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: AppTagEntity)

    @Delete
    suspend fun delete(tag: AppTagEntity)

    @Query("DELETE FROM app_tags WHERE tag = :tag")
    suspend fun deleteTag(tag: String)
}

@Dao
interface UsageDao {
    @Insert
    suspend fun insert(session: UsageSessionEntity): Long

    /** 開いているあいだ、定期的に終端を伸ばす。 */
    @Query("UPDATE usage_sessions SET endEpochSec = :endEpochSec WHERE id = :id")
    suspend fun updateEnd(id: Long, endEpochSec: Long)

    @Query("SELECT * FROM usage_sessions WHERE endEpochSec >= :sinceEpochSec ORDER BY startEpochSec")
    suspend fun allSince(sinceEpochSec: Long): List<UsageSessionEntity>

    @Query("SELECT * FROM usage_sessions WHERE packageName = :pkg AND endEpochSec >= :sinceEpochSec ORDER BY startEpochSec")
    suspend fun sessionsSince(pkg: String, sinceEpochSec: Long): List<UsageSessionEntity>

    @Query("SELECT * FROM usage_sessions WHERE endEpochSec >= :sinceEpochSec ORDER BY startEpochSec")
    fun observeSince(sinceEpochSec: Long): Flow<List<UsageSessionEntity>>

    @Query("DELETE FROM usage_sessions WHERE endEpochSec < :beforeEpochSec")
    suspend fun purgeBefore(beforeEpochSec: Long)
}

@Dao
interface ChangeRequestDao {
    @Query("SELECT * FROM change_requests ORDER BY createdAtEpochSec DESC")
    fun observeAll(): Flow<List<ChangeRequestEntity>>

    @Query("SELECT * FROM change_requests WHERE status = 'PENDING' ORDER BY createdAtEpochSec")
    fun observePending(): Flow<List<ChangeRequestEntity>>

    @Query("SELECT * FROM change_requests WHERE id = :id")
    suspend fun getById(id: Long): ChangeRequestEntity?

    @Insert
    suspend fun insert(request: ChangeRequestEntity): Long

    @Update
    suspend fun update(request: ChangeRequestEntity)
}

@Dao
interface DeclarationDao {
    @Query("SELECT * FROM declarations WHERE packageName = :pkg AND active = 1 LIMIT 1")
    suspend fun activeFor(pkg: String): DeclarationEntity?

    @Query("SELECT * FROM declarations WHERE active = 1")
    suspend fun allActive(): List<DeclarationEntity>

    @Insert
    suspend fun insert(declaration: DeclarationEntity): Long

    @Query("UPDATE declarations SET consumedSec = :consumedSec WHERE id = :id")
    suspend fun updateConsumed(id: Long, consumedSec: Long)

    @Query("UPDATE declarations SET active = 0 WHERE id = :id")
    suspend fun deactivate(id: Long)

    @Query("UPDATE declarations SET active = 0 WHERE packageName = :pkg")
    suspend fun deactivateAllFor(pkg: String)
}

@Dao
interface BlockLogDao {
    @Query("SELECT * FROM block_logs ORDER BY atEpochSec DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BlockLogEntity>>

    @Query("SELECT * FROM block_logs WHERE insteadNote = '' ORDER BY atEpochSec DESC LIMIT 1")
    fun observeLatestWithoutNote(): Flow<BlockLogEntity?>

    @Insert
    suspend fun insert(log: BlockLogEntity): Long

    @Query("UPDATE block_logs SET insteadNote = :note WHERE id = :id")
    suspend fun setNote(id: Long, note: String)

    @Query("UPDATE block_logs SET overridden = 1 WHERE id = :id")
    suspend fun markOverridden(id: Long)

    /**
     * ルールごとの押し切り回数。「そのルールに慣れたか」の判定に使う。
     *
     * Flow ではなく都度引くのは、対象期間が「いまから遡って1週間」で動き続けるため。
     * Flow にすると購読した時刻で窓が固定される。
     */
    @Query(
        "SELECT ruleId, COUNT(*) AS count FROM block_logs " +
            "WHERE overridden = 1 AND atEpochSec >= :sinceEpochSec GROUP BY ruleId"
    )
    suspend fun overrideCountsSince(sinceEpochSec: Long): List<RuleOverrideCount>
}

/** [BlockLogDao.overrideCountsSince] の戻り。 */
data class RuleOverrideCount(val ruleId: Long, val count: Int)

@Dao
interface StudyWindowDao {
    @Query("SELECT * FROM study_windows WHERE endEpochSec >= :sinceEpochSec ORDER BY startEpochSec")
    suspend fun activeSince(sinceEpochSec: Long): List<StudyWindowEntity>

    @Upsert
    suspend fun upsertAll(windows: List<StudyWindowEntity>)

    @Query("DELETE FROM study_windows")
    suspend fun deleteAll()

    @Query("DELETE FROM study_windows WHERE endEpochSec < :beforeEpochSec")
    suspend fun purgeBefore(beforeEpochSec: Long)
}

@Dao
interface LockoutDao {
    @Query("SELECT * FROM lockouts WHERE untilEpochSec > :nowEpochSec ORDER BY untilEpochSec DESC")
    fun observeActive(nowEpochSec: Long): Flow<List<LockoutEntity>>

    @Query("SELECT * FROM lockouts WHERE untilEpochSec > :nowEpochSec")
    suspend fun activeAt(nowEpochSec: Long): List<LockoutEntity>

    @Insert
    suspend fun insert(lockout: LockoutEntity): Long

    @Query("DELETE FROM lockouts WHERE untilEpochSec <= :beforeEpochSec")
    suspend fun purgeBefore(beforeEpochSec: Long)

    /**
     * 集中の延長と取り消しは uid で引く。
     *
     * 挿入した行の id はメモリ上のキャッシュに戻していないので、
     * id で引くと別の行を触る。uid なら作った時点で決まっている。
     */
    @Query("UPDATE lockouts SET untilEpochSec = :untilEpochSec WHERE uid = :uid AND uid != ''")
    suspend fun extendByUid(uid: String, untilEpochSec: Long)

    @Query("DELETE FROM lockouts WHERE uid = :uid AND uid != ''")
    suspend fun deleteByUid(uid: String)

    /** 開発ツール専用。ふだんの動作からは呼ばない。 */
    @Query("DELETE FROM lockouts")
    suspend fun deleteAll()
}

@Dao
interface PointEventDao {
    @Query("SELECT * FROM point_events ORDER BY atEpochSec DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PointEventEntity>>

    @Query("SELECT COALESCE(SUM(delta), 0) FROM point_events")
    fun observeBalance(): Flow<Int>

    @Query("SELECT COALESCE(SUM(delta), 0) FROM point_events")
    suspend fun balance(): Int

    /**
     * 鍵が重複したら黙って捨てる。
     * 「その日はもう加点済み」を呼び出し側で数えなくて済ませるため。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: PointEventEntity): Long

    @Query("DELETE FROM point_events")
    suspend fun deleteAll()
}

@Dao
interface DayStatDao {
    @Query("SELECT * FROM day_stats ORDER BY epochDay DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DayStatEntity>>

    @Query("SELECT * FROM day_stats WHERE epochDay = :epochDay")
    suspend fun get(epochDay: Long): DayStatEntity?

    @Upsert
    suspend fun upsert(stat: DayStatEntity)
}
