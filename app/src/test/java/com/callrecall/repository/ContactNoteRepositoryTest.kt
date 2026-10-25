package com.callrecall.repository

import com.callrecall.database.ContactNoteDao
import com.callrecall.models.ContactNote
import com.callrecall.models.SortOption
import com.callrecall.utils.PhoneNumberUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ContactNoteRepositoryTest {
    private lateinit var dao: FakeDao
    private lateinit var repository: ContactNoteRepository

    @Before fun setup() {
        PhoneNumberUtils.setDefaultRegion("IN")
        dao = FakeDao()
        repository = ContactNoteRepository(dao)
    }

    @Test fun saveAndCallLookupUseSameInternationalKey() = runBlocking {
        val id = repository.saveNote(0, "9876543210", "Alice", "Call tomorrow")
        assertEquals("+919876543210", dao.notes.value.single().phoneNumber)
        listOf("9876543210", "919876543210", "+91 98765 43210").forEach {
            assertEquals(id, repository.getNoteByPhone(it)?.id)
            assertTrue(repository.existsForPhone(it))
        }
        repository.saveNote(0, "+919876543210", "Alice", "Updated")
        assertEquals(1, dao.notes.value.size)
        assertEquals("Updated", dao.notes.value.single().noteContent)
        assertFalse(repository.existsForPhone("+442079460018"))
    }

    @Test fun overlappingSavesCannotCreateDuplicatePhoneNotes() = runBlocking {
        val ids = (1..20).map { index ->
            async {
                val phone = if (index % 2 == 0) "9876543210" else "+919876543210"
                repository.saveNote(0, phone, "Alice", "Note $index")
            }
        }.awaitAll()
        assertEquals(1, ids.toSet().size)
        assertEquals(1, dao.notes.value.size)
        assertEquals("+919876543210", dao.notes.value.single().phoneNumber)
    }

    @Test fun foreignNotesKeepTheirOwnCountryCode() = runBlocking {
        repository.saveNote(0, "+44 20 7946 0018", "London", "UK note")
        assertEquals("+442079460018", dao.notes.value.single().phoneNumber)
        assertTrue(repository.existsForPhone("0044 20 7946 0018"))
    }

    @Test fun existingLocalNotesAreMatchedAndCanonicalizedOnSave() = runBlocking {
        dao.insert(ContactNote(id = 7, phoneNumber = "9876543210", name = "Alice", noteContent = "Keep me"))
        assertEquals(7L, repository.getNoteByPhone("+919876543210")?.id)
        assertEquals("+919876543210", repository.observeAllNotes().first().single().phoneNumber)
        repository.saveNote(0, "919876543210", "Alice", "Keep me")
        assertEquals(1, dao.notes.value.size)
        assertEquals(7L, dao.notes.value.single().id)
        assertEquals("+919876543210", dao.notes.value.single().phoneNumber)
    }

    @Test fun conflictingEditsDoNotOverwriteAnotherNote() = runBlocking {
        val first = repository.saveNote(0, "9876543210", "Alice", "First")
        val second = repository.saveNote(0, "+442079460018", "Bob", "Second")
        try {
            repository.saveNote(second, "919876543210", "Bob", "Changed")
            fail("Expected duplicate phone rejection")
        } catch (_: DuplicatePhoneNumberException) { }
        assertEquals("First", dao.getNoteById(first)?.noteContent)
        assertEquals("Second", dao.getNoteById(second)?.noteContent)
    }

    @Test fun invalidAndHiddenNumbersCannotBeStored() = runBlocking {
        listOf("", "123", "private", "*123#").forEach {
            try {
                repository.saveNote(0, it, "Name", "Note")
                fail("Expected invalid phone rejection for $it")
            } catch (_: IllegalArgumentException) { }
        }
        assertTrue(dao.notes.value.isEmpty())
        assertNull(repository.getNoteByPhone(""))
    }

    @Test fun searchAcceptsInternationalFormatting() {
        val notes = listOf(ContactNote(phoneNumber = "+919876543210", name = "Alice"))
        assertEquals(notes, repository.filterAndSort(notes, "+91 98765", SortOption.NAME_A_Z))
        assertEquals(notes, repository.filterAndSort(notes, "98765-43210", SortOption.NAME_A_Z))
        assertTrue(repository.filterAndSort(notes, "Unknown", SortOption.NAME_A_Z).isEmpty())
    }

    private class FakeDao : ContactNoteDao {
        val notes = MutableStateFlow<List<ContactNote>>(emptyList())
        override fun observeAllNotes() = notes
        override suspend fun getAllNotes() = notes.value.sortedBy { it.id }
        override suspend fun getNoteById(id: Long) = notes.value.find { it.id == id }
        override suspend fun getNoteByPhone(phoneNumber: String) = notes.value.find { it.phoneNumber == phoneNumber }
        override suspend fun existsForPhone(phoneNumber: String) = getNoteByPhone(phoneNumber) != null
        override suspend fun insert(note: ContactNote): Long {
            yield()
            check(notes.value.none { it.phoneNumber == note.phoneNumber })
            val id = note.id.takeIf { it > 0 } ?: ((notes.value.maxOfOrNull { it.id } ?: 0L) + 1L)
            notes.value = notes.value + note.copy(id = id)
            return id
        }
        override suspend fun update(note: ContactNote) {
            notes.value = notes.value.map { if (it.id == note.id) note else it }
        }
        override suspend fun delete(note: ContactNote) {
            notes.value = notes.value.filterNot { it.id == note.id }
        }
    }
}
