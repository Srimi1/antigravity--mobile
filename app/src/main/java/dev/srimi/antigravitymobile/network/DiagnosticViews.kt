package dev.srimi.antigravitymobile.network

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.srimi.antigravitymobile.providers.ConnectionStage
import dev.srimi.antigravitymobile.providers.DiagnosticReport
import dev.srimi.antigravitymobile.providers.PrivateDnsMode

/** Reusable rendering of a [DiagnosticReport] (Accounts network check; Lane A may show it under "Paused: …"). */
@Composable fun DiagnosticDetails(report: DiagnosticReport) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(report.summary)
        report.recovery?.let { Text("What to do: $it", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary) }
        Text(NetworkHosts.facts(report), style = MaterialTheme.typography.bodySmall)
    }
}

object NetworkHosts {
    /** Accepts "host", "https://host/path" or "host:port"; returns a bare hostname or null. Never keeps paths or queries. */
    fun normalize(input: String): String? {
        val host = input.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':').lowercase().trimEnd('.')
        return host.takeIf { it.length in 1..253 && it.matches(Regex("[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?)*")) }
    }

    /** One line of facts, for the user to read or copy into a bug report. */
    fun facts(r: DiagnosticReport): String = listOf(
        "Host ${r.host}",
        "network ${r.transport ?: "none"}" + when (r.validated) { true -> " (internet verified)"; false -> " (no verified internet)"; null -> "" },
        "Private DNS " + when (r.privateDns) { PrivateDnsMode.Off -> "off"; PrivateDnsMode.Automatic -> "automatic"
            PrivateDnsMode.Strict -> "\"${r.privateDnsServer ?: "custom"}\""; PrivateDnsMode.Unknown -> "unknown" },
        if (r.vpnActive) "VPN on" else "no VPN",
        if (r.captivePortal) "sign-in page required" else null,
        if (r.resolvedAddresses.isEmpty()) "no DNS answer" else "resolves to ${r.resolvedAddresses.joinToString()}",
        "failed at " + when (r.failedStage) { null -> "nothing (all checks passed)"; ConnectionStage.Network -> "network"
            ConnectionStage.Dns -> "DNS"; ConnectionStage.Tcp -> "connection"; ConnectionStage.Tls -> "TLS"
            ConnectionStage.Http -> "HTTP"; ConnectionStage.Stream -> "streaming" },
        if (r.networkChanges > 0) "${r.networkChanges} network change(s) in the last 3 minutes" else null,
    ).filterNotNull().joinToString(" · ")
}
