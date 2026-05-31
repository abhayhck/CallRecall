package com.callrecall.utils

/**
 * Holds the best-known phone number for the current call session.
 * Populated from phone-state broadcasts and outgoing-call intents.
 */
object CallNumberStore {

    private const val PREFS = "call_recall_active_call"
    private const val KEY_NUMBER = "active_number"
    private const val KEY_OUTGOING = "outgoing_number"

    const val UNKNOWN_CALLER = ""

    fun setIncoming(context: android.content.Context, number: String?) {
        val normalized = number?.let { PhoneNumberUtils.normalize(it) }.orEmpty()
        if (normalized.isNotEmpty()) {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_NUMBER, normalized)
                .apply()
        }
    }

    fun setOutgoing(context: android.content.Context, number: String?) {
        val normalized = number?.let { PhoneNumberUtils.normalize(it) }.orEmpty()
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_OUTGOING, normalized)
            .apply()
        if (normalized.isNotEmpty()) {
            setIncoming(context, normalized)
        }
    }

    fun getBestNumber(context: android.content.Context): String {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        return prefs.getString(KEY_NUMBER, null)
            ?: prefs.getString(KEY_OUTGOING, null)
            ?: ""
    }

    fun clear(context: android.content.Context) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
