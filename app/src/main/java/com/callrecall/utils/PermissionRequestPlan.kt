package com.callrecall.utils

/** Select one permission step at a time, without launching competing Android dialogs. */
sealed interface PermissionRequestPlan {
    data class Runtime(val permissions: List<String>) : PermissionRequestPlan
    data object AppSettings : PermissionRequestPlan
    data object OverlaySettings : PermissionRequestPlan
    data object Ready : PermissionRequestPlan
}

fun permissionRequestPlan(
    missingRuntime: List<String>,
    blockedRuntime: Set<String>,
    overlayGranted: Boolean
): PermissionRequestPlan {
    val requestable = missingRuntime.filterNot { it in blockedRuntime }
    return when {
        requestable.isNotEmpty() -> PermissionRequestPlan.Runtime(requestable)
        missingRuntime.isNotEmpty() -> PermissionRequestPlan.AppSettings
        !overlayGranted -> PermissionRequestPlan.OverlaySettings
        else -> PermissionRequestPlan.Ready
    }
}
