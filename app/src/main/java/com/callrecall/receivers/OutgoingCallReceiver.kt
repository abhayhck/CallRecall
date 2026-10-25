package com.callrecall.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.callrecall.utils.CallNumberStore
import com.callrecall.utils.CallOverlayCoordinator
import com.callrecall.utils.PhoneNumberUtils

/**
 * Captures the dialed number when the user places an outgoing call.
 */
class OutgoingCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_NEW_OUTGOING_CALL) return
        val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
        val normalized = number?.let { PhoneNumberUtils.normalize(it) }.orEmpty()
        Log.d("CallOverlayCoord", "Outgoing-call broadcast received")
        CallNumberStore.setOutgoing(context, normalized)
        CallOverlayCoordinator.handleCallState(
            context,
            TelephonyManager.CALL_STATE_OFFHOOK,
            normalized.ifBlank { null }
        )
    }
}
