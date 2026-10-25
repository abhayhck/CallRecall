package com.callrecall.backup

import com.callrecall.models.ContactNote
import com.callrecall.utils.PhoneNumberUtils

data class RestoreConflict(val existing: ContactNote, val incoming: ContactNote)
data class RestorePreview(
    val backup: BackupData,
    val existingSnapshot: List<ContactNote>,
    val additions: List<ContactNote>,
    val identicalCount: Int,
    val conflicts: List<RestoreConflict>,
    val legacyNumberCount: Int = 0
)
data class RestoreChanges(val additions: List<ContactNote>, val replacements: List<ContactNote>, val result: RestoreResult)
data class RestoreResult(val added: Int, val replaced: Int, val kept: Int, val identical: Int)

object RestorePlanner {
    fun changes(preview: RestorePreview, replacePhones: Set<String>, current: List<ContactNote>): RestoreChanges {
        if (current.sortedBy { it.id } != preview.existingSnapshot.sortedBy { it.id }) {
            throw RestoreChangedException()
        }
        val replacements = preview.conflicts.filter { it.incoming.phoneNumber in replacePhones }.map {
            it.incoming.copy(id = it.existing.id)
        }
        val result = RestoreResult(preview.additions.size, replacements.size,
            preview.conflicts.size - replacements.size, preview.identicalCount)
        return RestoreChanges(preview.additions.map { it.copy(id = 0) }, replacements, result)
    }

    fun preview(backup: BackupData, existing: List<ContactNote>): RestorePreview {
        BackupCodec.validate(backup.notes)
        fun key(note: ContactNote): String = PhoneNumberUtils.normalize(note.phoneNumber).ifBlank { note.phoneNumber }
        val incomingByPhone = backup.notes.groupBy { key(it) }
        val existingByPhone = existing.groupBy { key(it) }
        val existingByRawPhone = existing.associateBy { it.phoneNumber }
        val additions = mutableListOf<ContactNote>()
        val conflicts = mutableListOf<RestoreConflict>()
        var identical = 0
        var legacy = 0
        backup.notes.forEach { original ->
            val canonical = key(original)
            val ambiguous = incomingByPhone[canonical].orEmpty().size > 1 ||
                existingByPhone[canonical].orEmpty().size > 1
            // Ambiguous aliases stay separate and match only their exact stored key.
            // This preserves every old note instead of merging or overwriting its content.
            val incoming = original.copy(id = 0)
            val current = if (ambiguous) existingByRawPhone[original.phoneNumber]
                else existingByPhone[canonical]?.singleOrNull()
            if (ambiguous || PhoneNumberUtils.normalize(original.phoneNumber) != original.phoneNumber ||
                original.phoneNumber.isBlank()) legacy++
            when {
                current == null -> additions.add(incoming)
                current.name == incoming.name && current.noteContent == incoming.noteContent -> identical++
                else -> conflicts.add(RestoreConflict(current, incoming))
            }
        }
        return RestorePreview(backup, existing.toList(), additions, identical, conflicts, legacy)
    }
}
