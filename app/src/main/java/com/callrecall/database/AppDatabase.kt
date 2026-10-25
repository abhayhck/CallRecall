package com.callrecall.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.callrecall.models.ContactNote

@Database(
    entities = [ContactNote::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun contactNoteDao(): ContactNoteDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Development builds used version 1 for both the old and the single-name schema.
        // Upgrade either shape without discarding notes or changing unrelated app data.
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val columns = mutableSetOf<String>()
                db.query("PRAGMA table_info(`contact_notes`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
                }
                if ("name" in columns) return

                check("contactName" in columns && "customDisplayName" in columns) {
                    "Unrecognized contact_notes schema; refusing to discard notes"
                }
                db.execSQL("""
                    CREATE TABLE contact_notes_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        phoneNumber TEXT NOT NULL,
                        name TEXT NOT NULL,
                        noteContent TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO contact_notes_new (id, phoneNumber, name, noteContent, createdAt, updatedAt)
                    SELECT id, phoneNumber,
                        CASE WHEN TRIM(contactName) != '' THEN contactName ELSE customDisplayName END,
                        noteContent, createdAt, updatedAt FROM contact_notes
                """.trimIndent())
                db.execSQL("DROP TABLE contact_notes")
                db.execSQL("ALTER TABLE contact_notes_new RENAME TO contact_notes")
                db.execSQL("CREATE UNIQUE INDEX index_contact_notes_phoneNumber ON contact_notes (phoneNumber)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "call_recall.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
        }
    }
}
