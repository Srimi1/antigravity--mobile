package dev.srimi.antigravitymobile.network

import dev.srimi.antigravitymobile.providers.ConnectionStage
import dev.srimi.antigravitymobile.providers.DiagnosticReport
import dev.srimi.antigravitymobile.providers.PrivateDnsMode

/** Raw observations from one probe run. Contains no credentials. */
data class NetworkFacts(
    val host: String,
    val transport: String?,
    val networkAvailable: Boolean,
    val validated: Boolean?,
    val captivePortal: Boolean,
    val vpnActive: Boolean,
    val privateDns: PrivateDnsMode,
    val privateDnsServer: String?,
    /** Android reports Private DNS as in use on the active network. */
    val privateDnsActive: Boolean,
    val resolvedAddresses: List<String>,
    /** null = not attempted (an earlier stage failed). */
    val dnsOk: Boolean?,
    val tcpOk: Boolean?,
    val tlsOk: Boolean?,
    val tlsError: String? = null,
    val networkChanges: Int = 0,
    /** Whether a well-known control name resolved on the same network when [host] did not. null = not tried. */
    val controlDnsOk: Boolean? = null,
)

/** Turns observations into the first failing stage, a summary and a specific recovery action. Pure. */
object DiagnosisRules {
    fun explain(f: NetworkFacts, now: Long = System.currentTimeMillis()): DiagnosticReport {
        val (stage, summary, recovery) = when {
            !f.networkAvailable -> Triple(ConnectionStage.Network, "The phone has no active network connection.",
                "Turn on Wi-Fi or mobile data (and turn off Airplane mode), then retry.")
            f.captivePortal -> Triple(ConnectionStage.Network, "This ${net(f)} requires signing in on a web page before it allows internet access.",
                "Open the network sign-in notification (or a browser) and sign in to the ${net(f)}, then retry.")
            f.dnsOk == false -> dnsFailure(f)
            f.tcpOk == false -> Triple(ConnectionStage.Tcp, "${f.host} was found (${f.resolvedAddresses.take(2).joinToString()}) but the connection to it was refused or blocked.",
                if (f.vpnActive) "A VPN is active and may be blocking ${f.host}. Pause the VPN or allow Antigravity Mobile in it, then retry."
                else "This ${net(f)} appears to block ${f.host} (firewall or filter). Try ${other(f)}, then retry.")
            f.tlsOk == false -> Triple(ConnectionStage.Tls, "A secure (TLS) connection to ${f.host} could not be made" +
                (f.tlsError?.let { " ($it)" } ?: "") + ". Something on the path may be intercepting it.",
                "Check that the phone's date and time are set automatically. If they are, this ${net(f)} (or a VPN/filter app) is interfering; try ${other(f)}.")
            f.networkChanges > 0 -> Triple(null, "The phone switched networks during the request; the connection to ${f.host} works now.",
                "Retry the request.")
            else -> Triple(null, "The network reached ${f.host} normally (DNS, connection and TLS all worked). If a request failed, the cause was the provider or the app, not this network.",
                null)
        }
        return DiagnosticReport(f.host, now, f.transport, f.networkAvailable, f.validated, f.captivePortal, f.vpnActive,
            f.privateDns, f.privateDnsServer, f.resolvedAddresses, stage, summary, recovery, f.networkChanges)
    }

    private fun dnsFailure(f: NetworkFacts): Triple<ConnectionStage, String, String> {
        val base = "The address of ${f.host} could not be looked up (DNS) on the ${net(f)}"
        return when {
            f.controlDnsOk == true && f.privateDns != PrivateDnsMode.Strict && !f.vpnActive -> Triple(ConnectionStage.Dns,
                "$base, although other names resolve. The name may be misspelled, may not exist, or this network's DNS blocks it.",
                "Check the spelling. If it is correct, try ${other(f)}; if it works there, this network is blocking ${f.host} (change Private DNS to a public resolver such as dns.google, or ask the network owner).")
            f.privateDns == PrivateDnsMode.Strict && f.controlDnsOk == true -> Triple(ConnectionStage.Dns,
                "$base. Private DNS \"${f.privateDnsServer ?: "custom server"}\" answers for other names but not this one, so it is likely blocking it.",
                "Private DNS is blocking ${f.host} — open Settings → Network & internet → Private DNS, choose Automatic, then retry.")
            f.privateDns == PrivateDnsMode.Strict -> Triple(ConnectionStage.Dns,
                "$base. Private DNS is set to \"${f.privateDnsServer ?: "a custom server"}\", which is not answering or is blocking it.",
                "Private DNS hostname unreachable — open Settings → Network & internet → Private DNS, choose Automatic, then retry.")
            f.vpnActive -> Triple(ConnectionStage.Dns, "$base while a VPN is active; the VPN's DNS may be blocking it.",
                "Pause the VPN or ad-blocking app, or allow ${f.host} in it, then retry.")
            f.validated == false -> Triple(ConnectionStage.Dns, "$base. Android also reports this network has no working internet.",
                "Switch to ${other(f)} (or restart the router), then retry.")
            else -> Triple(ConnectionStage.Dns, "$base. Other sites may work, so this network's DNS may be filtering ${f.host}.",
                "Try ${other(f)}. If it keeps happening on this network, set Settings → Network & internet → Private DNS to a public resolver hostname such as dns.google, then retry.")
        }
    }

    private fun net(f: NetworkFacts) = when (f.transport) { "Wi-Fi" -> "Wi-Fi network"; "Mobile data" -> "mobile network"; null, "unknown" -> "network"; else -> "${f.transport} network" }
    private fun other(f: NetworkFacts) = when (f.transport) { "Wi-Fi" -> "mobile data"; "Mobile data" -> "a Wi-Fi network"; else -> "another network" }
}
