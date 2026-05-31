package com.callrecall.utils

import android.content.Context
import android.provider.CallLog
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager

/**
 * Fallback to read the most recent call number when broadcasts hide the caller ID.
 */
object CallLogHelper {

    fun getLatestCallNumber(context: Context): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val since = System.currentTimeMillis() - 60_000
        val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE)
        val selection = "${CallLog.Calls.DATE} > ?"
        val args = arrayOf(since.toString())
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            selection,
            args,
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                if (index >= 0) {
                    val number = cursor.getString(index)
                    if (!number.isNullOrBlank()) {
                        return PhoneNumberUtils.normalize(number)
                    }
                }
            }
        }
        return null
    }
}
