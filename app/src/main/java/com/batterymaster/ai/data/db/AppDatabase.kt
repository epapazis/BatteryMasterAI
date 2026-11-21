package com.batterymaster.ai.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        SampleEntity::class,
        SessionEntity::class,
        CapacityEstimateEntity::class,
        ProtectionEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sampleDao(): SampleDao
    abstract fun sessionDao(): SessionDao
    abstract fun capacityDao(): CapacityDao
    abstract fun protectionDao(): ProtectionDao

    companion object {
        const val NAME = "battery_master.db"

        /**
         * Every schema change must bump `version` and add a Migration here (or an
         * @AutoMigration). Exported schemas live in app/schemas and are used by
         * MigrationTest. Destructive migration is deliberately NOT enabled: users'
         * history must survive upgrades.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
