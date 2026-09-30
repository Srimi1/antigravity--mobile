package dev.srimi.antigravitymobile

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

data class ProbeUiState(
    val ready: Boolean = false,
    val running: Boolean = false,
    val active: String = "",
    val output: String = "",
    val records: List<CheckRecord> = emptyList(),
    val approval: String? = null,
)

class ProbeViewModel(application: Application) : AndroidViewModel(application) {
    private val services = application.container
    private val store = services.database.checks()
    private val root = File(application.filesDir, "workspaces/probe").apply { mkdirs() }
    private val workspace = WorkspaceService(root, File(application.filesDir, "checkpoints"))
    private val executor = NativeExecutionService(File(application.applicationInfo.nativeLibraryDir, "libexecution_probe.so"), root)
    private val chatgpt = services.chatgpt
    private val mutable = MutableStateFlow(ProbeUiState())
    val state: StateFlow<ProbeUiState> = mutable.asStateFlow()
    private var task: Job? = null

    val deviceSummary: String get() = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.joinToString()}"
    init {
        viewModelScope.launch {
            store.interruptUnfinished()
            mutable.update { it.copy(ready = true) }
            store.observe().collect { records -> mutable.update { it.copy(records = records) } }
        }
    }
    private fun run(name: String, block: suspend () -> String) {
        if (!state.value.ready || state.value.running) return
        mutable.update { it.copy(running = true, active = name, output = "", approval = null) }
        task = viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            val start = System.currentTimeMillis()
            try {
                store.save(CheckRecord(id, name, "RUNNING", "Action started", start))
                val detail = block()
                store.save(CheckRecord(id, name, "PASSED", detail, start, System.currentTimeMillis() - start))
            } catch (_: CancellationException) {
                withContext(NonCancellable) { store.save(CheckRecord(id, name, "CANCELLED", "Stopped by user; not replayed", start, System.currentTimeMillis() - start)) }
            } catch (error: Exception) {
                // OAuth URLs, tokens, provider bodies and user code are never persisted as error text.
                store.save(CheckRecord(id, name, "FAILED", "${error.javaClass.simpleName}: check failed. See compatibility instructions or retry.", start, System.currentTimeMillis() - start))
            } finally {
                mutable.update { it.copy(running = false, active = "") }
            }
        }
    }
    fun requestCommand(argument: String) { if (!state.value.running) mutable.update { it.copy(approval = argument) } }
    fun declineCommand() { mutable.update { it.copy(approval = null) } }
    fun approveCommand() {
        val argument = state.value.approval ?: return
        run("Native command: $argument") {
            val result = executor.execute(argument) { line -> mutable.update { it.copy(output = (it.output + line + "\n").takeLast(16_384)) } }
            val expected = if (argument == "exit-7") 7 else 0
            check(result.exitCode == expected)
            "Packaged ARM64/Bionic executable returned expected exit code ${result.exitCode} in ${result.durationMs} ms."
        }
    }
    fun cancellationProbe() = run("Native cancellation") {
        val ready = CompletableDeferred<Unit>()
        coroutineScope {
            val execution = async {
                executor.execute("wait") { line -> if (line == "ready") ready.complete(Unit) }
            }
            withTimeout(5000) { ready.await() }
            val start = System.currentTimeMillis()
            executor.cancel()
            val result = withTimeout(3000) { execution.await() }
            check(result.exitCode != 0)
            "Process stopped in ${System.currentTimeMillis() - start} ms; exit ${result.exitCode}. No child processes are spawned by this probe."
        }
    }
    fun fileProbe() = run("Workspace read, edit and rollback") {
        withContext(Dispatchers.IO) {
            val folder = "checks/${UUID.randomUUID()}"
            val path = "$folder/Hello.kt"
            val unrelated = "$folder/personal-note.txt"
            workspace.write(path, "fun hello() = \"before\"\n")
            workspace.write(unrelated, "keep my work\n")
            val before = workspace.checkpoint(listOf(path))
            workspace.write(path, "fun hello() = \"after\"\n")
            val changes = workspace.diff(before)
            check(changes.size == 1)
            workspace.restore(changes)
            check(workspace.read(path).contains("before") && workspace.read(unrelated) == "keep my work\n")
            "Local read/edit/diff/rollback passed. Unrelated file preserved. Fixture: $folder"
        }
    }
    fun generateSample() = run("Generate Compose sample source") {
        withContext(Dispatchers.IO) {
            val folder = "hello-phone-${System.currentTimeMillis()}"
            val destination = File(root, folder).apply { mkdirs() }
            ZipInputStream(getApplication<Application>().assets.open("hello-phone.zip")).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = File(destination, entry.name).canonicalFile
                    check(target.path.startsWith(destination.canonicalPath + File.separator))
                    if (!entry.isDirectory) {
                        target.parentFile!!.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                        if (entry.name == "gradlew") check(target.setExecutable(true, true))
                    }
                    entry = zip.nextEntry
                }
            }
            "Compose project written to $folder. Source generation passed; on-phone compilation remains BLOCKED because no JDK/Gradle/aapt2 toolchain is bundled."
        }
    }
    fun connectChatGpt(openBrowser: (String) -> Unit) = run("ChatGPT OAuth consent") {
        chatgpt.authenticate(openBrowser)
        "Signature, issuer, audience, expiry, nonce, PKCE callback and ChatGPT plan scope validated. Inference remains unverified."
    }
    fun inferenceProbe() = run("ChatGPT subscription inference") {
        var completed = false
        chatgpt.streamTurn("Write a Kotlin function named square that accepts an Int and returns its square. Return only the function.")
            .collect { event ->
                when (event) {
                    is ProviderEvent.Text -> mutable.update { it.copy(output = (it.output + event.delta).takeLast(16_384)) }
                    ProviderEvent.Completed -> completed = true
                    is ProviderEvent.Item -> Unit
                }
            }
        check(completed)
        "A coding response completed through the documented ChatGPT-plan OAuth route. No API-key fallback. Local agent tools were not exercised."
    }
    fun refreshProbe() = run("ChatGPT token renewal") { chatgpt.renewCredentials(); "Renewed credentials stored atomically in Keystore-encrypted storage." }
    fun logoutProbe() = run("ChatGPT local logout") {
        val revoked = chatgpt.disconnect()
        if (revoked) "Local credentials removed and remote revocation confirmed (or no renewable session existed)."
        else "Local credentials removed. Remote revocation UNVERIFIED; disconnect this app in ChatGPT Settings."
    }
    fun cancel() {
        executor.cancel()
        // The adapter is shared with the agent; only interrupt it for this screen's own ChatGPT checks.
        if (state.value.active.startsWith("ChatGPT")) chatgpt.cancel()
        task?.cancel()
    }
    suspend fun report(): String = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        val memory = ActivityManager.MemoryInfo().also { (context.getSystemService(Application.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        val result = JSONObject().put("schemaVersion", 1).put("stage", "FULL_APP_PREVIEW").put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            .put("generatedAt", java.time.Instant.now().toString()).put("fullProductGate", "BLOCKED")
            .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("android", Build.VERSION.RELEASE).put("api", Build.VERSION.SDK_INT)
                .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("totalRamBytes", memory.totalMem)
                .put("freeStorageBytes", StatFs(context.filesDir.path).availableBytes))
            .put("providers", JSONObject().put("google", "BLOCKED: no supported native subscription route established")
                .put("claude", "BLOCKED: applicable subscription integration/approval not established")
                .put("chatgpt", "UNVERIFIED until OAuth, completed inference, renewal and logout checks pass"))
            .put("chatgptAccount", chatgpt.accountState().status.name)
            .put("onPhoneBuild", "BLOCKED: no Android-host JDK/Gradle/aapt2 toolchain bundled")
            .put("checks", JSONArray(store.all().map { record -> JSONObject()
                .put("name", record.name).put("status", record.status).put("detail", record.detail)
                .put("startedAt", record.startedAt).put("durationMs", record.durationMs) }))
        result.toString(2)
    }
    // The shared database belongs to AppContainer and stays open for the process lifetime.
    override fun onCleared() { cancel() }
}
