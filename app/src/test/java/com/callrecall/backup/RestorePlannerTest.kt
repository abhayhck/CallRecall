package com.callrecall.backup

import com.callrecall.models.ContactNote
import com.callrecall.utils.PhoneNumberUtils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RestorePlannerTest {
    private val alice = ContactNote(id = 7, phoneNumber = "+919876543210", name = "Alice", noteContent = "Current", createdAt = 100, updatedAt = 200)
    private val london = ContactNote(id = 9, phoneNumber = "+442079460018", name = "London", noteContent = "UK", createdAt = 300, updatedAt = 400)
    @Before fun setup() { PhoneNumberUtils.setDefaultRegion("IN") }

    @Test fun freshPhoneReceivesAllNotesWithNewLocalIds() {
        val backup = BackupData(500, listOf(alice, london))
        val preview = RestorePlanner.preview(backup, emptyList())
        val changes = RestorePlanner.changes(preview, emptySet(), emptyList())
        assertEquals(listOf(alice.copy(id = 0), london.copy(id = 0)), changes.additions)
        assertEquals(RestoreResult(2, 0, 0, 0), changes.result)
    }

    @Test fun identicalContentIsSkippedEvenIfDatesDiffer() {
        val preview = RestorePlanner.preview(BackupData(500, listOf(alice.copy(updatedAt = 400))), listOf(alice))
        assertEquals(1, preview.identicalCount)
        assertTrue(preview.conflicts.isEmpty())
        assertTrue(RestorePlanner.changes(preview, emptySet(), listOf(alice)).replacements.isEmpty())
    }

    @Test fun conflictsKeepCurrentNoteByDefaultAndOtherNotesAreUntouched() {
        val incoming = alice.copy(id = 55, noteContent = "Backup text")
        val existing = listOf(alice, london)
        val preview = RestorePlanner.preview(BackupData(500, listOf(incoming)), existing)
        val changes = RestorePlanner.changes(preview, emptySet(), existing)
        assertEquals(1, preview.conflicts.size)
        assertTrue(changes.additions.isEmpty())
        assertTrue(changes.replacements.isEmpty())
        assertEquals(RestoreResult(0, 0, 1, 0), changes.result)
    }

    @Test fun explicitReplacementKeepsLocalIdAndRestoresBackupContentAndDates() {
        val incoming = alice.copy(id = 55, name = "Backup name", noteContent = "Backup text", createdAt = 20, updatedAt = 50)
        val preview = RestorePlanner.preview(BackupData(500, listOf(incoming, london)), listOf(alice))
        val changes = RestorePlanner.changes(preview, setOf(alice.phoneNumber), listOf(alice))
        assertEquals(listOf(incoming.copy(id = alice.id)), changes.replacements)
        assertEquals(listOf(london.copy(id = 0)), changes.additions)
        assertEquals(RestoreResult(1, 1, 0, 0), changes.result)
    }

    @Test fun individualConflictChoicesReplaceOnlyTheSelectedNote() {
        val existing = listOf(alice, london)
        val incoming = existing.map { it.copy(name = "Backup " + it.name, noteContent = "Backup version") }
        val preview = RestorePlanner.preview(BackupData(500, incoming), existing)
        val changes = RestorePlanner.changes(preview, setOf(london.phoneNumber), existing.reversed())
        assertEquals(listOf(incoming[1]), changes.replacements)
        assertEquals(RestoreResult(0, 1, 1, 0), changes.result)
        assertEquals("Current", alice.noteContent)
        assertEquals("UK", london.noteContent)
    }

    @Test fun localAndCountryPrefixedNumbersAreOneIdentity() {
        val local = alice.copy(phoneNumber = "9876543210")
        val preview = RestorePlanner.preview(BackupData(500, listOf(alice.copy(noteContent = "Backup"))), listOf(local))
        assertTrue(preview.additions.isEmpty())
        assertEquals(1, preview.conflicts.size)
        val changes = RestorePlanner.changes(preview, setOf(alice.phoneNumber), listOf(local))
        assertEquals("+919876543210", changes.replacements.single().phoneNumber)
        assertEquals(local.id, changes.replacements.single().id)
    }

    @Test fun concurrentEditAdditionOrDeletionRequiresFreshPreview() {
        val preview = RestorePlanner.preview(BackupData(500, listOf(london)), listOf(alice))
        val changedLists = listOf(listOf(alice.copy(noteContent = "Edited")), listOf(alice, london), emptyList())
        changedLists.forEach {
            assertThrows(RestoreChangedException::class.java) { RestorePlanner.changes(preview, emptySet(), it) }
        }
    }

    @Test fun exactDuplicateBackupKeysAreRejectedButExistingAliasesStayUntouched() {
        assertThrows(InvalidBackupException::class.java) { RestorePlanner.preview(BackupData(500, listOf(alice, alice)), emptyList()) }
        val existing = listOf(alice, alice.copy(id = 11, phoneNumber = "9876543210", noteContent = "Separate legacy note"))
        val preview = RestorePlanner.preview(BackupData(500, listOf(alice)), existing)
        val changes = RestorePlanner.changes(preview, emptySet(), existing)
        assertEquals(1, preview.identicalCount)
        assertTrue(changes.additions.isEmpty())
        assertTrue(changes.replacements.isEmpty())
    }

    @Test fun restoringAliasesToANewPhoneKeepsEveryDistinctNote() {
        val backupNotes = listOf(
            alice.copy(id = 0, phoneNumber = "9876543210", noteContent = "Local note"),
            alice.copy(id = 0, phoneNumber = "919876543210", noteContent = "Prefixed note"),
            alice.copy(id = 0, noteContent = "International note")
        )
        val preview = RestorePlanner.preview(BackupData(500, backupNotes), emptyList())
        val changes = RestorePlanner.changes(preview, emptySet(), emptyList())
        assertEquals(backupNotes, changes.additions)
        assertEquals(3, preview.legacyNumberCount)
        assertEquals(RestoreResult(3, 0, 0, 0), changes.result)
    }

    @Test fun singleLegacyNumberRetainsItsOriginalValueInsteadOfGuessingACountry() {
        val original = alice.copy(id = 0, phoneNumber = "9876543210")
        val preview = RestorePlanner.preview(BackupData(500, listOf(original)), emptyList())
        assertEquals(listOf(original), preview.additions)
        assertEquals(1, preview.legacyNumberCount)
    }

    @Test fun legacyHiddenOrShortNumbersArePreservedRatherThanDiscarded() {
        val backupNotes = listOf(alice.copy(id = 0, phoneNumber = ""), london.copy(id = 0, phoneNumber = "1234567"))
        val preview = RestorePlanner.preview(BackupData(500, backupNotes), emptyList())
        assertEquals(backupNotes, RestorePlanner.changes(preview, emptySet(), emptyList()).additions)
        assertEquals(2, preview.legacyNumberCount)
    }
}
