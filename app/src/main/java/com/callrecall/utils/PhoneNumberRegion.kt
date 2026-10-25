package com.callrecall.utils

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/** SIM country is more useful than the UI language, particularly when roaming. */
fun configurePhoneNumberRegion(context: Context) {
    val telephony = context.getSystemService(TelephonyManager::class.java)
    fun readCountry(read: () -> String?): String? = try {
        read()?.takeIf { it.length == 2 }
    } catch (_: SecurityException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }
    val country = readCountry { telephony?.simCountryIso }
        ?: readCountry { telephony?.networkCountryIso }
        ?: context.resources.configuration.locales[0]?.country?.takeIf { it.length == 2 }
        ?: Locale.getDefault().country
    PhoneNumberUtils.setDefaultRegion(country)
}
