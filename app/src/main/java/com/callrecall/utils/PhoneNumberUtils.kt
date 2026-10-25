package com.callrecall.utils

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

object PhoneNumberUtils {
    private val parser by lazy { PhoneNumberUtil.getInstance() }

    @Volatile
    private var defaultRegion = "ZZ"

    /** Used only for numbers entered without an explicit international prefix. */
    fun setDefaultRegion(region: String) {
        val iso = region.uppercase(Locale.ROOT)
        defaultRegion = iso.takeIf { it in parser.supportedRegions } ?: "ZZ"
    }

    fun defaultCountryLabel(): String? {
        val code = parser.getCountryCodeForRegion(defaultRegion)
        if (code == 0) return null
        val country = Locale.Builder().setRegion(defaultRegion).build().displayCountry
        return "$country (+$code)"
    }

    /** Canonical E.164 key for storage AND call lookup; empty means invalid/unknown. */
    fun normalize(phoneNumber: String, region: String = defaultRegion): String {
        val raw = phoneNumber.trim()
        if (raw.isEmpty() || raw.length > 100) return ""
        // Do not turn service codes, extensions or arbitrary text into a caller's identity.
        if (raw.any { !it.isDigit() && !it.isWhitespace() && it !in "+()-./" }) return ""
        val input = if (raw.startsWith("00")) "+${raw.drop(2)}" else raw
        return try {
            val number = parser.parse(input, region.uppercase(Locale.ROOT))
            if (!parser.isValidNumber(number) || number.hasExtension()) ""
            else parser.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)
        } catch (_: NumberParseException) {
            ""
        }
    }

    fun digitsOnly(phoneNumber: String): String = phoneNumber.filter { it.isDigit() }

    fun isValid(phoneNumber: String): Boolean = normalize(phoneNumber).isNotEmpty()
}
