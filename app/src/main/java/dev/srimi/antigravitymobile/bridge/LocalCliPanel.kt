package dev.srimi.antigravitymobile.bridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.srimi.antigravitymobile.SectionCard
import dev.srimi.antigravitymobile.container
import dev.srimi.antigravitymobile.runtime.AgentBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens a CLI backend only after this phone's own probe; failures are runtime limits, never user decisions. */
@Composable fun LocalCliPanel() {
    val services = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<AgentBackend?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    SectionCard("Local CLI agents") {
        Text("Codex CLI and Antigravity CLI run inside Debian with their own sign-in, in a private copy of the project. " +
            "Each stays disabled until this phone proves ARM64 execution and a sandbox that refuses writes outside its workspace.",
            style = MaterialTheme.typography.bodySmall)
        listOf(AgentBackend.Codex, AgentBackend.AntigravityCli).forEach { backend ->
            val label = if (backend == AgentBackend.Codex) "Codex CLI" else "Antigravity CLI"
            val status = remember(revision) { services.cliGate.unavailable(backend)?.reason ?: "Enabled on this phone" }
            val probe = remember(revision) { services.cliGate.recorded(backend) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(status, style = MaterialTheme.typography.bodySmall)
                probe?.let { Text("Last check: ${it.machine}, ${it.version ?: "no version"}, sandbox ${it.sandbox}", style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(enabled = busy == null, onClick = {
                    busy = backend; error = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                check(services.database.runtime().active() == null) { "Finish or stop the current agent task first" }
                                services.cliGate.verify(backend)
                            }
                        } catch (failure: Exception) { error = "$label check failed: ${failure.message ?: "bridge unavailable"}" }
                        finally { busy = null; revision++ }
                    }
                }) { Text(if (busy == backend) "Checking…" else "Verify $label") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
