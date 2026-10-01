package dev.srimi.antigravitymobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

object ApkInstaller {
    data class Launch(val opened: Boolean, val message: String)
    /** Copies an APK into the FileProvider-shared install folder and opens Android's installer. */
    suspend fun install(context: Context, open: () -> InputStream): String = launch(context, open).message
    suspend fun launch(context: Context, open: () -> InputStream): Launch {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return Launch(false, "Allow installs from this app, return, then request installation again.")
        }
        val apk = withContext(Dispatchers.IO) {
            val target = File(context.filesDir, "install/${java.util.UUID.randomUUID()}.apk")
            target.parentFile!!.mkdirs()
            try {
                open().use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        total += count
                        check(total <= 512L * 1024 * 1024) { "APK is larger than 512 MB" }
                        output.write(buffer, 0, count)
                    }
                } }
                checkNotNull(context.packageManager.getPackageArchiveInfo(target.path,0)) { "Not a valid APK" }
                val free = android.os.StatFs(target.parentFile!!.path).availableBytes
                // Leave room for Android's staging/dex work and low-storage reserve.
                val required = target.length() + 768L * 1024 * 1024
                check(free >= required) { "Low storage: ${free / (1024*1024)} MB free. " +
                    "Free at least ${(required-free)/(1024*1024)+1} MB, then try installing again." }
                target
            } catch (error: Exception) { target.delete(); throw error }
        }
        val content = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(content, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return Launch(true, "Android's installer opened. Confirm there to install; installation and launch are not yet verified.")
    }
}

@Composable fun BuildScreen(build: BuildViewModel, probe: ProbeViewModel, notify: (String) -> Unit) {
    val state by build.state.collectAsStateWithLifecycle()
    val checks by probe.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) build.refreshTools() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.message) { state.message?.let { notify(it); build.dismissMessage() } }
    var tasks by remember(state.project?.id) { mutableStateOf(":app:assembleDebug") }
    fun install(open: () -> InputStream) = scope.launch {
        notify(try { ApkInstaller.install(context, open) } catch (error: Exception) { "Could not open this APK: ${friendly(error)}" })
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) install { context.contentResolver.openInputStream(uri) ?: error("Could not read the file") }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            notify(try {
                val report = probe.report()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(report.toByteArray()) } }
                "Compatibility report exported"
            } catch (_: Exception) { "Export failed. Choose another destination." })
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Build", style = MaterialTheme.typography.headlineSmall)
        SectionCard("On-phone build") {
            StatusChip(when { state.workerInstalled -> "Tools installed"; state.workerOutdated -> "Tools update needed"; else -> "Install tools" },
                if (state.workerInstalled) "CONNECTED" else "BLOCKED")
            Text("Gradle 8.13, Java 17 and Android SDK 36 run locally in a separate build app. " +
                "The Kotlin/Compose sample has built on an ARM64 emulator; this phone and other projects still need validation.")
            if (!state.workerInstalled) Button(onClick = { install { context.assets.open("build-worker.apk") } }) {
                Text(if (state.workerOutdated) "Update build tools" else "Install build tools")
            }
            if (state.workerOutdated) Text("This version needs newer build tools. Updating keeps your projects; previous build outputs in the tools app are kept.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = tasks, onValueChange = { tasks = it }, label = { Text("Gradle tasks") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { build.prepare(tasks) }, enabled = state.workerInstalled && state.project != null &&
                state.report?.gradleProject == true && !state.preparing && state.records.none { it.status in setOf("RUNNING", "DISPATCHING", "CANCEL_REQUESTED") }) {
                Text(if (state.preparing) "Copying project…" else "Review build")
            }
            Text("Copies source before asking for approval. The original project is not mounted in the build app. " +
                "Dependencies require internet; each build has a fresh cache and keeps its output in build storage.", style = MaterialTheme.typography.bodySmall)
        }
        val project = state.project
        val report = state.report
        SectionCard(project?.let { "Project: ${it.name}" } ?: "Project") {
            when {
                project == null -> Text("Open a project to inspect it.")
                report == null -> Text(if (state.checking) "Inspecting…" else "Not inspected")
                else -> {
                    Text("Gradle project: ${if (report.gradleProject) "yes" else "no"} · wrapper: ${if (report.wrapper) "yes" else "no"} · " +
                        "Android app module: ${if (report.androidApp) "yes" else "no"}")
                    if (report.apks.isEmpty()) Text("No APK files in this project.", style = MaterialTheme.typography.bodySmall)
                    else {
                        Text("APK files found in the project:", style = MaterialTheme.typography.labelLarge)
                        report.apks.forEach { path ->
                            OutlinedButton(onClick = { build.apkFile(path)?.let { file -> install { file.inputStream() } } }) {
                                Text("Install $path", fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    OutlinedButton(onClick = build::inspect) { Text("Inspect again") }
                }
            }
        }
        state.records.take(10).forEach { record ->
            SectionCard("Build ${record.id.take(8)}") {
                StatusChip(record.status.replace('_', ' ').lowercase(), record.status)
                Text(record.tasks, fontFamily = FontFamily.Monospace)
                Text(record.detail, style = MaterialTheme.typography.bodySmall)
                if (record.durationMs > 0) Text("Elapsed: ${record.durationMs / 1000}s", style = MaterialTheme.typography.bodySmall)
                if (record.status == "AWAITING_APPROVAL") {
                    if (record.agentTaskId == null) OutlinedButton(onClick = { build.review(record) }) { Text("Review command") }
                    else Text("Answer this build's approval in Agent.", style = MaterialTheme.typography.bodySmall)
                }
                if (record.status in setOf("RUNNING", "DISPATCHING", "CANCEL_REQUESTED")) {
                    OutlinedButton(onClick = { build.cancel(record.id) }, enabled = record.status != "CANCEL_REQUESTED") { Text("Stop build") }
                }
                if (record.status != "AWAITING_APPROVAL" && record.status != "DECLINED")
                    OutlinedButton(onClick = { build.refresh(record.id) }) { Text("Refresh result") }
                val apks by produceState<List<File>>(emptyList(), record.id, record.artifactState) {
                    value = if (record.status == "COMPLETED" && record.artifactState == "READY") withContext(Dispatchers.IO) { build.artifacts(record.id) } else emptyList()
                }
                apks.forEach { apk ->
                    OutlinedButton(onClick = { install {
                        check(build.artifacts(record.id).any { it == apk }) { "Recorded APK changed; refresh the build result" }
                        apk.inputStream()
                    } }) { Text("Install ${apk.name}") }
                    OutlinedButton(onClick = {
                        val packageName = context.packageManager.getPackageArchiveInfo(apk.path, 0)?.packageName
                        val intent = packageName?.let { context.packageManager.getLaunchIntentForPackage(it) }
                        if (intent == null) notify("Install the APK first, then launch it from your app launcher.")
                        else context.startActivity(intent)
                    }) { Text("Open installed app") }
                }
                val output by produceState(state.output[record.id].orEmpty(), record.id, record.status, state.output[record.id]) {
                    value = state.output[record.id].takeIf { !it.isNullOrBlank() }
                        ?: withContext(Dispatchers.IO) { context.container.builds.log(record.id) }
                }
                var show by remember(record.id) { mutableStateOf(false) }
                if (output.isNotBlank()) {
                    TextButton(onClick = { show = !show }) { Text(if (show) "Hide output" else "Show output") }
                    if (show) Text(output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        SectionCard("Install an APK") {
            Text("Pick any APK (for example one built on a computer) and hand it to Android's installer.")
            OutlinedButton(onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }) {
                Text("Select APK from storage")
            }
        }
        SectionCard("Device diagnostics") {
            Text(probe.deviceSummary, style = MaterialTheme.typography.bodySmall)
            Text("Runs the Android/Bionic test executable packaged in this APK. It proves native execution and cancellation, not a compiler.")
            Button(onClick = { probe.requestCommand("version") }, enabled = checks.ready && !checks.running) { Text("Check executable") }
            OutlinedButton(onClick = { probe.requestCommand("exit-7") }, enabled = checks.ready && !checks.running) { Text("Check nonzero exit status") }
            OutlinedButton(onClick = probe::cancellationProbe, enabled = checks.ready && !checks.running) { Text("Check process cancellation") }
            OutlinedButton(onClick = probe::fileProbe, enabled = checks.ready && !checks.running) { Text("Check file edit and rollback") }
            OutlinedButton(onClick = { exporter.launch("antigravity-mobile-compatibility.json") }, enabled = checks.ready && !checks.running) {
                Text("Export compatibility report")
            }
            if (checks.running) BusyRow("Running: ${checks.active}", onCancel = probe::cancel)
            if (checks.output.isNotBlank()) Text(checks.output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("Check history") {
            if (checks.records.isEmpty()) Text("No checks yet")
            checks.records.take(20).forEach { record ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(record.status.lowercase(), record.status)
                    Text(record.name, style = MaterialTheme.typography.labelLarge)
                }
                Text(record.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    state.approval?.let { record ->
        AlertDialog(onDismissRequest = build::decline, title = { Text("Approve build command") },
            text = { Text("Run Gradle ${record.tasks} on the prepared copy of ${state.project?.name}?\n\n" +
                "Snapshot SHA-256: ${record.snapshotHash}\n\n" +
                "Project build scripts can execute code, download dependencies and access the build app’s storage and internet. " +
                "Anti Gravity account storage stays in the main app. This approval runs once; interrupted commands are never replayed.") },
            confirmButton = { TextButton(onClick = build::approve) { Text("Approve and build") } },
            dismissButton = { TextButton(onClick = build::decline) { Text("Cancel") } })
    }
    checks.approval?.let { argument ->
        AlertDialog(onDismissRequest = probe::declineCommand, title = { Text("Approve command") },
            text = { Text("Run the packaged execution-probe with \"$argument\" in its private workspace?") },
            confirmButton = { TextButton(onClick = probe::approveCommand) { Text("Run") } },
            dismissButton = { TextButton(onClick = probe::declineCommand) { Text("Cancel") } })
    }
}
