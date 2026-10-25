package com.callrecall.utils

import org.junit.Assert.*
import org.junit.Test

class PermissionRequestPlanTest {
    @Test fun initialRequestContainsOnlyMissingRuntimePermissions() {
        assertEquals(
            PermissionRequestPlan.Runtime(listOf("phone", "outgoing", "notifications")),
            permissionRequestPlan(listOf("phone", "outgoing", "notifications"), emptySet(), false)
        )
    }

    @Test fun deniedPermissionCanBeRequestedAgainAfterRationale() {
        assertEquals(PermissionRequestPlan.Runtime(listOf("phone")),
            permissionRequestPlan(listOf("phone"), emptySet(), false))
    }

    @Test fun permanentDenialGoesToAppSettingsInsteadOfLoopingRequests() {
        assertEquals(PermissionRequestPlan.AppSettings,
            permissionRequestPlan(listOf("phone"), setOf("phone"), false))
    }

    @Test fun requestablePermissionsAreHandledBeforeBlockedOnes() {
        assertEquals(PermissionRequestPlan.Runtime(listOf("contacts")),
            permissionRequestPlan(listOf("phone", "contacts"), setOf("phone"), false))
    }

    @Test fun overlaySettingsWaitUntilRuntimePermissionsAreGranted() {
        assertEquals(PermissionRequestPlan.OverlaySettings,
            permissionRequestPlan(emptyList(), emptySet(), false))
    }

    @Test fun returningFromSettingsReevaluatesActualGrants() {
        assertEquals(PermissionRequestPlan.Ready,
            permissionRequestPlan(emptyList(), setOf("phone"), true))
    }

    @Test fun revokingPermissionRequiresSetupAgainEvenIfOverlayIsEnabled() {
        assertEquals(PermissionRequestPlan.Runtime(listOf("phone")),
            permissionRequestPlan(listOf("phone"), emptySet(), true))
    }
}
