package com.callrecall.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.callrecall.models.ContactNote
import com.callrecall.models.SortOption
import com.callrecall.repository.ContactNoteRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(
    private val repository: ContactNoteRepository
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val sortOption = MutableStateFlow(SortOption.RECENTLY_UPDATED)

    val currentSortOption: StateFlow<SortOption> = sortOption

    val notes: StateFlow<List<ContactNote>> = combine(
        repository.observeAllNotes(),
        searchQuery,
        sortOption
    ) { allNotes, query, sort ->
        repository.filterAndSort(allNotes, query, sort)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setSortOption(option: SortOption) {
        sortOption.value = option
    }

    fun deleteNote(note: ContactNote, onComplete: () -> Unit) {
        viewModelScope.launch {
            repository.delete(note)
            onComplete()
        }
    }

    class Factory(
        private val repository: ContactNoteRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(repository) as T
        }
    }
}
