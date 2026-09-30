package dev.srimi.antigravitymobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val model: ProbeViewModel by viewModels()
    private var notice by mutableStateOf("")
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val report = model.report()
                withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(report.toByteArray()) } }
                notice = "Compatibility report exported."
            } catch (_: Exception) { notice = "Export failed. Choose another destination." }
        }
    }
    private val apkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    File(filesDir, "install/selected.apk").apply {
                        parentFile!!.mkdirs()
                        contentResolver.openInputStream(uri)!!.use { input -> outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            var count = input.read(buffer)
                            while (count != -1) {
                                total += count
                                check(total <= 128L * 1024 * 1024) { "APK too large" }
                                output.write(buffer, 0, count)
                                count = input.read(buffer)
                            }
                        } }
                    }
                }
                check(packageManager.getPackageArchiveInfo(apk.path, 0) != null)
                val content = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", apk)
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(content, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                notice = "Installer opened. Selecting an APK does not prove it was built on this phone."
            } catch (_: Exception) { notice = "Could not open this APK. Check the file and installation permission." }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFAFCEFF), background = Color(0xFF11151D))) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Antigravity Mobile", style = MaterialTheme.typography.headlineMedium)
                        Text("Personal validation probe · 0.1.2", style = MaterialTheme.typography.labelLarge)
                        Text("Full app: BLOCKED. This prototype tests local execution and ChatGPT access. Google, Claude and on-phone APK compilation have not passed.")
                        Text(model.deviceSummary, style = MaterialTheme.typography.bodySmall)
                        ProbeCard("Local workspace") {
                            Text("Create a small source fixture, edit it, inspect the change and restore it while preserving an unrelated file.")
                            Button(onClick = model::fileProbe, enabled = state.ready && !state.running) { Text("Run file and rollback check") }
                            OutlinedButton(onClick = model::generateSample, enabled = state.ready && !state.running) { Text("Generate Compose sample source") }
                        }
                        ProbeCard("Android ARM64 execution") {
                            Text("Runs an Android/Bionic executable packaged in this APK. These checks do not establish a complete development toolchain.")
                            Button(onClick = { model.requestCommand("version") }, enabled = state.ready && !state.running) { Text("Check executable") }
                            OutlinedButton(onClick = { model.requestCommand("exit-7") }, enabled = state.ready && !state.running) { Text("Check nonzero exit status") }
                            OutlinedButton(onClick = model::cancellationProbe, enabled = state.ready && !state.running) { Text("Check process cancellation") }
                        }
                        ProbeCard("Subscription validation") {
                            Text("Google: blocked · Claude: blocked", color = MaterialTheme.colorScheme.error)
                            Text("ChatGPT: experimental documented sign-in. Consent and a completed request must prove access; no separate API billing fallback.")
                            Button(onClick = { model.connectChatGpt { url ->
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } }, enabled = state.ready && !state.running) { Text("Continue with ChatGPT") }
                            OutlinedButton(onClick = model::inferenceProbe, enabled = state.ready && !state.running) { Text("Test subscription coding response") }
                            OutlinedButton(onClick = model::refreshProbe, enabled = state.ready && !state.running) { Text("Test token renewal") }
                            OutlinedButton(onClick = model::logoutProbe, enabled = state.ready && !state.running) { Text("Disconnect ChatGPT") }
                        }
                        ProbeCard("APK installation") {
                            Text("Build: blocked. A JDK, Gradle and Android-host build tools are not bundled. Installer access can be tested independently with an existing APK.")
                            OutlinedButton(onClick = {
                                if (packageManager.canRequestPackageInstalls()) apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                                else {
                                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                                    notice = "Enable installation for this probe, return, then select the APK again."
                                }
                            }, enabled = !state.running) { Text("Select APK for installer check") }
                        }
                        if (state.running) {
                            Text("Running: ${state.active}")
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Button(onClick = model::cancel) { Text("Stop current check") }
                        }
                        if (state.output.isNotBlank()) Text(state.output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { export.launch("antigravity-mobile-compatibility.json") }, enabled = state.ready && !state.running) { Text("Export compatibility report") }
                        if (notice.isNotEmpty()) Text(notice)
                        Text("Check history", style = MaterialTheme.typography.titleLarge)
                        state.records.take(20).forEach { record ->
                            Text("${record.status} · ${record.name}", style = MaterialTheme.typography.labelLarge)
                            Text(record.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                state.approval?.let { argument ->
                    AlertDialog(onDismissRequest = model::declineCommand,
                        title = { Text("Approve command") },
                        text = { Text("Run packaged execution-probe $argument in the private probe workspace?") },
                        confirmButton = { TextButton(onClick = model::approveCommand) { Text("Run") } },
                        dismissButton = { TextButton(onClick = model::declineCommand) { Text("Cancel") } })
                }
            }
        }
    }
}

@Composable private fun ProbeCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
