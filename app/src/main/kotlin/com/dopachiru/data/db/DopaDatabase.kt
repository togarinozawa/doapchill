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
        LockoutEntity::class,
        PointEventEntity::class,
    ],
    version = 5,
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
    abstract fun lockoutDao(): LockoutDao
    abstract fun pointEventDao(): PointEventDao

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

        /**
         * ルールに端末をまたいで一意な ID を足した(ver.0.5)。
         *
         * 既存の行は空のまま入る。起動時の backfill で振る。
         * ここで振らないのは、SQLite に UUID を作る手段が無いため。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `rules` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * 罰(封鎖)とポイントを足した(ver.0.6)。
         *
         * ルールに consequenceJson を1列足し、テーブルを2つ増やすだけ。既存の行は
         * 空文字のまま入り、読むときに「罰なし・ポイントは設定の既定値」に落ちる。
         * 更新した瞬間に黙って封鎖が始まることはない。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_3_4_SQL.forEach { db.execSQL(it) }
            }
        }

        /**
         * 3→4 で流す SQL。
         *
         * 定数として外に出してあるのは、Room が生成した `schemas/4.json` と
         * 突き合わせるテストから読むため。手書きの SQL と生成されたスキーマは
         * ずれても**移行するまで気づけない**ので、機械に見比べさせている。
         *
         * `AUTOINCREMENT` まで含めて Room の生成物と一字一句そろえてある。
         * Room の検証は PRAGMA を見るだけで AUTOINCREMENT の有無を見ないが、
         * 無いと削除した行の id が再利用される ── 検証を通ることと
         * 同じ挙動になることは別なので、生成物をそのまま写す。
         */
        internal val MIGRATION_3_4_SQL: List<String> = listOf(
            "ALTER TABLE `rules` ADD COLUMN `consequenceJson` TEXT NOT NULL DEFAULT ''",
            "CREATE TABLE IF NOT EXISTS `lockouts` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`targetJson` TEXT NOT NULL, " +
                "`untilEpochSec` INTEGER NOT NULL, " +
                "`reason` TEXT NOT NULL, " +
                "`createdAtEpochSec` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_lockouts_untilEpochSec` " +
                "ON `lockouts` (`untilEpochSec`)",
            "CREATE TABLE IF NOT EXISTS `point_events` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`delta` INTEGER NOT NULL, " +
                "`reason` TEXT NOT NULL, " +
                "`note` TEXT NOT NULL, " +
                "`atEpochSec` INTEGER NOT NULL, " +
                "`dedupKey` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_point_events_atEpochSec` " +
                "ON `point_events` (`atEpochSec`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_point_events_dedupKey` " +
                "ON `point_events` (`dedupKey`)",
        )

        /**
         * 4→5。封鎖に uid と earlyExitJson を足すだけ。
         *
         * 既存の行はどちらも空で入る。earlyExitJson が空 = 罰なので、
         * **移行前に科されていた罰が、更新した瞬間に解けるようにはならない。**
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_4_5_SQL.forEach { db.execSQL(it) }
            }
        }

        /** 4→5 で流す SQL。[MIGRATION_3_4_SQL] と同じくテストから読む。 */
        internal val MIGRATION_4_5_SQL: List<String> = listOf(
            "ALTER TABLE `lockouts` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `lockouts` ADD COLUMN `earlyExitJson` TEXT NOT NULL DEFAULT ''",
        )

        @Volatile
        private var instance: DopaDatabase? = null

        fun get(context: Context): DopaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DopaDatabase::class.java,
                    "dopachiru.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }
    }
}
