package com.callrecall.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.callrecall.utils.CallNumberStore
import com.callrecall.utils.CallOverlayCoordinator
import com.callrecall.utils.PhoneNumberUtils

/**
 * Receives PHONE_STATE broadcasts and forwards call state to the monitoring service.
 */
class PhoneStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val stateExtra = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val state = when (stateExtra) {
            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
            TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
            else -> return
        }

        @Suppress("DEPRECATION")
        val rawNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val normalized = rawNumber?.let { PhoneNumberUtils.normalize(it) }.orEmpty()
        if (normalized.isNotEmpty()) {
            CallNumberStore.setIncoming(context, normalized)
        }

        CallOverlayCoordinator.handleCallState(
            context,
            state,
            normalized.ifBlank { null }
        )
    }
}
