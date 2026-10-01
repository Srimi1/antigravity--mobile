package dev.srimi.antigravitymobile

import android.content.pm.PackageManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.srimi.antigravitymobile.bridge.PairedBridgeConnection
import dev.srimi.antigravitymobile.linux.TermuxProtocol
import dev.srimi.antigravitymobile.runtime.AgentBackend
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

/**
 * Real Termux RUN_COMMAND, real proot Debian, real helper and the installed official Codex CLI binary.
 * No sign-in or inference. Skipped unless Termux is installed and RUN_COMMAND is granted (run with am instrument).
 */
@RunWith(AndroidJUnit4::class)
class TermuxBridgeDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun ready(): Boolean = runCatching { context.packageManager.getPackageInfo(TermuxProtocol.PACKAGE, 0) }.isSuccess &&
        context.checkSelfPermission(TermuxProtocol.PERMISSION) == PackageManager.PERMISSION_GRANTED

    @Test fun pairedHelperProbesInstalledCodexAndRejectsUnpairedClients() = runBlocking {
        assumeTrue("Termux with RUN_COMMAND granted is required", ready())
        val services = context.container
        services.ready.await()
        val probe = withTimeout(240_000) { services.cliGate.verify(AgentBackend.Codex) }
        Log.i("AgmEvidence", "codex probe: $probe")
        assertEquals("aarch64", probe.machine)
        assertTrue("installed official CLI runs: ${probe.version}", probe.version?.startsWith("codex-cli ") == true)
        assertTrue(probe.sandbox in setOf("confirmed", "unavailable", "escaped"))
        val gate = services.cliGate.unavailable(AgentBackend.Codex)
        assertEquals("gate opens only for a confirmed sandbox", probe.sandbox != "confirmed", gate != null)

        val port = services.cliLauncher.endpointPort!!
        val stranger = runCatching { PairedBridgeConnection.connect(port, "pair-stranger", ByteArray(32).also(SecureRandom()::nextBytes)).close() }
        assertTrue("unpaired client must be rejected", stranger.isFailure)
        // The paired daemon is reused, not relaunched, on the next connection.
        services.cliLauncher.connect().use { assertEquals("NOT_FOUND", it.call(JSONObject().put("op", "status").put("taskId", "no-such-task")).getString("state")) }
        assertEquals(port, services.cliLauncher.endpointPort)
    }
}
