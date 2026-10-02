package dev.srimi.antigravitymobile.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxPermissionStepTest {
    @Test fun firstTapShowsAndroidsDialog() {
        assertEquals(TermuxPermissionStep.RequestDialog, TermuxPermissionStep.onAllow(granted = false, askedBefore = false, showRationale = false))
    }

    @Test fun afterOneDenialAndroidStillShowsTheDialog() {
        assertEquals(TermuxPermissionStep.RequestDialog, TermuxPermissionStep.onAllow(granted = false, askedBefore = true, showRationale = true))
    }

    @Test fun afterAndroidStopsAskingAllowOpensSettingsInsteadOfDoingNothing() {
        // Reproduced on Android 12 and 16: after two denials (or a dismissed dialog) the permission is USER_FIXED and
        // requestPermissions returns at once without UI, so the old Allow button did nothing.
        assertEquals(TermuxPermissionStep.OpenSettings, TermuxPermissionStep.onAllow(granted = false, askedBefore = true, showRationale = false))
    }

    @Test fun deniedResultWithoutRationaleOpensSettings() {
        assertEquals(TermuxPermissionStep.OpenSettings, TermuxPermissionStep.afterResult(granted = false, showRationale = false))
        assertEquals(TermuxPermissionStep.Done, TermuxPermissionStep.afterResult(granted = true, showRationale = false))
        assertEquals(TermuxPermissionStep.Explain, TermuxPermissionStep.afterResult(granted = false, showRationale = true))
    }

    @Test fun grantedNeedsNothing() {
        assertEquals(TermuxPermissionStep.Done, TermuxPermissionStep.onAllow(granted = true, askedBefore = true, showRationale = false))
    }

    @Test fun deniedMessageSaysWhereToGrantIt() {
        val message = TermuxUnavailable.PermissionDenied().message!!
        assertTrue(message.contains("Additional permissions"))
        assertTrue(message.contains("Run commands in Termux environment"))
    }
}
