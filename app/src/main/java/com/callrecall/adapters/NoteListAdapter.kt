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
            binding.textPrimaryName.text = note.contactName.ifBlank { note.displayLabel() }
            binding.textCustomName.text = note.customDisplayName
            binding.textCustomName.visibility = if (note.customDisplayName.isNotBlank()) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
            binding.textContactName.visibility = android.view.View.GONE
            binding.textPhoneNumber.text = note.phoneNumber
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
