package dev.srimi.antigravitymobile.network

import dev.srimi.antigravitymobile.providers.ConnectionStage
import dev.srimi.antigravitymobile.providers.PrivateDnsMode
import org.junit.Assert.*
import org.junit.Test

class DiagnosisRulesTest {
    private val healthy = NetworkFacts("api.groq.com", "Wi-Fi", true, true, false, false, PrivateDnsMode.Automatic, null, true,
        listOf("192.0.2.10"), dnsOk = true, tcpOk = true, tlsOk = true)

    @Test fun noNetwork() {
        val r = DiagnosisRules.explain(healthy.copy(networkAvailable = false, transport = null, dnsOk = null, tcpOk = null, tlsOk = null))
        assertEquals(ConnectionStage.Network, r.failedStage)
        assertTrue(r.recovery!!.contains("Wi-Fi or mobile data"))
    }

    @Test fun captivePortalComesBeforeDns() {
        val r = DiagnosisRules.explain(healthy.copy(captivePortal = true, dnsOk = false))
        assertEquals(ConnectionStage.Network, r.failedStage)
        assertTrue(r.recovery!!.contains("sign in"))
    }

    @Test fun strictPrivateDnsGivesTheSettingsFix() {
        val r = DiagnosisRules.explain(healthy.copy(privateDns = PrivateDnsMode.Strict, privateDnsServer = "dns.adguard.example", dnsOk = false, resolvedAddresses = emptyList()))
        assertEquals(ConnectionStage.Dns, r.failedStage)
        assertTrue(r.summary.contains("dns.adguard.example"))
        assertTrue(r.recovery!!.startsWith("Private DNS hostname unreachable"))
        assertTrue(r.recovery!!.contains("Automatic"))
    }

    @Test fun vpnAndUnvalidatedDns() {
        assertTrue(DiagnosisRules.explain(healthy.copy(vpnActive = true, dnsOk = false)).recovery!!.contains("VPN"))
        val r = DiagnosisRules.explain(healthy.copy(transport = "Mobile data", validated = false, dnsOk = false))
        assertTrue(r.recovery!!.contains("a Wi-Fi network"))
    }

    @Test fun filteredDnsOnWifiSuggestsMobileData() {
        val r = DiagnosisRules.explain(healthy.copy(dnsOk = false))
        assertEquals(ConnectionStage.Dns, r.failedStage)
        assertTrue(r.recovery!!.contains("mobile data"))
    }

    @Test fun tcpAndTlsStages() {
        assertEquals(ConnectionStage.Tcp, DiagnosisRules.explain(healthy.copy(tcpOk = false, tlsOk = null)).failedStage)
        val tls = DiagnosisRules.explain(healthy.copy(tlsOk = false, tlsError = "SSLHandshakeException"))
        assertEquals(ConnectionStage.Tls, tls.failedStage)
        assertTrue(tls.recovery!!.contains("date and time"))
    }

    @Test fun healthyPathBlamesProviderAndNetworkSwitchSaysRetry() {
        val ok = DiagnosisRules.explain(healthy)
        assertNull(ok.failedStage)
        assertNull(ok.recovery)
        assertEquals("Retry the request.", DiagnosisRules.explain(healthy.copy(networkChanges = 2)).recovery)
    }
}
