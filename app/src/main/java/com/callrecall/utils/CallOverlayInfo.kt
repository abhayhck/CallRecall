package com.callrecall.utils

import kotlinx.coroutines.CancellationException

data class CallOverlayInfo(val phoneNumber: String, val hasNote: Boolean)

/** Permission or storage failures must not suppress the floating button itself. */
suspend fun resolveCallOverlayInfo(
    resolvePhone: suspend () -> String,
    hasNote: suspend (String) -> Boolean,
    reportFailure: (Exception) -> Unit
): CallOverlayInfo {
    val phone = try {
        resolvePhone()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        reportFailure(e)
        ""
    }
    val existing = if (phone.isEmpty()) false else try {
        hasNote(phone)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        reportFailure(e)
        false
    }
    return CallOverlayInfo(phone, existing)
}
