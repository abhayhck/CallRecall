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
    val contactName: String = "",
    val customDisplayName: String = "",
    val noteContent: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /** Primary label shown in lists and editor header. */
    fun displayLabel(): String {
        return when {
            customDisplayName.isNotBlank() -> customDisplayName
            contactName.isNotBlank() -> contactName
            else -> phoneNumber
        }
    }

    fun secondaryLabel(): String {
        return when {
            customDisplayName.isNotBlank() && contactName.isNotBlank() -> contactName
            customDisplayName.isNotBlank() || contactName.isNotBlank() -> phoneNumber
            else -> ""
        }
    }
}
