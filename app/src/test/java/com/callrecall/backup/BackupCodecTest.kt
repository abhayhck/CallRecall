package com.callrecall.backup

import com.callrecall.models.ContactNote
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class BackupCodecTest {
    private val notes = listOf(
        ContactNote(id = 99, phoneNumber = "+919876543210", name = "आशा 🌿", noteContent = "Line one\nLine two — café", createdAt = 100, updatedAt = 200),
        ContactNote(id = 3, phoneNumber = "+442079460018", name = "", noteContent = "", createdAt = 300, updatedAt = 400)
    )
    private val password = "Café-秘密-🌿-password".toCharArray()

    @Test fun plainBackupRoundTripsWithoutPasswordsOrLocalIds() {
        val bytes = BackupCodec.encode(notes, exportedAt = 500)
        assertFalse(BackupCodec.isEncrypted(bytes))
        val decoded = BackupCodec.decode(bytes)
        assertEquals(500L, decoded.exportedAt)
        assertEquals(notes.map { it.copy(id = 0) }, decoded.notes)
    }

    @Test fun encryptedBackupRoundTripsUnicodePasswordsOnANewInstance() {
        val bytes = BackupCodec.encode(notes, password, 500)
        assertTrue(BackupCodec.isEncrypted(bytes))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Line one"))
        assertEquals(notes.map { it.copy(id = 0) }, BackupCodec.decode(bytes, password.copyOf()).notes)
        assertTrue(password.contentEquals("Café-秘密-🌿-password".toCharArray()))
    }

    @Test fun encryptionUsesFreshSaltAndNonceForEveryFile() {
        val first = BackupCodec.encode(notes, password, 500)
        val second = BackupCodec.encode(notes, password, 500)
        assertFalse(first.contentEquals(second))
        assertEquals(BackupCodec.decode(first, password), BackupCodec.decode(second, password))
    }

    @Test fun encryptedBackupRequiresItsPassword() {
        val file = BackupCodec.encode(notes, password)
        assertThrows(BackupPasswordRequiredException::class.java) { BackupCodec.decode(file) }
        assertThrows(IncorrectBackupPasswordException::class.java) { BackupCodec.decode(file, "wrong-password".toCharArray()) }
    }

    @Test fun ciphertextAndExportMetadataAreAuthenticated() {
        val file = BackupCodec.encode(notes, password, 500)
        val changedCiphertext = file.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(IncorrectBackupPasswordException::class.java) { BackupCodec.decode(changedCiphertext, password) }
        // Export timestamp starts at byte 13. Keep it valid but change the authenticated header.
        val changedHeader = file.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertThrows(IncorrectBackupPasswordException::class.java) { BackupCodec.decode(changedHeader, password) }
    }

    @Test fun plaintextCorruptionAndTrailingBytesAreRejected() {
        val file = BackupCodec.encode(notes)
        val damaged = file.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(InvalidBackupException::class.java) { BackupCodec.decode(damaged) }
        assertThrows(InvalidBackupException::class.java) { BackupCodec.decode(file + byteArrayOf(1)) }
    }

    @Test fun truncatedAndUnrelatedFilesAreRejected() {
        val file = BackupCodec.encode(notes)
        listOf(byteArrayOf(), "not a backup".toByteArray(), file.copyOf(24), file.copyOf(file.size - 1)).forEach {
            assertThrows(InvalidBackupException::class.java) { BackupCodec.decode(it) }
        }
    }

    @Test fun unsupportedVersionsAndMaliciousWorkFactorsAreRejectedBeforeKdf() {
        val file = BackupCodec.encode(notes, password)
        val futureVersion = file.copyOf().also { ByteBuffer.wrap(it, 8, 4).putInt(999) }
        assertThrows(UnsupportedBackupVersionException::class.java) { BackupCodec.decode(futureVersion, password) }
        val maliciousIterations = file.copyOf().also { ByteBuffer.wrap(it, 21, 4).putInt(Int.MAX_VALUE) }
        assertThrows(InvalidBackupException::class.java) { BackupCodec.decode(maliciousIterations, password) }
        val maliciousLength = file.copyOf().also { ByteBuffer.wrap(it, 53, 4).putInt(Int.MAX_VALUE) }
        assertThrows(InvalidBackupException::class.java) { BackupCodec.decode(maliciousLength, password) }
    }

    @Test fun existingVersionOneFilesRemainReadable() {
        val note = notes.first().copy(id = 0)
        val payload = java.io.ByteArrayOutputStream().also { buffer ->
            java.io.DataOutputStream(buffer).use { out ->
                out.writeInt(1)
                listOf(note.phoneNumber, note.name, note.noteContent).forEach { text ->
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    out.writeInt(bytes.size); out.write(bytes)
                }
                out.writeLong(note.createdAt); out.writeLong(note.updatedAt)
            }
        }.toByteArray()
        assertEquals(listOf(note), BackupCodec.decode(plainEnvelope(payload)).notes)
    }

    @Test fun maliciousPayloadCountsAndStringLengthsAreRejectedWithoutAllocatingThem() {
        fun payload(block: java.io.DataOutputStream.() -> Unit): ByteArray =
            java.io.ByteArrayOutputStream().also { buffer -> java.io.DataOutputStream(buffer).use { it.block() } }.toByteArray()
        val negativeCount = payload { writeInt(-1) }
        val hugeCount = payload { writeInt(Int.MAX_VALUE) }
        val hugeField = payload {
            writeInt(1); writeInt(Int.MAX_VALUE); write(ByteArray(32))
        }
        listOf(negativeCount, hugeCount, hugeField).forEach {
            assertThrows(InvalidBackupException::class.java) { BackupCodec.decode(plainEnvelope(it)) }
        }
    }

    @Test fun malformedUtf8IsRejectedEvenWithAValidChecksum() {
        val payload = java.io.ByteArrayOutputStream().also { buffer ->
            java.io.DataOutputStream(buffer).use {
                it.writeInt(1)
                it.writeInt(1); it.writeByte(0xFF)
                it.writeInt(0); it.writeInt(0)
                it.writeLong(100); it.writeLong(200)
            }
        }.toByteArray()
        assertThrows(InvalidBackupException::class.java) { BackupCodec.decode(plainEnvelope(payload)) }
    }

    private fun plainEnvelope(payload: ByteArray): ByteArray {
        val header = java.io.ByteArrayOutputStream().also { buffer ->
            java.io.DataOutputStream(buffer).use {
                it.write("CRBACKUP".toByteArray(Charsets.US_ASCII)); it.writeInt(1)
                it.writeByte(0); it.writeLong(500); it.writeInt(payload.size)
            }
        }.toByteArray()
        val data = header + payload
        return data + java.security.MessageDigest.getInstance("SHA-256").digest(data)
    }

    @Test fun onlyExactDuplicateStoredKeysAreRejected() {
        assertThrows(InvalidBackupException::class.java) { BackupCodec.encode(notes + notes.first()) }
    }

    @Test fun legacyLocalPrefixedAndUnresolvableNumbersAreBackedUpWithoutDataLoss() {
        val legacy = listOf(
            notes.first().copy(id = 1, phoneNumber = "9876543210", noteContent = "Local note"),
            notes.first().copy(id = 2, phoneNumber = "919876543210", noteContent = "Prefixed note"),
            notes.first().copy(id = 3, phoneNumber = "+919876543210", noteContent = "International note"),
            notes.first().copy(id = 4, phoneNumber = "1234567", noteContent = "Old short number"),
            notes.first().copy(id = 5, phoneNumber = "", noteContent = "Old hidden caller")
        )
        val decoded = BackupCodec.decode(BackupCodec.encode(legacy))
        assertEquals(legacy.map { it.copy(id = 0) }, decoded.notes)
    }

    @Test fun encryptedBackupsAlsoPreserveLegacyValues() {
        val legacy = listOf(notes.first().copy(phoneNumber = "old saved number", createdAt = -1, updatedAt = Long.MAX_VALUE))
        assertEquals(legacy.map { it.copy(id = 0) }, BackupCodec.decode(BackupCodec.encode(legacy, password), password).notes)
    }

    @Test fun emptyBackupsAndLongMultilingualNotesAreSupported() {
        assertTrue(BackupCodec.decode(BackupCodec.encode(emptyList())).notes.isEmpty())
        val long = notes.first().copy(id = 0, noteContent = "Long note 🌿\n".repeat(10_000))
        assertEquals(listOf(long), BackupCodec.decode(BackupCodec.encode(listOf(long))).notes)
    }

    @Test fun unusualStoredTimestampsArePreservedButResourceLimitsStillApply() {
        val note = notes.first().copy(id = 0, createdAt = -1, updatedAt = Long.MAX_VALUE)
        assertEquals(listOf(note), BackupCodec.decode(BackupCodec.encode(listOf(note))).notes)
        assertThrows(BackupTooLargeException::class.java) { BackupCodec.encode(listOf(notes.first().copy(name = "x".repeat(16 * 1024 + 1)))) }
    }
}
