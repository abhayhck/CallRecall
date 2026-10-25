package com.callrecall.utils

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import com.callrecall.CallRecallApp
import com.callrecall.repository.ContactNoteRepository
import com.callrecall.services.CallMonitoringService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows/hides the floating overlay on call state changes.
 *
 * Runs on the application scope so it still works when [CallMonitoringService] cannot
 * start as a foreground service from the background (common on Android 14+).
 */
object CallOverlayCoordinator {

    private const val TAG = "CallOverlayCoord"

    private val overlayManager by lazy { FloatingOverlayManager(CallRecallApp.instance) }
    private val repository: ContactNoteRepository
        get() = CallRecallApp.instance.repository

    @Volatile
    private var lastState = TelephonyManager.CALL_STATE_IDLE

    @Volatile
    private var showJob: Job? = null

    fun handleCallState(
        context: Context,
        state: Int,
        numberHint: String?,
        startMonitoringService: Boolean = true
    ) {
        val appContext = context.applicationContext

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                lastState = state
                scheduleShow(appContext, numberHint, isIncoming = true)
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                val wasIdle = lastState == TelephonyManager.CALL_STATE_IDLE
                lastState = state
                scheduleShow(appContext, numberHint, isIncoming = !wasIdle)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                lastState = state
                showJob?.cancel()
                showJob = null
                overlayManager.hide()
                CallNumberStore.clear(appContext)
            }
        }

        if (startMonitoringService) {
            // Best-effort: keeps TelephonyCallback when allowed; overlay does not depend on this.
            CallMonitoringService.notifyCallState(appContext, state, numberHint)
        }
    }

    private fun scheduleShow(context: Context, numberHint: String?, isIncoming: Boolean) {
        if (!PermissionHelper.hasOverlayPermission(context)) {
            Log.w(TAG, "Overlay permission missing")
            return
        }

        showJob?.cancel()
        showJob = CallRecallApp.instance.applicationScope.launch {
            delay(if (isIncoming) 350 else 150)

            try {
                val info = resolveCallOverlayInfo(
                    resolvePhone = { resolvePhoneNumber(context, numberHint) },
                    hasNote = { phone -> withContext(Dispatchers.IO) { repository.existsForPhone(phone) } },
                    reportFailure = { Log.w(TAG, "Caller lookup unavailable; showing a new-note bubble", it) }
                )
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.Main) {
                    if (lastState != TelephonyManager.CALL_STATE_IDLE) {
                        Log.d(TAG, "Showing overlay; caller resolved=${info.phoneNumber.isNotEmpty()}")
                        overlayManager.show(info.phoneNumber, info.hasNote)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show overlay", e)
            }
        }
    }

    private suspend fun resolvePhoneNumber(context: Context, hint: String?): String {
        if (!hint.isNullOrBlank()) {
            val normalized = PhoneNumberUtils.normalize(hint)
            if (normalized.isNotEmpty()) return normalized
        }

        PhoneNumberUtils.normalize(CallNumberStore.getBestNumber(context))
            .takeIf { it.isNotEmpty() }?.let { return it }

        if (PermissionHelper.hasReadCallLog(context)) {
            delay(200)
            CallLogHelper.getLatestCallNumber(context)?.takeIf { it.isNotEmpty() }?.let { return it }
        }

        return ""
    }
}
