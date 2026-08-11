package com.dopachiru.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RuleEntity::class,
        AppTagEntity::class,
        UsageSessionEntity::class,
        ChangeRequestEntity::class,
        DeclarationEntity::class,
        BlockLogEntity::class,
        StudyWindowEntity::class,
        DayStatEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class DopaDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao
    abstract fun appTagDao(): AppTagDao
    abstract fun usageDao(): UsageDao
    abstract fun changeRequestDao(): ChangeRequestDao
    abstract fun declarationDao(): DeclarationDao
    abstract fun blockLogDao(): BlockLogDao
    abstract fun studyWindowDao(): StudyWindowDao
    abstract fun dayStatDao(): DayStatDao

    companion object {
        /**
         * 学習予定の窓を足した(ver.0.4)。
         *
         * 破壊的移行は許していない。個人用でも、貯めた記録が更新のたびに
         * 消えるのは困る。テーブルを足すたびにここに1つ書く。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `study_windows` (" +
                        "`id` TEXT NOT NULL, " +
                        "`startEpochSec` INTEGER NOT NULL, " +
                        "`endEpochSec` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`goalId` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`receivedAtEpochSec` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_study_windows_startEpochSec` " +
                        "ON `study_windows` (`startEpochSec`)"
                )
            }
        }

        @Volatile
        private var instance: DopaDatabase? = null

        fun get(context: Context): DopaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DopaDatabase::class.java,
                    "dopachiru.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
