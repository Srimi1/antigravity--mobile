package dev.srimi.antigravitymobile.linux

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.srimi.antigravitymobile.SectionCard
import dev.srimi.antigravitymobile.StatusChip
import dev.srimi.antigravitymobile.friendly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LinuxUiState(
    val termux: TermuxStatus? = null,
    val linux: LinuxStatus? = null,
    val storage: StorageUsage? = null,
    val clis: List<CliInstall> = emptyList(),
    val busy: String? = null,
    val progress: String? = null,
    val message: String? = null,
)

class LinuxViewModel(application: Application) : AndroidViewModel(application) {
    private val gateway = AndroidTermuxGateway(application)
    private val runtime = TermuxLinuxRuntime(application, gateway)
    private val mutable = MutableStateFlow(LinuxUiState())
    val state: StateFlow<LinuxUiState> = mutable.asStateFlow()
    private var job: Job? = null

    init { refresh() }

    fun refresh() = run("Checking", quiet = true) {
        val termux = gateway.status()
        mutable.update { it.copy(termux = termux) }
        val linux = runtime.status()
        mutable.update { it.copy(linux = linux) }
        if (linux.state == LinuxState.Stopped || linux.state == LinuxState.Running)
            mutable.update { it.copy(storage = runtime.storageUsage(), clis = runtime.installedClis()) }
        null
    }

    private fun run(label: String, quiet: Boolean = false, block: suspend () -> String?) {
        if (state.value.busy != null) return
        mutable.update { it.copy(busy = label, progress = null, message = null) }
        job = viewModelScope.launch {
            val message = try { block() } catch (_: CancellationException) { "$label stopped here; anything already running in Termux continues" }
                catch (unavailable: TermuxUnavailable) { unavailable.message } catch (error: Exception) { "$label failed: ${friendly(error)}" }
            mutable.update { it.copy(busy = null, progress = null, message = message ?: if (quiet) null else "$label finished") }
        }
    }
    private fun progress(step: String) = mutable.update { it.copy(progress = step) }
    private fun afterwards(block: suspend () -> String?): suspend () -> String? = { val m = block(); refreshNow(); m }
    private suspend fun refreshNow() {
        val linux = runtime.status()
        mutable.update { it.copy(termux = gateway.status(), linux = linux) }
        if (linux.state == LinuxState.Stopped || linux.state == LinuxState.Running)
            mutable.update { it.copy(storage = runtime.storageUsage(), clis = runtime.installedClis()) }
    }

    fun cancel() { job?.cancel() }
    fun dismiss() = mutable.update { it.copy(message = null) }
    fun install(desktop: Boolean) = run(if (desktop) "Install Debian with desktop" else "Install Debian", block = afterwards {
        runtime.ensureInstalled(desktop, ::progress).detail
    })
    fun start(desktop: Boolean) = run("Start", block = afterwards { runtime.start(desktop).detail })
    fun stop() = run("Stop", block = afterwards { runtime.stop().detail })
    fun cleanup(items: Set<CleanupItem>) = run("Clean up", block = afterwards {
        val result = runtime.cleanup(items)
        "Removed: ${result.removed.joinToString { it.name }.ifEmpty { "nothing" }}" +
            (result.freedBytes?.let { ", freed ${it / 1_048_576} MB" } ?: "") +
            (if (result.failures.isNotEmpty()) ". Not removed: ${result.failures.keys.joinToString { it.name }}" else "")
    })
    fun installCli(tool: CliTool) = run("Install ${tool.label}", block = afterwards {
        val cli = runtime.installCli(tool, ::progress)
        "${tool.label} ${cli.version ?: ""} installed at ${cli.binaryPath}. Sign in inside it (Open Debian terminal)."
    })
    fun openTerminal() = run("Open terminal") { runtime.openTerminal(); "Termux opened inside Debian. Run the CLI there to sign in." }
}

private fun copy(context: Context, text: String) =
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("command", text))
private fun open(context: Context, url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

/** Build-tab panel: guided Termux + Debian 12 + XFCE setup. Lane A inserts it into BuildScreen. */
@Composable fun LinuxSetupPanel() {
    val model: LinuxViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val idle = state.busy == null
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.refresh() }
    var confirm by remember { mutableStateOf<Set<CleanupItem>?>(null) }

    SectionCard("Linux on this phone (Termux)") {
        Text("Debian 12 runs inside the Termux app as a normal user (no root, Android's own kernel). Everything this app adds lives in one " +
            "container named agm-debian; your existing Termux, its packages and other containers are left alone.", style = MaterialTheme.typography.bodySmall)
        state.busy?.let { label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(listOfNotNull(label, state.progress).joinToString(": "), Modifier.weight(1f))
                TextButton(onClick = model::cancel) { Text("Stop waiting") }
            }
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall); TextButton(onClick = model::dismiss) { Text("OK") } }

        val termux = state.termux
        // Step 1: Termux installed
        StepRow("1. Termux app", termux?.installed == true, termux?.versionName?.let { "Installed ($it)" } ?: "Not installed")
        if (termux?.installed == false) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { open(context, "https://f-droid.org/packages/com.termux/") }) { Text("F-Droid") }
            OutlinedButton(onClick = { open(context, "https://github.com/termux/termux-app/releases") }) { Text("GitHub") }
        }
        // Step 2: RUN_COMMAND permission
        if (termux?.installed == true) {
            StepRow("2. Permission to run commands in Termux", termux.runCommandPermission,
                if (termux.runCommandPermission) "Granted" else "Not granted (runtime limitation until you allow it)")
            if (!termux.runCommandPermission) Button(onClick = {
                if (context.checkSelfPermission(TermuxProtocol.PERMISSION) != PackageManager.PERMISSION_GRANTED) permission.launch(TermuxProtocol.PERMISSION)
            }, enabled = idle) { Text("Allow") }
            // Step 3: allow-external-apps
            StepRow("3. Termux accepts commands from this app", termux.externalAppsAllowed == true, when (termux.externalAppsAllowed) {
                true -> "Allowed"; false -> "Off in Termux settings"; null -> "Unknown until the first command" })
            if (termux.externalAppsAllowed != true) {
                Text("Open Termux, paste and run this once:", style = MaterialTheme.typography.bodySmall)
                Text(TermuxProtocol.ALLOW_EXTERNAL_APPS_COMMAND, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copy(context, TermuxProtocol.ALLOW_EXTERNAL_APPS_COMMAND) }) { Text("Copy") }
                    OutlinedButton(onClick = { context.packageManager.getLaunchIntentForPackage(TermuxProtocol.PACKAGE)?.let(context::startActivity) }) { Text("Open Termux") }
                }
            }
        }
        // Step 4: Debian
        state.linux?.let { linux ->
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Debian 12", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                StatusChip(linux.state.name.lowercase(), when (linux.state) {
                    LinuxState.Running -> "RUNNING"; LinuxState.Stopped -> "PASSED"; LinuxState.Installing, LinuxState.Starting, LinuxState.Stopping -> "RUNNING"
                    LinuxState.NotInstalled, LinuxState.TermuxMissing -> "DISCONNECTED"; LinuxState.Failed -> "FAILED" })
            }
            Text(linux.detail, style = MaterialTheme.typography.bodySmall)
            val ready = termux?.ready == true || termux?.runCommandPermission == true
            when (linux.state) {
                LinuxState.NotInstalled, LinuxState.Failed -> if (ready) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { model.install(false) }, enabled = idle) { Text("Install") }
                    OutlinedButton(onClick = { model.install(true) }, enabled = idle) { Text("Install with desktop") }
                }
                LinuxState.Stopped -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (linux.desktopInstalled) Button(onClick = { model.start(true) }, enabled = idle && termux?.x11Installed == true) { Text("Start desktop") }
                    else OutlinedButton(onClick = { model.install(true) }, enabled = idle) { Text("Add desktop") }
                    OutlinedButton(onClick = model::openTerminal, enabled = idle) { Text("Open terminal") }
                }
                LinuxState.Running -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = model::stop, enabled = idle) { Text("Stop desktop") }
                    OutlinedButton(onClick = model::openTerminal, enabled = idle) { Text("Open terminal") }
                }
                else -> Unit
            }
            if (linux.desktopInstalled && termux?.x11Installed != true) {
                Text("The desktop needs the Termux:X11 app (install the APK from its GitHub nightly release).", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { open(context, "https://github.com/termux/termux-x11/releases/tag/nightly") }) { Text("Termux:X11 releases") }
            }
            if (linux.desktopInstalled) Text("Desktop on this phone: unverified until it starts, signs in and runs a real task here.", style = MaterialTheme.typography.bodySmall)
        }
        // Storage and cleanup
        state.storage?.let { usage ->
            HorizontalDivider()
            fun mb(b: Long?) = b?.let { "${it / 1_048_576} MB" } ?: "unknown"
            Text("Storage: Debian ${mb(usage.distributionBytes)} (package cache ${mb(usage.packageCacheBytes)}, CLIs ${mb(usage.cliBytes)}, workspaces ${mb(usage.workspaceBytes)})",
                style = MaterialTheme.typography.bodySmall)
            CleanupChooser(idle) { confirm = it }
        }
        OutlinedButton(onClick = model::refresh, enabled = idle) { Text("Refresh") }
    }
    confirm?.let { items ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text("Remove ${items.joinToString { it.name }}?") },
            text = { Text(if (CleanupItem.Distribution in items) "The whole agm-debian container is deleted, including signed-in CLIs and anything saved inside it. " +
                "Termux itself, your other containers and your Termux files are not touched." else "Only these parts inside agm-debian are removed.") },
            confirmButton = { TextButton(onClick = { model.cleanup(items); confirm = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
}

@Composable private fun StepRow(title: String, done: Boolean, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title); Text(detail, style = MaterialTheme.typography.bodySmall) }
        StatusChip(if (done) "done" else "to do", if (done) "PASSED" else "UNVERIFIED")
    }
}

@Composable private fun CleanupChooser(idle: Boolean, onRemove: (Set<CleanupItem>) -> Unit) {
    var selected by remember { mutableStateOf(emptySet<CleanupItem>()) }
    Text("Clean up", style = MaterialTheme.typography.labelLarge)
    CleanupItem.entries.forEach { item ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(item in selected, { selected = if (it) selected + item else selected - item })
            Text(when (item) {
                CleanupItem.PackageCache -> "Downloaded package cache"; CleanupItem.Workspaces -> "Project copies in Debian"
                CleanupItem.CliInstalls -> "Installed CLIs"; CleanupItem.Desktop -> "XFCE desktop"; CleanupItem.Distribution -> "Entire Debian container"
            }, style = MaterialTheme.typography.bodySmall)
        }
    }
    OutlinedButton(onClick = { onRemove(selected) }, enabled = idle && selected.isNotEmpty()) { Text("Remove selected") }
}

/** Accounts-tab section: the official CLIs inside Debian and their sign-in status. */
@Composable fun CliAccountsSection() {
    val model: LinuxViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val idle = state.busy == null
    val usable = state.linux?.state == LinuxState.Stopped || state.linux?.state == LinuxState.Running
    SectionCard("CLI accounts (phone-local Linux)") {
        Text("Each CLI signs in with its own official flow inside Debian; this app never reads their credentials. A CLI counts as verified " +
            "only after a real task completes through it on this phone.", style = MaterialTheme.typography.bodySmall)
        if (!usable) { Text("Set up Linux in the Build tab first.", style = MaterialTheme.typography.bodySmall); return@SectionCard }
        state.busy?.let { Text(listOfNotNull(it, state.progress).joinToString(": "), style = MaterialTheme.typography.bodySmall) }
        val installed = state.clis.associateBy { it.tool }
        listOf(CliTool.CODEX to "codex login", CliTool.ANTIGRAVITY to "agy", CliTool.CLAUDE_CODE to "claude", CliTool.GEMINI to "gemini").forEach { (tool, signIn) ->
            val cli = installed[tool]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tool.label)
                    Text(if (cli?.installed == true) "${cli.version ?: "version unknown"} · sign-in: run `$signIn` in the Debian terminal · unverified"
                        else "Not installed", style = MaterialTheme.typography.bodySmall)
                }
                if (cli?.installed != true) TextButton(onClick = { model.installCli(tool) }, enabled = idle) { Text("Install") }
            }
        }
        Text("Gemini CLI stopped serving Google sign-in (free and Google AI Pro/Ultra) on 18 June 2026; Google points those users to the " +
            "Antigravity CLI. Gemini CLI stays available with paid Gemini API keys (free-key use is not stated). Google subscription access " +
            "counts only after a real request through the Antigravity CLI with your account.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = model::openTerminal, enabled = idle) { Text("Open Debian terminal") }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
