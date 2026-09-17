package com.sheldondesousa.uncork.data.reviews

import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class WineReviewDatabaseInstaller(context: Context) {
    private val applicationContext = context.applicationContext
    private val installMutex = Mutex()
    private val databaseDirectory = File(applicationContext.noBackupFilesDir, DATABASE_DIRECTORY)
    private val databaseFile = File(databaseDirectory, DATABASE_ASSET_NAME)
    private val partialFile = File(databaseDirectory, "$DATABASE_ASSET_NAME.part")
    private var installedFile: File? = null

    suspend fun ensureInstalled(): File = withContext(Dispatchers.IO) {
        installMutex.withLock {
            installedFile?.takeIf(File::isFile)?.let { return@withLock it }
            if (databaseFile.isUsableWineReviewDatabase()) {
                installedFile = databaseFile
                return@withLock databaseFile
            }

            databaseDirectory.mkdirs()
            partialFile.delete()
            databaseFile.delete()

            applicationContext.assets.open(DATABASE_ASSET_NAME).use { input ->
                FileOutputStream(partialFile).buffered(COPY_BUFFER_BYTES).use { output ->
                    input.copyTo(output, COPY_BUFFER_BYTES)
                }
            }

            check(partialFile.isUsableWineReviewDatabase()) {
                "$DATABASE_ASSET_NAME was copied but failed SQLite validation."
            }
            check(partialFile.renameTo(databaseFile)) {
                "$DATABASE_ASSET_NAME could not be moved into app storage."
            }
            databaseFile.also { installedFile = it }
        }
    }

    private fun File.isUsableWineReviewDatabase(): Boolean = isFile && runCatching {
        SQLiteDatabase.openDatabase(absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            DatabaseUtils.longForQuery(database, REVIEW_COUNT_QUERY, null) == EXPECTED_REVIEW_COUNT
        }
    }.getOrDefault(false)

    private companion object {
        const val DATABASE_ASSET_NAME = "wine_reviews.db"
        const val DATABASE_DIRECTORY = "wine-reviews"
        const val COPY_BUFFER_BYTES = 1024 * 1024
        const val EXPECTED_REVIEW_COUNT = 119_030L
        const val REVIEW_COUNT_QUERY = "SELECT COUNT(*) FROM wine_reviews"
    }
}
