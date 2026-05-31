package com.callrecall.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.callrecall.models.ContactNote
import com.callrecall.repository.ContactNoteRepository
import com.callrecall.utils.ContactUtils
import com.callrecall.utils.PhoneNumberUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NoteEditorViewModel(
    application: Application,
    private val repository: ContactNoteRepository
) : AndroidViewModel(application) {

    private val _noteId = MutableStateFlow(0L)
    private val _phoneNumber = MutableStateFlow("")
    private val _contactName = MutableStateFlow("")
    private val _customDisplayName = MutableStateFlow("")
    private val _noteContent = MutableStateFlow("")
    private val _updatedAt = MutableStateFlow(System.currentTimeMillis())
    private val _isLoading = MutableStateFlow(true)

    val phoneNumber: StateFlow<String> = _phoneNumber.asStateFlow()
    val contactName: StateFlow<String> = _contactName.asStateFlow()
    val customDisplayName: StateFlow<String> = _customDisplayName.asStateFlow()
    val noteContent: StateFlow<String> = _noteContent.asStateFlow()
    val updatedAt: StateFlow<Long> = _updatedAt.asStateFlow()
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

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
                    ContactUtils.lookupNameByPhone(getApplication(), normalized)
                } else {
                    ""
                }
                _contactName.value = resolvedName
                _customDisplayName.value = ""
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
                _contactName.value = name
                _customDisplayName.value = ""
                _noteContent.value = ""
                _updatedAt.value = System.currentTimeMillis()
            }
            _isLoading.value = false
        }
    }

    private fun applyNote(note: ContactNote) {
        _noteId.value = note.id
        _phoneNumber.value = note.phoneNumber
        _contactName.value = note.contactName
        _customDisplayName.value = note.customDisplayName
        _noteContent.value = note.noteContent
        _updatedAt.value = note.updatedAt
    }

    fun updateContactName(value: String) {
        _contactName.value = value
        scheduleAutoSave()
    }

    fun updateCustomDisplayName(value: String) {
        _customDisplayName.value = value
        scheduleAutoSave()
    }

    fun updatePhoneNumber(value: String) {
        _phoneNumber.value = PhoneNumberUtils.normalize(value)
        scheduleAutoSave()
    }

    fun updateNoteContent(value: String) {
        _noteContent.value = value
        scheduleAutoSave()
    }

    private var savePending = false

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
    suspend fun persist() {
        val phone = _phoneNumber.value
        // Allow saving notes without a phone number (e.g. opened from overlay when number hidden)
        if (phone.isBlank() && _noteContent.value.isBlank() &&
            _contactName.value.isBlank() && _customDisplayName.value.isBlank()
        ) {
            return
        }
        if (phone.isNotBlank() && !PhoneNumberUtils.isValid(phone)) return
        val savedId = repository.saveNote(
            id = _noteId.value,
            phoneNumber = phone,
            contactName = _contactName.value.trim(),
            customDisplayName = _customDisplayName.value.trim(),
            noteContent = _noteContent.value,
        )
        _noteId.value = savedId
        _updatedAt.value = System.currentTimeMillis()
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
