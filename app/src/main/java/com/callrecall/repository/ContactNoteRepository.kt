package com.callrecall.repository

import com.callrecall.database.ContactNoteDao
import com.callrecall.models.ContactNote
import com.callrecall.models.SortOption
import com.callrecall.utils.PhoneNumberUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ContactNoteRepository(
    private val dao: ContactNoteDao
) {

    fun observeAllNotes(): Flow<List<ContactNote>> = dao.observeAllNotes()

    suspend fun getNoteById(id: Long): ContactNote? = dao.getNoteById(id)

    suspend fun getNoteByPhone(phoneNumber: String): ContactNote? {
        val normalized = PhoneNumberUtils.normalize(phoneNumber)
        return dao.getNoteByPhone(normalized)
    }

    suspend fun existsForPhone(phoneNumber: String): Boolean {
        return dao.existsForPhone(PhoneNumberUtils.normalize(phoneNumber))
    }

    suspend fun insert(note: ContactNote): Long {
        return dao.insert(note.copy(phoneNumber = PhoneNumberUtils.normalize(note.phoneNumber)))
    }

    suspend fun update(note: ContactNote) {
        dao.update(
            note.copy(
                phoneNumber = PhoneNumberUtils.normalize(note.phoneNumber),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun saveNote(
        id: Long,
        phoneNumber: String,
        contactName: String,
        customDisplayName: String,
        noteContent: String
    ): Long {
        val normalizedPhone = PhoneNumberUtils.normalize(phoneNumber)
        if (normalizedPhone.isEmpty()) {
            throw IllegalArgumentException("Phone number required to save a note")
        }
        val now = System.currentTimeMillis()
        return if (id > 0) {
            val existing = dao.getNoteById(id) ?: throw IllegalStateException("Note not found")
            dao.update(
                existing.copy(
                    phoneNumber = normalizedPhone,
                    contactName = contactName,
                    customDisplayName = customDisplayName,
                    noteContent = noteContent,
                    updatedAt = now
                )
            )
            id
        } else {
            val existingByPhone = dao.getNoteByPhone(normalizedPhone)
            if (existingByPhone != null) {
                dao.update(
                    existingByPhone.copy(
                        contactName = contactName,
                        customDisplayName = customDisplayName,
                        noteContent = noteContent,
                        updatedAt = now
                    )
                )
                existingByPhone.id
            } else {
                dao.insert(
                    ContactNote(
                        phoneNumber = normalizedPhone,
                        contactName = contactName,
                        customDisplayName = customDisplayName,
                        noteContent = noteContent,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
        }
    }

    suspend fun delete(note: ContactNote) {
        dao.delete(note)
    }

    fun filterAndSort(
        notes: List<ContactNote>,
        query: String,
        sortOption: SortOption
    ): List<ContactNote> {
        val trimmed = query.trim()
        val filtered = if (trimmed.isEmpty()) {
            notes
        } else {
            val lower = trimmed.lowercase()
            notes.filter { note ->
                note.contactName.lowercase().contains(lower) ||
                    note.customDisplayName.lowercase().contains(lower) ||
                    note.phoneNumber.contains(trimmed) ||
                    PhoneNumberUtils.digitsOnly(note.phoneNumber).contains(trimmed)
            }
        }
        return when (sortOption) {
            SortOption.RECENTLY_UPDATED -> filtered.sortedByDescending { it.updatedAt }
            SortOption.OLDEST_UPDATED -> filtered.sortedBy { it.updatedAt }
            SortOption.NAME_A_Z -> filtered.sortedBy { it.displayLabel().lowercase() }
            SortOption.NAME_Z_A -> filtered.sortedByDescending { it.displayLabel().lowercase() }
        }
    }
}
