package com.callrecall.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.callrecall.databinding.ItemNoteBinding
import com.callrecall.models.ContactNote
import com.callrecall.utils.DateTimeUtils

class NoteListAdapter(
    private val onNoteClick: (ContactNote) -> Unit,
    private val onNoteLongClick: (ContactNote) -> Unit
) : ListAdapter<ContactNote, NoteListAdapter.NoteViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val binding = ItemNoteBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return NoteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class NoteViewHolder(
        private val binding: ItemNoteBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(note: ContactNote) {
            binding.textPrimaryName.text = note.displayLabel().ifBlank {
                binding.root.context.getString(com.callrecall.R.string.unnamed_note)
            }
            binding.textNotePreview.text = note.noteContent.trim()
            binding.textNotePreview.visibility = if (note.noteContent.isBlank()) {
                android.view.View.GONE
            } else {
                android.view.View.VISIBLE
            }
            binding.textPhoneNumber.text = note.phoneNumber
            binding.textPhoneNumber.visibility = if (note.name.isNotBlank() && note.phoneNumber.isNotBlank()) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
            binding.textLastUpdated.text = DateTimeUtils.formatDate(note.updatedAt)

            binding.root.setOnClickListener { onNoteClick(note) }
            binding.root.setOnLongClickListener {
                onNoteLongClick(note)
                true
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ContactNote>() {
        override fun areItemsTheSame(oldItem: ContactNote, newItem: ContactNote): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ContactNote, newItem: ContactNote): Boolean {
            return oldItem == newItem
        }
    }
}
