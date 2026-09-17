package com.sheldondesousa.uncork.data.profile

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [VarietyRegionProfile::class],
    version = 3,
    exportSchema = false,
)
@TypeConverters(StringListConverter::class)
abstract class VarietyRegionDatabase : RoomDatabase() {
    abstract fun varietyRegionProfileDao(): VarietyRegionProfileDao

    companion object {
        @Volatile
        private var instance: VarietyRegionDatabase? = null

        fun getInstance(context: Context): VarietyRegionDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VarietyRegionDatabase::class.java,
                    "variety-region-profiles.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `VarietyRegionProfile_new` (
                        `country` TEXT NOT NULL,
                        `province` TEXT NOT NULL,
                        `variety` TEXT NOT NULL,
                        `body` TEXT,
                        `tannin` TEXT,
                        `acidity` TEXT,
                        `flavorNotes` TEXT NOT NULL,
                        `generatedBy` TEXT NOT NULL,
                        `generatedAt` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        PRIMARY KEY(`country`, `province`, `variety`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `VarietyRegionProfile_new` (
                        `country`, `province`, `variety`, `body`, `tannin`, `acidity`,
                        `flavorNotes`, `generatedBy`, `generatedAt`, `source`
                    )
                    -- Version 1 stored no country. Preserve the row without inferring one.
                    SELECT
                        'Unknown', `province`, `variety`, `body`, `tannin`, `acidity`,
                        `flavorNotes`, `generatedBy`, `generatedAt`, `source`
                    FROM `VarietyRegionProfile`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `VarietyRegionProfile`")
                db.execSQL("ALTER TABLE `VarietyRegionProfile_new` RENAME TO `VarietyRegionProfile`")
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `VarietyRegionProfile` ADD COLUMN `cachedWineName` TEXT")
                db.execSQL("ALTER TABLE `VarietyRegionProfile` ADD COLUMN `cachedWinery` TEXT")
                db.execSQL("ALTER TABLE `VarietyRegionProfile` ADD COLUMN `cachedSuggestedPairing` TEXT")
                db.execSQL("ALTER TABLE `VarietyRegionProfile` ADD COLUMN `webSummary` TEXT")
            }
        }
    }
}
