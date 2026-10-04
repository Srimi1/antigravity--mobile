package dev.srimi.antigravitymobile.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun termuxInstalledAfterTheAppNeedsAnAppUpdateNotADialog() {
        // Android drops a custom permission it did not know at install time: Settings lists nothing and
        // requestPermissions fails silently, so Allow must explain the update instead.
        assertFalse(TermuxPermissionStep.registered(termuxFirstInstall = 2_000, appLastUpdate = 1_000))
        assertTrue(TermuxPermissionStep.registered(termuxFirstInstall = 1_000, appLastUpdate = 2_000))
        assertEquals(TermuxPermissionStep.UpdateApp,
            TermuxPermissionStep.onAllow(granted = false, askedBefore = false, showRationale = false, registered = false))
        assertEquals(TermuxPermissionStep.Done,
            TermuxPermissionStep.onAllow(granted = true, askedBefore = false, showRationale = false, registered = false))
    }

    @Test fun setupCommandCannotLeaveTheShellWaitingForAQuote() {
        val command = TermuxProtocol.ALLOW_EXTERNAL_APPS_COMMAND
        for (risky in listOf("'", "\"", "\\", "`", "&&", "|", "(", ")", "{", "}")) assertFalse("contains $risky", command.contains(risky))
        assertTrue(command.contains("allow-external-apps=true >> ~/.termux/termux.properties"))
        assertTrue(command.contains("termux-reload-settings"))
        // Every cut-off prefix is still balanced, so a partial paste just runs (or fails) and returns to the prompt.
        assertTrue(command.indices.all { i -> command.take(i).count { it == '\'' } % 2 == 0 })
    }
}
