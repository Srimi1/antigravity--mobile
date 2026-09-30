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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

object ApkInstaller {
    /** Copies an APK into the FileProvider-shared install folder and opens Android's installer. */
    suspend fun install(context: Context, open: () -> InputStream): String {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Allow installs from this app, return, then try again."
        }
        val apk = withContext(Dispatchers.IO) {
            File(context.filesDir, "install/selected.apk").apply {
                parentFile!!.mkdirs()
                open().use { input -> outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    var count = input.read(buffer)
                    while (count != -1) {
                        total += count
                        check(total <= 256L * 1024 * 1024) { "APK is larger than 256 MB" }
                        output.write(buffer, 0, count)
                        count = input.read(buffer)
                    }
                } }
            }
        }
        checkNotNull(context.packageManager.getPackageArchiveInfo(apk.path, 0)) { "Not a valid APK" }
        val content = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(content, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Android's installer opened. Confirm there to install."
    }
}

@Composable fun BuildScreen(build: BuildViewModel, probe: ProbeViewModel, notify: (String) -> Unit) {
    val state by build.state.collectAsStateWithLifecycle()
    val checks by probe.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
            StatusChip("blocked", "BLOCKED")
            Text(BuildInspector.BLOCKED_REASON)
            Button(onClick = {}, enabled = false) { Text("Build debug APK") }
            Text("This button stays disabled until a real Android-host toolchain is packaged and validated. " +
                "The app will not pretend a build ran.", style = MaterialTheme.typography.bodySmall)
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
    checks.approval?.let { argument ->
        AlertDialog(onDismissRequest = probe::declineCommand, title = { Text("Approve command") },
            text = { Text("Run the packaged execution-probe with \"$argument\" in its private workspace?") },
            confirmButton = { TextButton(onClick = probe::approveCommand) { Text("Run") } },
            dismissButton = { TextButton(onClick = probe::declineCommand) { Text("Cancel") } })
    }
}
