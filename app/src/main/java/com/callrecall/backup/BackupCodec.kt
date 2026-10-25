package com.callrecall.backup

import com.callrecall.models.ContactNote
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class InvalidBackupException : Exception()
class UnsupportedBackupVersionException : Exception()
class BackupPasswordRequiredException : Exception()
class IncorrectBackupPasswordException : Exception()
class BackupTooLargeException : Exception()
class RestoreChangedException : Exception()
class AmbiguousExistingNotesException : Exception()

data class BackupData(val exportedAt: Long, val notes: List<ContactNote>)

/** Portable, versioned file format. No device-bound keys or passwords are saved. */
object BackupCodec {
    const val MAX_FILE_BYTES = 32 * 1024 * 1024
    private const val MAX_NOTES = 50_000
    private const val MAX_CONTENT_BYTES = 8 * 1024 * 1024
    private const val VERSION = 2
    private const val ITERATIONS = 600_000
    private const val MAX_TIMESTAMP = 253402300799999L
    private val magic = "CRBACKUP".toByteArray(Charsets.US_ASCII)

    fun encode(notes: List<ContactNote>, password: CharArray? = null, exportedAt: Long = System.currentTimeMillis()): ByteArray {
        validate(notes)
        valid(exportedAt in 0..MAX_TIMESTAMP)
        val payloadBuffer = ByteArrayOutputStream()
        DataOutputStream(payloadBuffer).use { out ->
            out.writeInt(notes.size)
            notes.forEach { note ->
                writeText(out, payloadBuffer, note.phoneNumber, 4096)
                writeText(out, payloadBuffer, note.name, 16 * 1024)
                writeText(out, payloadBuffer, note.noteContent, MAX_CONTENT_BYTES)
                out.writeLong(note.createdAt)
                out.writeLong(note.updatedAt)
            }
        }
        val payload = payloadBuffer.toByteArray()
        try {
            val encrypted = password != null && password.isNotEmpty()
            val salt = if (encrypted) randomBytes(16) else byteArrayOf()
            val iv = if (encrypted) randomBytes(12) else byteArrayOf()
            val headerBuffer = ByteArrayOutputStream()
            DataOutputStream(headerBuffer).use { out ->
                out.write(magic)
                out.writeInt(VERSION)
                out.writeByte(if (encrypted) 1 else 0)
                out.writeLong(exportedAt)
                if (encrypted) {
                    out.writeInt(ITERATIONS)
                    out.write(salt)
                    out.write(iv)
                }
                out.writeInt(payload.size + if (encrypted) 16 else 0)
            }
            val header = headerBuffer.toByteArray()
            val body = if (encrypted) {
                crypt(Cipher.ENCRYPT_MODE, payload, password!!, salt, iv, header)
            } else payload
            val result = if (encrypted) header + body else header + body + digest(header, body)
            if (result.size > MAX_FILE_BYTES) throw BackupTooLargeException()
            return result
        } finally {
            payload.fill(0)
        }
    }

    fun isEncrypted(bytes: ByteArray): Boolean = envelope(bytes).encrypted

    fun decode(bytes: ByteArray, password: CharArray? = null): BackupData {
        val file = envelope(bytes)
        val payload = if (file.encrypted) {
            if (password == null || password.isEmpty()) throw BackupPasswordRequiredException()
            try {
                crypt(Cipher.DECRYPT_MODE, file.body, password, file.salt, file.iv, file.header)
            } catch (_: AEADBadTagException) {
                throw IncorrectBackupPasswordException()
            }
        } else {
            valid(MessageDigest.isEqual(file.checksum, digest(file.header, file.body)))
            file.body
        }
        try {
            val input = DataInputStream(ByteArrayInputStream(payload))
            val count = input.readInt()
            valid(count in 0..MAX_NOTES)
            // A note needs at least 12 bytes of string lengths and 16 of timestamps.
            valid(count <= input.available() / 28)
            val notes = List(count) {
                ContactNote(
                    phoneNumber = readText(input, if (file.version == 1) 32 else 4096),
                    name = readText(input, 16 * 1024),
                    noteContent = readText(input, MAX_CONTENT_BYTES),
                    createdAt = input.readLong(),
                    updatedAt = input.readLong()
                )
            }
            valid(input.available() == 0)
            validate(notes)
            return BackupData(file.exportedAt, notes)
        } catch (_: EOFException) {
            throw InvalidBackupException()
        } finally {
            payload.fill(0)
        }
    }

    private data class Envelope(
        val version: Int, val encrypted: Boolean, val exportedAt: Long, val header: ByteArray,
        val body: ByteArray, val salt: ByteArray, val iv: ByteArray, val checksum: ByteArray
    )

    private fun envelope(bytes: ByteArray): Envelope {
        if (bytes.size > MAX_FILE_BYTES) throw BackupTooLargeException()
        try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            valid(ByteArray(magic.size).also { input.readFully(it) }.contentEquals(magic))
            val version = input.readInt()
            if (version !in 1..VERSION) throw UnsupportedBackupVersionException()
            val mode = input.readUnsignedByte()
            valid(mode == 0 || mode == 1)
            val encrypted = mode == 1
            val exportedAt = input.readLong()
            valid(exportedAt in 0..MAX_TIMESTAMP)
            val salt: ByteArray
            val iv: ByteArray
            if (encrypted) {
                // Versions 1 and 2 have fixed KDF parameters; untrusted files cannot increase the work factor.
                valid(input.readInt() == ITERATIONS)
                salt = ByteArray(16).also { input.readFully(it) }
                iv = ByteArray(12).also { input.readFully(it) }
            } else {
                salt = byteArrayOf()
                iv = byteArrayOf()
            }
            val length = input.readInt()
            val minimum = if (encrypted) 20 else 4
            val trailer = if (encrypted) 0 else 32
            valid(length >= minimum && length <= MAX_FILE_BYTES && input.available() - trailer == length)
            val header = bytes.copyOfRange(0, bytes.size - input.available())
            val body = ByteArray(length).also { input.readFully(it) }
            val checksum = ByteArray(trailer).also { input.readFully(it) }
            return Envelope(version, encrypted, exportedAt, header, body, salt, iv, checksum)
        } catch (_: EOFException) {
            throw InvalidBackupException()
        }
    }

    internal fun validate(notes: List<ContactNote>) {
        if (notes.size > MAX_NOTES) throw BackupTooLargeException()
        val phones = HashSet<String>()
        notes.forEach { note ->
            // Room permits legacy/raw phone keys and arbitrary Long timestamps.
            // A backup must retain those values, even when today's editor rejects them.
            valid(phones.add(note.phoneNumber))
        }
    }

    private fun writeText(out: DataOutputStream, buffer: ByteArrayOutputStream, text: String, max: Int) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > max || buffer.size().toLong() + bytes.size + 128 > MAX_FILE_BYTES) {
            throw BackupTooLargeException()
        }
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readText(input: DataInputStream, max: Int): String {
        val size = input.readInt()
        valid(size in 0..max && size <= input.available())
        val bytes = ByteArray(size).also { input.readFully(it) }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw InvalidBackupException()
        }
    }

    private fun crypt(mode: Int, data: ByteArray, password: CharArray, salt: ByteArray, iv: ByteArray, header: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            cipher.doFinal(data)
        } finally {
            key.fill(0)
        }
    }

    private fun randomBytes(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }
    private fun digest(header: ByteArray, body: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        update(header)
        digest(body)
    }
    private fun valid(condition: Boolean) { if (!condition) throw InvalidBackupException() }
}
