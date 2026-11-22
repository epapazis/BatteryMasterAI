package com.batterymaster.ai.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SampleEntity::class,
        SessionEntity::class,
        CapacityEstimateEntity::class,
        ProtectionEventEntity::class,
        ChargerTestEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sampleDao(): SampleDao
    abstract fun sessionDao(): SessionDao
    abstract fun capacityDao(): CapacityDao
    abstract fun protectionDao(): ProtectionDao
    abstract fun chargerTestDao(): ChargerTestDao

    companion object {
        const val NAME = "battery_master.db"

        /**
         * Every schema change must bump `version` and add a Migration here (or an
         * @AutoMigration). Exported schemas live in app/schemas and are used by
         * MigrationTest. Destructive migration is deliberately NOT enabled: users'
         * history must survive upgrades.
         */
        /** v1 → v2: foreground app per sample (per-app usage) and charger speed tests. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `samples` ADD COLUMN `foregroundApp` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `charger_tests` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                        "`label` TEXT NOT NULL, `avgCurrentMa` INTEGER NOT NULL, `peakCurrentMa` INTEGER NOT NULL, " +
                        "`avgPowerW` REAL, `startLevel` INTEGER NOT NULL, `endLevel` INTEGER NOT NULL, " +
                        "`temperatureC` REAL, `durationSec` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_charger_tests_timestamp` ON `charger_tests` (`timestamp`)")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
    }
}
