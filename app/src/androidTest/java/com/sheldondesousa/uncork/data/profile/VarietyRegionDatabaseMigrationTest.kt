package com.sheldondesousa.uncork.data.profile

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VarietyRegionDatabaseMigrationTest {
    @Test
    fun migration1To2PreservesRowsAndAddsCountryToTheKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(TEST_DATABASE)

        val legacyHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DATABASE)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `VarietyRegionProfile` (
                                    `variety` TEXT NOT NULL,
                                    `province` TEXT NOT NULL,
                                    `body` TEXT,
                                    `tannin` TEXT,
                                    `acidity` TEXT,
                                    `flavorNotes` TEXT NOT NULL,
                                    `generatedBy` TEXT NOT NULL,
                                    `generatedAt` TEXT NOT NULL,
                                    `source` TEXT NOT NULL,
                                    PRIMARY KEY(`variety`, `province`)
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                INSERT INTO `VarietyRegionProfile` (
                                    `variety`, `province`, `body`, `tannin`, `acidity`,
                                    `flavorNotes`, `generatedBy`, `generatedAt`, `source`
                                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """.trimIndent(),
                                arrayOf(
                                    "Nebbiolo",
                                    "Piedmont",
                                    "full",
                                    "high",
                                    "high",
                                    "[\"red cherry\",\"rose petal\"]",
                                    "legacy-test",
                                    "2026-09-14T00:00:00Z",
                                    "kaggle_derived",
                                ),
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        legacyHelper.writableDatabase
        legacyHelper.close()

        val migratedDatabase = Room.databaseBuilder(
            context,
            VarietyRegionDatabase::class.java,
            TEST_DATABASE,
        ).addMigrations(
            VarietyRegionDatabase.MIGRATION_1_2,
            VarietyRegionDatabase.MIGRATION_2_3,
        )
            .build()

        try {
            val migrated = runBlocking {
                migratedDatabase.varietyRegionProfileDao()
                    .find("Unknown", "Piedmont", "Nebbiolo")
            }
            assertNotNull(migrated)
            assertEquals("Unknown", migrated?.country)
            assertEquals(listOf("red cherry", "rose petal"), migrated?.flavorNotes)
            assertEquals(null, migrated?.cachedWineName)
            assertEquals(null, migrated?.webSummary)
        } finally {
            migratedDatabase.close()
            context.deleteDatabase(TEST_DATABASE)
        }
    }

    private companion object {
        const val TEST_DATABASE = "variety-region-migration-test.db"
    }
}
