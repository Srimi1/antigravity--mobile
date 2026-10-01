package dev.srimi.antigravitymobile.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.srimi.antigravitymobile.providers.ConnectionStage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Live probes on the device's real network. Requires internet access on the test device. */
@RunWith(AndroidJUnit4::class)
class AndroidNetworkDiagnosticsTest {
    private val diagnostics = AndroidNetworkDiagnostics.shared(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun reachableProviderPassesEveryStage() = runBlocking {
        val report = diagnostics.diagnose("api.openai.com")
        assertTrue(report.summary, report.networkAvailable)
        assertNotNull(report.transport)
        assertTrue(report.resolvedAddresses.isNotEmpty())
        assertNull(report.summary, report.failedStage)
    }

    @Test fun unknownHostFailsAtDnsWithARecoveryAction() = runBlocking {
        val report = diagnostics.diagnose("no-such-host.invalid")
        assertEquals(ConnectionStage.Dns, report.failedStage)
        assertTrue(report.resolvedAddresses.isEmpty())
        assertNotNull(report.recovery)
    }

    @Test fun closedPortFailsAtTcp() = runBlocking {
        assertEquals(ConnectionStage.Tcp, diagnostics.diagnose("dns.google", 9).failedStage)
    }

    @Test fun wrongCertificateHostFailsAtTls() = runBlocking {
        // wrong.host.badssl.com serves a certificate for another name.
        assertEquals(ConnectionStage.Tls, diagnostics.diagnose("wrong.host.badssl.com").failedStage)
    }
}
