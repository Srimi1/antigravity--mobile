package dev.srimi.antigravitymobile.linux

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
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
import dev.srimi.antigravitymobile.container
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
        try { refreshNow() }
        // The first command is what reveals allow-external-apps, so show that result in step 3 as well.
        finally { mutable.update { it.copy(termux = gateway.status()) } }
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

    private fun cliEnvironmentChanged() = getApplication<Application>().container.cliGate.environmentChanged()
    fun cancel() { job?.cancel() }
    fun dismiss() = mutable.update { it.copy(message = null) }
    fun note(text: String) = mutable.update { it.copy(message = text) }
    fun install(desktop: Boolean) = run(if (desktop) "Install Debian with desktop" else "Install Debian", block = afterwards {
        try { runtime.ensureInstalled(desktop, ::progress).detail } finally { cliEnvironmentChanged() }
    })
    fun start(desktop: Boolean) = run("Start", block = afterwards { runtime.start(desktop).detail })
    fun stop() = run("Stop", block = afterwards { runtime.stop().detail })
    fun cleanup(items: Set<CleanupItem>) = run("Clean up", block = afterwards {
        val active = getApplication<Application>().container.let { it.ready.await(); it.database.runtime().active() }
        CleanupPolicy.blockedReason(items, active?.backend)?.let { return@afterwards it }
        val result = try { runtime.cleanup(items) }
            finally { if (items.any { it == CleanupItem.CliInstalls || it == CleanupItem.Distribution }) cliEnvironmentChanged() }
        "Removed: ${result.removed.joinToString { it.name }.ifEmpty { "nothing" }}" +
            (result.freedBytes?.let { ", freed ${it / 1_048_576} MB" } ?: "") +
            (if (result.failures.isNotEmpty()) ". Not removed: ${result.failures.keys.joinToString { it.name }}" else "")
    })
    fun installCli(tool: CliTool) = run("Install ${tool.label}", block = afterwards {
        val cli = try { runtime.installCli(tool, ::progress) } finally { cliEnvironmentChanged() }
        "${tool.label} ${cli.version ?: ""} installed at ${cli.binaryPath}. Sign in inside it (Open Debian terminal)."
    })
    fun openTermux() { try { gateway.openApp() } catch (error: TermuxUnavailable) { note(error.message.orEmpty()) } }
    fun openTerminal() = run("Open terminal") { runtime.openTerminal(); "Debian terminal requested. Continue in Termux." }
    fun signIn(tool: CliTool) = run("Open ${tool.label}") { runtime.openTerminal(tool); "Continue sign-in inside ${tool.label} in Termux." }
    fun openGoogleCli() = run("Open Google sign-in") {
        GoogleCliCommands.open(gateway)
        "Continue in Termux: choose Google OAuth and sign in in your browser."
    }
}

private fun copy(context: Context, text: String) =
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("command", text))
private fun open(context: Context, url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
private fun openAppSettings(context: Context) = runCatching {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
private tailrec fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }
private fun showRationale(context: Context) =
    context.activity()?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, TermuxProtocol.PERMISSION) } == true
private const val SETTINGS_PATH = "Permissions → Additional permissions → Run commands in Termux environment → Allow"

/** The same working permission path is available beside Google login and in Build. */
@Composable private fun TermuxPermissionControls(model: LinuxViewModel) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("termux", Context.MODE_PRIVATE) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when (TermuxPermissionStep.afterResult(granted, showRationale(context))) {
            TermuxPermissionStep.OpenSettings -> {
                model.note("Android did not show its permission dialog. In the Settings page that opened: $SETTINGS_PATH, then come back.")
                openAppSettings(context)
            }
            TermuxPermissionStep.Explain -> model.note("Not allowed. Tap Allow again and choose Allow in Android's dialog.")
            else -> Unit
        }
        model.refresh()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            val granted = context.checkSelfPermission(TermuxProtocol.PERMISSION) == PackageManager.PERMISSION_GRANTED
            when (TermuxPermissionStep.onAllow(granted, prefs.getBoolean("runCommandAsked", false), showRationale(context))) {
                TermuxPermissionStep.RequestDialog -> {
                    prefs.edit().putBoolean("runCommandAsked", true).apply()
                    permission.launch(TermuxProtocol.PERMISSION)
                }
                TermuxPermissionStep.OpenSettings -> {
                    model.note("Allow it in the Settings page that opened: $SETTINGS_PATH, then come back.")
                    openAppSettings(context).onFailure { model.note("Open Android Settings → Apps → Antigravity Mobile → $SETTINGS_PATH.") }
                }
                else -> model.refresh()
            }
        }) { Text("Allow") }
        OutlinedButton(onClick = {
            openAppSettings(context).onFailure { model.note("Open Android Settings → Apps → Antigravity Mobile → $SETTINGS_PATH.") }
        }) { Text("Open settings") }
    }
    Text("If no Android dialog appears, allow it in Settings: $SETTINGS_PATH.", style = MaterialTheme.typography.bodySmall)
}

/** Build-tab panel: guided Termux + Debian 12 + XFCE setup. */
@Composable fun LinuxSetupPanel() {
    val model: LinuxViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val idle = state.busy == null
    // Coming back from Settings or Termux re-checks the permission and whether Termux now accepts commands.
    LifecycleResumeEffect(Unit) { model.refresh(); onPauseOrDispose { } }
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
            if (!termux.runCommandPermission) {
                TermuxPermissionControls(model)
            }
            // Step 3: allow-external-apps
            StepRow("3. Termux accepts commands from this app", termux.externalAppsAllowed == true, when (termux.externalAppsAllowed) {
                true -> "Allowed"; false -> "Off in Termux settings"; null -> "Unknown until the first command" })
            if (termux.externalAppsAllowed != true) {
                Text("Open Termux, paste and run this once:", style = MaterialTheme.typography.bodySmall)
                Text("Press Enter on the keyboard. When it prints Done, come back here; this step updates by itself" +
                    (if (!termux.runCommandPermission) " once step 2 is allowed" else "") +
                    ". If Termux asks \"Display all … possibilities?\", press n: the Tab key was pressed, nothing is wrong.",
                    style = MaterialTheme.typography.bodySmall)
                Text(TermuxProtocol.ALLOW_EXTERNAL_APPS_COMMAND, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copy(context, TermuxProtocol.ALLOW_EXTERNAL_APPS_COMMAND) }) { Text("Copy") }
                    OutlinedButton(onClick = model::openTermux) { Text("Open Termux") }
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

/** Google account login uses the official Android CLI; Debian and a desktop keyring are not prerequisites. */
@Composable fun GoogleCliSignInSection() {
    val model: LinuxViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) { model.refresh(); onPauseOrDispose { } }
    SectionCard("Gemini with Google sign-in") {
        Text("Sign in with your Google account in Google's official Antigravity CLI. It opens in Termux, then opens your browser. " +
            "Debian is not needed for this sign-in.", style = MaterialTheme.typography.bodySmall)
        val termux = state.termux
        if (termux?.installed == false) {
            Text("Install Termux, open it once to finish setup, then return here.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { open(context, "https://github.com/termux/termux-app/releases") }) { Text("Install Termux") }
        }
        if (termux?.installed == true && !termux.runCommandPermission) TermuxPermissionControls(model)
        if (termux?.runCommandPermission == true && termux.externalAppsAllowed != true) {
            Text("In Termux, paste this command and press Enter once:", style = MaterialTheme.typography.bodySmall)
            Text(TermuxProtocol.ALLOW_EXTERNAL_APPS_COMMAND, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { copy(context, TermuxProtocol.ALLOW_EXTERNAL_APPS_COMMAND); model.openTermux() }) { Text("Copy setup and open Termux") }
        }
        state.busy?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = model::openGoogleCli, enabled = state.busy == null && termux?.ready == true) { Text("Sign in with Google") }
        OutlinedButton(onClick = model::openTermux, enabled = termux?.installed == true) { Text("Open Termux") }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("The first launch downloads Google's Android CLI; sign-in stays inside that client. In-app CLI Agent chat still needs a passing " +
            "sandbox check. A Google login here does not connect the AI Studio API-key account below.", style = MaterialTheme.typography.bodySmall)
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
        Text("For Gemini with your personal Google account or Google AI Pro/Ultra, use Antigravity CLI: run agy, complete its sign-in yourself, " +
            "then use /model or agy models to choose Gemini. Consumer Gemini CLI access moved to Antigravity CLI. " +
            "Agent chat stays disabled until the phone's sandbox check passes.", style = MaterialTheme.typography.bodySmall)
        if (!usable) {
            Text("Set up Linux in the Build tab first. Allow command access, then install Debian.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = model::openTermux) { Text("Open Termux") }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            return@SectionCard
        }
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
                else TextButton(onClick = { model.signIn(tool) }, enabled = idle) {
                    Text(if (tool == CliTool.ANTIGRAVITY) "Google sign-in" else "Open CLI")
                }
            }
        }
        Text("Gemini CLI stopped serving Google sign-in (free and Google AI Pro/Ultra) on 18 June 2026; Google points those users to the " +
            "Antigravity CLI. Gemini CLI stays available with paid Gemini API keys (free-key use is not stated). Google subscription access " +
            "counts only after a real request through the Antigravity CLI with your account.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = model::openTerminal, enabled = idle) { Text("Open Debian terminal") }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
