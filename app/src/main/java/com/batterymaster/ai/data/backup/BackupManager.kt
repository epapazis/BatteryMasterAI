package com.batterymaster.ai.data.backup

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.sqlite.db.SimpleSQLiteQuery
import com.batterymaster.ai.data.db.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

/**
 * One-file backup of all recorded history (the SQLite database). Restoring replaces the
 * current history and restarts the app. Settings are not included.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
) {
    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        // Flush the write-ahead log so the single .db file is complete.
        db.openHelper.writableDatabase.query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")).use { it.moveToFirst() }
        val file = context.getDatabasePath(AppDatabase.NAME)
        context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: error("Could not open the selected file")
    }

    /** Validates the file, swaps it in, and restarts the app. Throws with a readable message on bad input. */
    suspend fun restore(uri: Uri) {
        val temp = withContext(Dispatchers.IO) {
            val t = File(context.cacheDir, "restore.db")
            context.contentResolver.openInputStream(uri)?.use { input -> t.outputStream().use { input.copyTo(it) } }
                ?: error("Could not open the selected file")
            validate(t)
            t
        }
        withContext(Dispatchers.IO) {
            db.close()
            val target = context.getDatabasePath(AppDatabase.NAME)
            File(target.path + "-wal").delete()
            File(target.path + "-shm").delete()
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        restart()
    }

    private fun validate(f: File) {
        val header = ByteArray(16)
        f.inputStream().use { if (it.read(header) != 16) error("This isn't a Battery Master AI backup.") }
        if (String(header, Charsets.US_ASCII) != "SQLite format 3\u0000") error("This isn't a Battery Master AI backup.")
        val sqlite = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val version = sqlite.version
            if (version > CURRENT_VERSION) error("This backup comes from a newer version of the app. Update the app first.")
            sqlite.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='samples'", null).use {
                if (!it.moveToFirst()) error("This isn't a Battery Master AI backup.")
            }
        } finally {
            sqlite.close()
        }
    }

    private fun restart() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (intent != null) context.startActivity(intent)
        exitProcess(0)
    }

    private companion object {
        const val CURRENT_VERSION = 2
    }
}
