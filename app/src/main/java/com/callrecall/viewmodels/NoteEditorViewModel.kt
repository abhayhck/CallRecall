package com.callrecall.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.callrecall.R
import com.callrecall.models.ContactNote
import com.callrecall.repository.DuplicatePhoneNumberException
import com.callrecall.repository.ContactNoteRepository
import com.callrecall.utils.ContactUtils
import com.callrecall.utils.PermissionHelper
import com.callrecall.utils.PhoneNumberUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NoteEditorViewModel(
    application: Application,
    private val repository: ContactNoteRepository
) : AndroidViewModel(application) {

    private val _noteId = MutableStateFlow(0L)
    private val _phoneNumber = MutableStateFlow("")
    private val _name = MutableStateFlow("")
    private val _noteContent = MutableStateFlow("")
    private val _updatedAt = MutableStateFlow(System.currentTimeMillis())
    private val _isLoading = MutableStateFlow(true)
    private val _phoneNumberError = MutableStateFlow<Int?>(null)

    val phoneNumber: StateFlow<String> = _phoneNumber.asStateFlow()
    val name: StateFlow<String> = _name.asStateFlow()
    val noteContent: StateFlow<String> = _noteContent.asStateFlow()
    val updatedAt: StateFlow<Long> = _updatedAt.asStateFlow()
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    val phoneNumberError: StateFlow<Int?> = _phoneNumberError.asStateFlow()

    fun load(noteId: Long, phoneNumber: String?) {
        viewModelScope.launch {
            _isLoading.value = true
            val note = when {
                noteId > 0 -> repository.getNoteById(noteId)
                !phoneNumber.isNullOrBlank() -> repository.getNoteByPhone(phoneNumber)
                else -> null
            }
            if (note != null) {
                applyNote(note)
            } else {
                val normalized = PhoneNumberUtils.normalize(phoneNumber.orEmpty())
                _noteId.value = 0
                _phoneNumber.value = normalized
                val resolvedName = if (normalized.isNotEmpty()) {
                    resolveName(normalized)
                } else {
                    ""
                }
                _name.value = resolvedName
                _noteContent.value = ""
                _updatedAt.value = System.currentTimeMillis()
            }
            _isLoading.value = false
        }
    }

    fun loadFromContact(name: String, phone: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val normalized = PhoneNumberUtils.normalize(phone)
            val existing = repository.getNoteByPhone(normalized)
            if (existing != null) {
                applyNote(existing)
            } else {
                _noteId.value = 0
                _phoneNumber.value = normalized
                _name.value = name
                _noteContent.value = ""
                _updatedAt.value = System.currentTimeMillis()
            }
            _isLoading.value = false
        }
    }

    private fun applyNote(note: ContactNote) {
        _noteId.value = note.id
        _phoneNumber.value = note.phoneNumber
        _name.value = resolveName(note.phoneNumber).ifBlank { note.name }
        _noteContent.value = note.noteContent
        _updatedAt.value = note.updatedAt
    }

    private fun resolveName(phone: String): String {
        if (phone.isBlank() || !PermissionHelper.hasReadContacts(getApplication())) return ""
        return try {
            ContactUtils.lookupNameByPhone(getApplication(), phone)
        } catch (_: SecurityException) {
            ""
        }
    }

    fun updateName(value: String) {
        _name.value = value
        scheduleAutoSave()
    }

    fun updatePhoneNumber(value: String) {
        // Keep invalid input visible so the user can correct it.
        _phoneNumber.value = PhoneNumberUtils.normalize(value).ifBlank { value.trim() }
        _phoneNumberError.value = if (PhoneNumberUtils.isValid(value)) null else R.string.invalid_phone_number
        scheduleAutoSave()
    }

    fun updateNoteContent(value: String) {
        _noteContent.value = value
        scheduleAutoSave()
    }

    private var savePending = false
    private val persistMutex = Mutex()

    private fun scheduleAutoSave() {
        if (savePending) return
        savePending = true
        viewModelScope.launch {
            kotlinx.coroutines.delay(400)
            savePending = false
            persist()
        }
    }

    /** Immediate save — used on pause/close. */
    fun persistNow() {
        viewModelScope.launch {
            persist()
        }
    }

    /** Persists current editor state to Room (auto-save). */
    suspend fun persist(): Boolean = persistMutex.withLock {
        if (_isLoading.value) return false
        val phone = PhoneNumberUtils.normalize(_phoneNumber.value)
        if (phone.isEmpty()) {
            _phoneNumberError.value = R.string.invalid_phone_number
            return false
        }
        return try {
            val savedId = repository.saveNote(
                id = _noteId.value,
                phoneNumber = phone,
                name = _name.value.trim(),
                noteContent = _noteContent.value,
            )
            _noteId.value = savedId
            // A save may finish after the user has entered a different phone number.
            if (PhoneNumberUtils.normalize(_phoneNumber.value) == phone) {
                _phoneNumber.value = phone
                _phoneNumberError.value = null
            }
            _updatedAt.value = System.currentTimeMillis()
            true
        } catch (_: DuplicatePhoneNumberException) {
            _phoneNumberError.value = R.string.phone_number_already_used
            false
        }
    }

    class Factory(
        private val application: Application,
        private val repository: ContactNoteRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return NoteEditorViewModel(application, repository) as T
        }
    }
}
