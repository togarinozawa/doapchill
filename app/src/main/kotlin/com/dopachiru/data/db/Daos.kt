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
}

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
interface DayStatDao {
    @Query("SELECT * FROM day_stats ORDER BY epochDay DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DayStatEntity>>

    @Query("SELECT * FROM day_stats WHERE epochDay = :epochDay")
    suspend fun get(epochDay: Long): DayStatEntity?

    @Upsert
    suspend fun upsert(stat: DayStatEntity)
}
