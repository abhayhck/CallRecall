package com.callrecall.utils

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

data class ContactInfo(
    val name: String,
    val phoneNumber: String
)

object ContactUtils {

    fun resolveContact(context: Context, contactUri: Uri): ContactInfo? {
        val resolver = context.contentResolver
        var name = ""
        resolver.query(
            contactUri,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                if (index >= 0) {
                    name = cursor.getString(index).orEmpty()
                }
            }
        }

        val lookupUri = ContactsContract.Contacts.lookupContact(resolver, contactUri)
            ?: return null
        val contactId = lookupUri.lastPathSegment ?: return null

        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId),
            null
        )?.use { phoneCursor ->
            if (phoneCursor.moveToFirst()) {
                val index = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (index >= 0) {
                    val number = phoneCursor.getString(index).orEmpty()
                    if (PhoneNumberUtils.isValid(number)) {
                        return ContactInfo(name, PhoneNumberUtils.normalize(number))
                    }
                }
            }
        }
        return null
    }

    fun lookupNameByPhone(context: Context, phoneNumber: String): String {
        val normalized = PhoneNumberUtils.normalize(phoneNumber)
        if (normalized.isEmpty()) return ""

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(normalized)
        )
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (index >= 0) {
                    return cursor.getString(index).orEmpty()
                }
            }
        }
        return ""
    }
}
