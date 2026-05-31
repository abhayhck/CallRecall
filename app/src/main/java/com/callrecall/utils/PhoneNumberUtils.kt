package com.callrecall.utils

object PhoneNumberUtils {

    /** Normalize to digits-only for storage and lookup consistency. */
    fun normalize(phoneNumber: String): String {
        return digitsOnly(phoneNumber)
    }

    fun digitsOnly(phoneNumber: String): String {
        return phoneNumber.filter { it.isDigit() }
    }

    fun isValid(phoneNumber: String): Boolean {
        val digits = digitsOnly(phoneNumber)
        return digits.length >= 7
    }
}
