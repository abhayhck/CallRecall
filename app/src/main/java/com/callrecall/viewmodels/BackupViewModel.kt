package com.callrecall.viewmodels

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.callrecall.CallRecallApp
import com.callrecall.R
import com.callrecall.backup.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface BackupEvent {
    data class ChooseExportLocation(val filename: String) : BackupEvent
    data class Notice(val message: Int, val counts: List<Int> = emptyList()) : BackupEvent
}

data class BackupUiState(
    val busy: Boolean = false,
    val waitingForExportLocation: Boolean = false,
    val passwordNeeded: Boolean = false,
    val lastExportAt: Long = 0,
    val preview: RestorePreview? = null,
    val replacePhones: Set<String> = emptySet()
)

class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val store = BackupStore((application as CallRecallApp).database, application.contentResolver)
    private val preferences = application.getSharedPreferences("backup_status", Application.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(BackupUiState(lastExportAt = preferences.getLong("last_export", 0)))
    val state = mutableState.asStateFlow()
    private val eventsChannel = Channel<BackupEvent>(Channel.BUFFERED)
    val events = eventsChannel.receiveAsFlow()
    private var pendingExport: ByteArray? = null
    private var exportCount = 0
    private var pendingInput: ByteArray? = null

    fun prepareExport(password: CharArray?) {
        if (state.value.busy || state.value.waitingForExportLocation) {
            password?.fill('\u0000')
            return
        }
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { store.export(password) }
                pendingExport?.fill(0)
                pendingExport = result.first
                exportCount = result.second
                mutableState.update { it.copy(waitingForExportLocation = true) }
                val stamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.ROOT).format(Date())
                eventsChannel.send(BackupEvent.ChooseExportLocation("CallRecall-$stamp.callrecall"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidBackupException) {
                eventsChannel.send(BackupEvent.Notice(R.string.backup_export_invalid))
            } catch (e: Exception) {
                report(e)
            } finally {
                password?.fill('\u0000')
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun exportTo(uri: Uri?) {
        val bytes = pendingExport
        pendingExport = null
        mutableState.update { it.copy(waitingForExportLocation = false) }
        if (uri == null) { bytes?.fill(0); return }
        if (bytes == null) {
            viewModelScope.launch { eventsChannel.send(BackupEvent.Notice(R.string.backup_selection_expired)) }
            return
        }
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.write(uri, bytes) }
                val now = System.currentTimeMillis()
                preferences.edit().putLong("last_export", now).apply()
                mutableState.update { it.copy(lastExportAt = now) }
                eventsChannel.send(BackupEvent.Notice(R.string.backup_export_success, listOf(exportCount)))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                eventsChannel.send(BackupEvent.Notice(R.string.backup_write_failed))
            } finally {
                bytes.fill(0)
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun readBackup(uri: Uri?) {
        if (uri == null || state.value.busy) return
        clearInput()
        mutableState.update { it.copy(busy = true, preview = null, replacePhones = emptySet()) }
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { store.read(uri) }
                pendingInput = bytes
                val encrypted = withContext(Dispatchers.Default) { BackupCodec.isEncrypted(bytes) }
                if (encrypted) mutableState.update { it.copy(passwordNeeded = true) }
                else decodeInput(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                clearInput()
                report(e)
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun unlock(password: CharArray) {
        if (state.value.busy) { password.fill('\u0000'); return }
        mutableState.update { it.copy(passwordNeeded = false, busy = true) }
        viewModelScope.launch {
            try {
                decodeInput(password)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IncorrectBackupPasswordException) {
                mutableState.update { it.copy(passwordNeeded = true) }
                report(e)
            } catch (e: Exception) {
                clearInput()
                report(e)
            } finally {
                password.fill('\u0000')
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun decodeInput(password: CharArray?) {
        val bytes = pendingInput ?: throw InvalidBackupException()
        val preview = withContext(Dispatchers.IO) { store.preview(BackupCodec.decode(bytes, password)) }
        mutableState.update { it.copy(preview = preview, replacePhones = emptySet(), passwordNeeded = false) }
        clearInput()
    }

    fun cancelUnlock() {
        clearInput()
        mutableState.update { it.copy(passwordNeeded = false) }
    }

    fun chooseReplacement(phone: String, replace: Boolean) {
        mutableState.update {
            it.copy(replacePhones = if (replace) it.replacePhones + phone else it.replacePhones - phone)
        }
    }

    fun restore() {
        val preview = state.value.preview ?: return
        if (state.value.busy) return
        val choices = state.value.replacePhones.toSet()
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { store.restore(preview, choices) }
                mutableState.update { it.copy(preview = null, replacePhones = emptySet()) }
                eventsChannel.send(BackupEvent.Notice(R.string.backup_restore_success,
                    listOf(result.added, result.replaced, result.kept, result.identical)))
            } catch (e: CancellationException) {
                throw e
            } catch (_: RestoreChangedException) {
                try {
                    val fresh = withContext(Dispatchers.IO) { store.preview(preview.backup) }
                    mutableState.update { it.copy(preview = fresh, replacePhones = emptySet()) }
                    eventsChannel.send(BackupEvent.Notice(R.string.backup_notes_changed))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    report(e)
                }
            } catch (e: Exception) {
                report(e)
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun report(error: Exception) {
        val message = when (error) {
            is IncorrectBackupPasswordException -> R.string.backup_wrong_password
            is BackupTooLargeException -> R.string.backup_too_large
            is UnsupportedBackupVersionException -> R.string.backup_newer_version
            is InvalidBackupException -> R.string.backup_invalid
            is AmbiguousExistingNotesException -> R.string.backup_existing_duplicates
            else -> R.string.backup_operation_failed
        }
        eventsChannel.send(BackupEvent.Notice(message))
    }

    private fun clearInput() { pendingInput?.fill(0); pendingInput = null }
    override fun onCleared() {
        pendingExport?.fill(0)
        clearInput()
        super.onCleared()
    }
}
