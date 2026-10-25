package com.callrecall.utils

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionHelper {

    val REQUIRED_RUNTIME_PERMISSIONS = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.PROCESS_OUTGOING_CALLS,
        Manifest.permission.POST_NOTIFICATIONS
    )

    fun missingRuntimePermissions(context: Context): List<String> =
        REQUIRED_RUNTIME_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    fun hasOutgoingCallPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.PROCESS_OUTGOING_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasReadPhoneState(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasReadContacts(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasReadCallLog(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun hasAllCorePermissions(context: Context): Boolean {
        return missingRuntimePermissions(context).isEmpty() && hasOverlayPermission(context)
    }

    fun missingPermissions(context: Context): List<String> {
        val missing = mutableListOf<String>()
        if (!hasReadPhoneState(context)) missing.add("Phone")
        if (!hasReadContacts(context)) missing.add("Contacts")
        if (!hasReadCallLog(context)) missing.add("Call log")
        if (!hasOutgoingCallPermission(context)) missing.add("Outgoing calls")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) missing.add("Notifications")
        if (!hasOverlayPermission(context)) missing.add("Display over other apps")
        return missing
    }

    fun overlaySettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    }

    fun appSettingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    )

    fun isBackgroundRestricted(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

}
