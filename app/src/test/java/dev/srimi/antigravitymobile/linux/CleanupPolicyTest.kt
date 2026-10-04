package dev.srimi.antigravitymobile.linux

import org.junit.Assert.*
import org.junit.Test

class CleanupPolicyTest {
    @Test fun cliStateIsProtectedWhileACliTaskHoldsTheSlot() {
        for (item in CleanupPolicy.TOUCHES_CLI_STATE) {
            assertNotNull(CleanupPolicy.blockedReason(setOf(item), "Codex"))
            assertNotNull(CleanupPolicy.blockedReason(setOf(item, CleanupItem.PackageCache), "AntigravityCli"))
        }
    }

    @Test fun otherCleanupAndNativeOrIdleStatesAreAllowed() {
        assertNull(CleanupPolicy.blockedReason(setOf(CleanupItem.PackageCache, CleanupItem.Desktop, CleanupItem.CliInstalls), "Codex"))
        assertNull(CleanupPolicy.blockedReason(setOf(CleanupItem.Workspaces, CleanupItem.Distribution), "Native"))
        assertNull(CleanupPolicy.blockedReason(setOf(CleanupItem.Workspaces, CleanupItem.Distribution), null))
    }
}
