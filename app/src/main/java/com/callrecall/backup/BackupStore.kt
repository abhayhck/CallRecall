package com.callrecall.backup

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import com.callrecall.database.AppDatabase
import java.io.ByteArrayOutputStream
import java.io.IOException

class BackupStore(private val database: AppDatabase, private val resolver: ContentResolver) {
    suspend fun export(password: CharArray?): Pair<ByteArray, Int> {
        val notes = database.withTransaction { database.contactNoteDao().getAllNotes() }.map {
            // Export is a lossless snapshot, not a phone-number cleanup operation.
            it.copy(id = 0)
        }
        return BackupCodec.encode(notes, password) to notes.size
    }

    fun read(uri: Uri): ByteArray {
        val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open backup")
        return input.use {
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                if (result.size().toLong() + count > BackupCodec.MAX_FILE_BYTES) throw BackupTooLargeException()
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        }
    }

    fun write(uri: Uri, bytes: ByteArray) {
        val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot create backup")
        output.use { it.write(bytes); it.flush() }
    }

    suspend fun preview(backup: BackupData): RestorePreview =
        RestorePlanner.preview(backup, database.contactNoteDao().getAllNotes())

    suspend fun restore(preview: RestorePreview, replacePhones: Set<String>): RestoreResult = database.withTransaction {
        val dao = database.contactNoteDao()
        // Recheck under the write transaction: an editor may have autosaved since preview.
        val changes = RestorePlanner.changes(preview, replacePhones, dao.getAllNotes())
        changes.additions.forEach { dao.insert(it) }
        changes.replacements.forEach { dao.update(it) }
        changes.result
    }
}
