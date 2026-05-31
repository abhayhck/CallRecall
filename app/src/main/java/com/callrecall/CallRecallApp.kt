package com.callrecall

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.callrecall.database.AppDatabase
import com.callrecall.receivers.OutgoingCallReceiver
import com.callrecall.repository.ContactNoteRepository
import com.callrecall.utils.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CallRecallApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    val repository: ContactNoteRepository by lazy {
        ContactNoteRepository(database.contactNoteDao())
    }

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var outgoingReceiverRegistered = false

    companion object {
        lateinit var instance: CallRecallApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerOutgoingCallReceiverIfNeeded()
    }

    fun registerOutgoingCallReceiverIfNeeded() {
        if (outgoingReceiverRegistered) return
        if (!PermissionHelper.hasReadPhoneState(this)) return
        try {
            val filter = IntentFilter(Intent.ACTION_NEW_OUTGOING_CALL).apply {
                priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            }
            ContextCompat.registerReceiver(
                this,
                OutgoingCallReceiver(),
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            outgoingReceiverRegistered = true
        } catch (e: Exception) {
            android.util.Log.w("CallRecallApp", "Outgoing call receiver not registered", e)
        }
    }
}
