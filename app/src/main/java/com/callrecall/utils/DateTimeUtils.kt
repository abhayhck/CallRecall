package com.callrecall.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateTimeUtils {

    private val dateTimeFormat = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    fun formatDateTime(timestamp: Long): String {
        return dateTimeFormat.format(Date(timestamp))
    }

    fun formatDate(timestamp: Long): String {
        return dateFormat.format(Date(timestamp))
    }
}
