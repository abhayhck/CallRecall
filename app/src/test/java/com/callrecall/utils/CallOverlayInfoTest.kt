package com.callrecall.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CallOverlayInfoTest {
    @Test fun knownCallerRetainsExistingNoteIcon() = runBlocking {
        assertEquals(CallOverlayInfo("+919876543210", true), resolveCallOverlayInfo(
            { "+919876543210" }, { true }, { fail("Unexpected failure") }
        ))
    }

    @Test fun missingCallLogPermissionStillAllowsAnUnknownCallerBubble() = runBlocking {
        val failures = mutableListOf<Exception>()
        val info = resolveCallOverlayInfo(
            { throw SecurityException("Call log permission revoked") },
            { fail("An unknown caller must not query notes"); false },
            { failures.add(it) }
        )
        assertEquals(CallOverlayInfo("", false), info)
        assertEquals(1, failures.size)
    }

    @Test fun storageFailureDoesNotSuppressBubbleOrLoseResolvedNumber() = runBlocking {
        val failures = mutableListOf<Exception>()
        val info = resolveCallOverlayInfo(
            { "+442079460018" }, { throw IllegalStateException("Database unavailable") },
            { failures.add(it) }
        )
        assertEquals(CallOverlayInfo("+442079460018", false), info)
        assertEquals(1, failures.size)
    }

    @Test fun hiddenNumberDoesNotRequireANoteLookup() = runBlocking {
        assertEquals(CallOverlayInfo("", false), resolveCallOverlayInfo(
            { "" }, { fail("No lookup expected"); false }, { fail("No failure expected") }
        ))
    }

    @Test fun endedCallsCancelResolutionInsteadOfShowingStaleBubbles() = runBlocking {
        try {
            resolveCallOverlayInfo({ throw CancellationException("Call ended") }, { true }, { fail("Not a lookup failure") })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun cancellationDuringStorageLookupAlsoPropagates() = runBlocking {
        try {
            resolveCallOverlayInfo({ "+919876543210" }, { throw CancellationException("Call ended") }, { fail("Not a lookup failure") })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
