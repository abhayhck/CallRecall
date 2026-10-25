package com.callrecall.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contact_notes",
    indices = [Index(value = ["phoneNumber"], unique = true)]
)
data class ContactNote(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val phoneNumber: String,
    val name: String = "",
    val noteContent: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /** Primary label shown in lists. */
    fun displayLabel(): String = name.ifBlank { phoneNumber }
}
