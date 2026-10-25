package com.callrecall.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.callrecall.models.ContactNote
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactNoteDao {

    @Query("SELECT * FROM contact_notes ORDER BY updatedAt DESC")
    fun observeAllNotes(): Flow<List<ContactNote>>

    @Query("SELECT * FROM contact_notes ORDER BY id")
    suspend fun getAllNotes(): List<ContactNote>

    @Query("SELECT * FROM contact_notes WHERE id = :id LIMIT 1")
    suspend fun getNoteById(id: Long): ContactNote?

    @Query("SELECT * FROM contact_notes WHERE phoneNumber = :phoneNumber LIMIT 1")
    suspend fun getNoteByPhone(phoneNumber: String): ContactNote?

    @Query("SELECT EXISTS(SELECT 1 FROM contact_notes WHERE phoneNumber = :phoneNumber)")
    suspend fun existsForPhone(phoneNumber: String): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(note: ContactNote): Long

    @Update
    suspend fun update(note: ContactNote)

    @Delete
    suspend fun delete(note: ContactNote)
}
