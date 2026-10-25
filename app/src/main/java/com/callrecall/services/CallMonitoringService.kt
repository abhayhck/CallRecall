package com.callrecall.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.callrecall.CallRecallApp
import com.callrecall.R
import com.callrecall.utils.CallNumberStore
import com.callrecall.utils.CallOverlayCoordinator
import com.callrecall.utils.PermissionHelper

/**
 * Optional foreground service during calls for [TelephonyCallback] backup.
 * The floating overlay is shown by [CallOverlayCoordinator] and does not depend on this service.
 */
class CallMonitoringService : Service() {

    private var telephonyManager: TelephonyManager? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var listenersRegistered = false

    override fun onCreate() {
        super.onCreate()
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (intent?.action != ACTION_CALL_STATE) {
                stopSelf()
                return START_NOT_STICKY
            }
            val state = intent.getIntExtra(EXTRA_STATE, TelephonyManager.CALL_STATE_IDLE)
            if (state != TelephonyManager.CALL_STATE_IDLE) {
                ensureForeground()
                registerListenersIfNeeded()
            } else {
                stopAfterCall()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand failed", e)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        unregisterListeners()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureForeground() {
        if (isRunning) return
        createNotificationChannel()
        val notification = buildNotification()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        isRunning = true
    }

    private fun stopAfterCall() {
        unregisterListeners()
        stopForeground(STOP_FOREGROUND_REMOVE)
        isRunning = false
        stopSelf()
    }

    private fun registerListenersIfNeeded() {
        if (listenersRegistered) return
        if (!PermissionHelper.hasReadPhoneState(this)) return

        registerCallListener()
        listenersRegistered = true
        Log.i(TAG, "Telephony callback registered")
    }

    private fun registerCallListener() {
        val tm = telephonyManager ?: return
        try {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    CallOverlayCoordinator.handleCallState(
                        this@CallMonitoringService,
                        state,
                        CallNumberStore.getBestNumber(this@CallMonitoringService),
                        startMonitoringService = false
                    )
                    if (state == TelephonyManager.CALL_STATE_IDLE) {
                        stopAfterCall()
                    } else {
                        ensureForeground()
                        registerListenersIfNeeded()
                    }
                }
            }
            telephonyCallback = callback
            tm.registerTelephonyCallback(mainExecutor, callback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot register telephony callback", e)
        }
    }

    private fun unregisterListeners() {
        val tm = telephonyManager ?: return
        telephonyCallback?.let {
            try {
                tm.unregisterTelephonyCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterTelephonyCallback failed", e)
            }
        }
        telephonyCallback = null
        listenersRegistered = false
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setSilent(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "CallMonitoringService"
        const val CHANNEL_ID = "call_monitoring"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_CALL_STATE = "com.callrecall.action.CALL_STATE"
        const val EXTRA_STATE = "extra_call_state"
        const val EXTRA_NUMBER = "extra_call_number"

        @Volatile
        var isRunning = false
            private set

        fun notifyCallState(context: Context, state: Int, number: String?) {
            if (!PermissionHelper.hasReadPhoneState(context)) return
            try {
                val intent = Intent(context, CallMonitoringService::class.java).apply {
                    action = ACTION_CALL_STATE
                    putExtra(EXTRA_STATE, state)
                    if (!number.isNullOrBlank()) {
                        putExtra(EXTRA_NUMBER, number)
                    }
                }
                if (state == TelephonyManager.CALL_STATE_IDLE) {
                    context.startService(intent)
                } else {
                    context.startForegroundService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Monitoring service not started (overlay still works): ${e.message}")
            }
        }
    }
}
