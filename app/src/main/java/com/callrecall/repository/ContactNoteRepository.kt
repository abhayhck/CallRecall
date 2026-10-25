package com.callrecall.repository

import com.callrecall.database.ContactNoteDao
import com.callrecall.models.ContactNote
import com.callrecall.models.SortOption
import com.callrecall.utils.PhoneNumberUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ContactNoteRepository(
    private val dao: ContactNoteDao
) {
    private val saveMutex = Mutex()

    fun observeAllNotes(): Flow<List<ContactNote>> = dao.observeAllNotes().map { notes ->
        notes.map { canonicalize(it) }
    }

    private fun canonicalize(note: ContactNote): ContactNote = note.copy(
        phoneNumber = PhoneNumberUtils.normalize(note.phoneNumber).ifBlank { note.phoneNumber }
    )

    private fun requirePhone(phone: String): String = PhoneNumberUtils.normalize(phone).also {
        require(it.isNotEmpty()) { "A valid phone number with country code is required" }
    }

    private suspend fun checkDuplicate(phone: String, exceptId: Long = 0L) {
        val duplicate = dao.observeAllNotes().first().any {
            it.id != exceptId && PhoneNumberUtils.normalize(it.phoneNumber) == phone
        }
        if (duplicate) throw DuplicatePhoneNumberException()
    }

    suspend fun getNoteById(id: Long): ContactNote? = dao.getNoteById(id)?.let { canonicalize(it) }

    suspend fun getNoteByPhone(phoneNumber: String): ContactNote? {
        val normalized = PhoneNumberUtils.normalize(phoneNumber)
        if (normalized.isEmpty()) return null
        // Existing development notes may still have local/digits-only phone keys.
        val note = dao.getNoteByPhone(normalized) ?: dao.observeAllNotes().first().firstOrNull {
            PhoneNumberUtils.normalize(it.phoneNumber) == normalized
        }
        return note?.let { canonicalize(it) }
    }

    suspend fun existsForPhone(phoneNumber: String): Boolean {
        return getNoteByPhone(phoneNumber) != null
    }

    suspend fun insert(note: ContactNote): Long {
        val phone = requirePhone(note.phoneNumber)
        checkDuplicate(phone)
        return dao.insert(note.copy(phoneNumber = phone))
    }

    suspend fun update(note: ContactNote) {
        val phone = requirePhone(note.phoneNumber)
        checkDuplicate(phone, note.id)
        dao.update(
            note.copy(
                phoneNumber = phone,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun saveNote(
        id: Long,
        phoneNumber: String,
        name: String,
        noteContent: String
    ): Long = saveMutex.withLock {
        val normalizedPhone = requirePhone(phoneNumber)
        val now = System.currentTimeMillis()
        if (id > 0) {
            checkDuplicate(normalizedPhone, id)
            val existing = dao.getNoteById(id) ?: throw IllegalStateException("Note not found")
            dao.update(
                existing.copy(
                    phoneNumber = normalizedPhone,
                    name = name,
                    noteContent = noteContent,
                    updatedAt = now
                )
            )
            id
        } else {
            val existingByPhone = getNoteByPhone(normalizedPhone)
            if (existingByPhone != null) {
                dao.update(
                    existingByPhone.copy(
                        name = name,
                        noteContent = noteContent,
                        updatedAt = now
                    )
                )
                existingByPhone.id
            } else {
                dao.insert(
                    ContactNote(
                        phoneNumber = normalizedPhone,
                        name = name,
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
            val phoneQuery = if (trimmed.all { it.isDigit() || it.isWhitespace() || it in "+()-./" }) {
                PhoneNumberUtils.digitsOnly(trimmed)
            } else ""
            notes.filter { note ->
                note.name.lowercase().contains(lower) ||
                    note.phoneNumber.contains(trimmed) ||
                    (phoneQuery.isNotEmpty() && PhoneNumberUtils.digitsOnly(note.phoneNumber).contains(phoneQuery))
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

class DuplicatePhoneNumberException : IllegalArgumentException("Another note already uses this phone number")
