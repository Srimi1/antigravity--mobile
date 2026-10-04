package dev.srimi.antigravitymobile

import android.app.Application
import android.content.Context
import androidx.room.Room
import dev.srimi.antigravitymobile.runtime.NativeAgentTaskRunner
import dev.srimi.antigravitymobile.runtime.AgentTaskRouter
import dev.srimi.antigravitymobile.runtime.AgentBackend
import dev.srimi.antigravitymobile.bridge.BridgePairingStore
import dev.srimi.antigravitymobile.bridge.CliAgentTaskRunner
import dev.srimi.antigravitymobile.bridge.CliCapabilityGate
import dev.srimi.antigravitymobile.bridge.PairedCliBridge
import dev.srimi.antigravitymobile.bridge.TermuxBridgeLauncher
import dev.srimi.antigravitymobile.linux.AndroidTermuxGateway
import dev.srimi.antigravitymobile.linux.CliTool
import dev.srimi.antigravitymobile.linux.TermuxLinuxRuntime
import dev.srimi.antigravitymobile.runtime.RoomProviderUsageStore
import dev.srimi.antigravitymobile.providers.CompatProviders
import dev.srimi.antigravitymobile.providers.ProviderStores
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eclipse.jgit.lib.PersonIdent
import org.json.JSONObject
import java.io.File

open class AntigravityApp : Application() {
    open val container: AppContainer by lazy { AppContainer(this) }
}

val Context.container: AppContainer get() = (applicationContext as AntigravityApp).container

/** Process-wide services. One agent task runs at a time; unfinished work is marked interrupted, never replayed. */
class AppContainer(context: Context, databaseOverride: SessionStore? = null,
    private val cliFactory: (AppContainer, Context) -> CliAgentTaskRunner = { services, app -> CliAgentTaskRunner(services, app,
        PairedCliBridge({ services.cliLauncher.connect() }, { services.cliGate.unavailable(it) })) },
    private val taskFactory: (AppContainer, Context) -> NativeAgentTaskRunner = { services, app -> NativeAgentTaskRunner(services, app) }) {
    private val app = context.applicationContext
    val database: SessionStore = databaseOverride ?: Room.databaseBuilder(app, SessionStore::class.java, "probe.db")
        .addMigrations(SessionStore.MIGRATION_1_2, SessionStore.MIGRATION_2_3, SessionStore.MIGRATION_3_4).build()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val prefs = app.getSharedPreferences("app", Context.MODE_PRIVATE)
    val projects = ProjectRepository(database.projects(), File(app.filesDir, "projects"))
    val changes = ChangeService(database.changes(), File(app.filesDir, "changesets"),
        workspaceFor = { id -> projects.find(id)?.let { workspace(it) } })
    val git = GitService(File(app.noBackupFilesDir, "git-home"))
    val chatgpt = ChatGptProbeAdapter(app)
    val gemini = GeminiAdapter(app)
    val claude = ClaudeAdapter(app)
    val compat = CompatProviders.shared(app)
    /** Which connected provider the Agent uses. Each provider is used only when the user picked it. */
    var agentProvider: ProviderId
        get() = runCatching { ProviderId.valueOf(prefs.getString("agentProvider", ProviderId.CHATGPT.name)!!) }.getOrDefault(ProviderId.CHATGPT)
        set(value) { prefs.edit().putString("agentProvider", value.name).apply() }
    fun agentModel(provider: ProviderId = agentProvider): AgentModel = when (provider) {
        ProviderId.CHATGPT -> chatgpt; ProviderId.GEMINI -> gemini; ProviderId.CLAUDE_KEY -> claude
        ProviderId.OPENAI_COMPAT -> compat
        ProviderId.CLAUDE, ProviderId.GOOGLE -> error("Selected subscription has no supported Agent route") }
    fun agentAccount(provider: ProviderId = agentProvider): AccountState = when (provider) {
        ProviderId.CHATGPT -> chatgpt.accountState(); ProviderId.GEMINI -> gemini.accountState(); ProviderId.CLAUDE_KEY -> claude.accountState()
        ProviderId.OPENAI_COMPAT -> compat.accountState(); ProviderId.CLAUDE -> ProviderPolicy.claude; ProviderId.GOOGLE -> ProviderPolicy.google }
    /**
     * Non-secret route snapshot captured when a task starts. A running or paused task cannot silently switch
     * its provider's model (or a compatible provider's endpoint) between turns or on retry.
     */
    fun agentSelection(provider: String): String? {
        val model = when (provider) {
            ProviderId.CHATGPT.name -> chatgpt.preferredModel
            ProviderId.GEMINI.name -> gemini.preferredModel
            ProviderId.CLAUDE_KEY.name -> claude.preferredModel
            ProviderId.OPENAI_COMPAT.name -> null
            else -> return null
        }
        if (provider != ProviderId.OPENAI_COMPAT.name) return JSONObject().put("model", model ?: JSONObject.NULL).toString()
        val id = compat.selected
        return JSONObject().put("id", id ?: JSONObject.NULL).put("model", id?.let { compat.model(it) } ?: JSONObject.NULL)
            .put("freeOnly", compat.freeOnly).put("planConfirmed", id?.let { compat.planConfirmed(it) } ?: false)
            .put("baseUrl", id?.let { compat.entry(it)?.descriptor?.baseUrl } ?: JSONObject.NULL).toString()
    }
    val builds = BuildCoordinator(app, database.builds(), projects, scope, database.runtime())
    var agentBackend: AgentBackend
        get() = runCatching { AgentBackend.valueOf(prefs.getString("agentBackend", AgentBackend.Native.name)!!) }.getOrDefault(AgentBackend.Native)
        set(value) { prefs.edit().putString("agentBackend", value.name).apply() }
    private val bridgePairing by lazy { BridgePairingStore(CredentialStore(app, "cli-bridge.pairing")) }
    /** Paired helper inside the user's Termux/Debian. Each CLI backend still needs its own on-device probe to open. */
    val cliLauncher by lazy {
        TermuxBridgeLauncher(AndroidTermuxGateway(app), { bridgePairing.current() }, {
            TermuxLinuxRuntime(app).installedClis().mapNotNull { install ->
                val id = when (install.tool) { CliTool.CODEX -> "codex"; CliTool.ANTIGRAVITY -> "antigravity"; else -> null }
                install.binaryPath?.let { path -> id?.let { it to path } }
            }.toMap()
        })
    }
    val cliGate by lazy { CliCapabilityGate(app.getSharedPreferences("cli-capability", Context.MODE_PRIVATE), cliLauncher) }
    val tasks by lazy { AgentTaskRouter(database.runtime(), taskFactory(this, app), cliFactory(this, app), scope) }
    val providerUsage = RoomProviderUsageStore(database.providerUsage())
    val websites = WebsiteService(File(app.filesDir, "website-copies"))
    private val checkpointRoot = File(app.filesDir, "checkpoints")
    private val gitCredentialStore = CredentialStore(app, "git.credentials")

    private val selected = MutableStateFlow(prefs.getString("selectedProject", null))
    val selectedProjectId: StateFlow<String?> = selected.asStateFlow()
    fun selectProject(id: String?) {
        selected.value = id
        prefs.edit().apply { if (id == null) remove("selectedProject") else putString("selectedProject", id) }.apply()
    }

    /** Completes after startup recovery; callers that read task state should await it. */
    val ready = CompletableDeferred<Unit>()
    init {
        if (databaseOverride == null) ProviderStores.usage = providerUsage
        scope.launch {
            try {
                database.checks().interruptUnfinished()
                val conversations = database.conversations()
                conversations.running().filter { database.runtime().forConversation(it.id).isEmpty() }.forEach { conversation ->
                    conversations.saveMessage(MessageRecord(java.util.UUID.randomUUID().toString(), conversation.id, "notice",
                        "The app stopped while this task was running. It was not resumed; review Changes and send a new message to continue.",
                        System.currentTimeMillis()))
                }
                conversations.interruptRunning()
                conversations.interruptActions()
                changes.recoverInterrupted()
                websites.recover()
                builds.recover()
                tasks.recover()
                ready.complete(Unit)
            } catch (error: Exception) { ready.completeExceptionally(error) }
        }
    }

    fun workspace(project: ProjectRecord) = WorkspaceService(projects.directory(project), checkpointRoot)

    fun accountStates(): List<AccountState> = listOf(chatgpt.accountState(), gemini.accountState(), claude.accountState(), compat.accountState(), ProviderPolicy.claude, ProviderPolicy.google)

    var gitCredentials: GitCredentials?
        get() = try { gitCredentialStore.read()?.let { GitCredentials(it.optString("username"), it.getString("token")) } } catch (_: Exception) { null }
        set(value) {
            if (value == null) gitCredentialStore.save(JSONObject())
            else gitCredentialStore.save(JSONObject().put("username", value.username).put("token", value.token))
        }
    val hasGitCredentials: Boolean get() = gitCredentials?.token?.isNotEmpty() == true
    val github = GitHubService { gitCredentials?.token }

    var authorName: String
        get() = prefs.getString("authorName", "").orEmpty()
        set(value) { prefs.edit().putString("authorName", value.trim()).apply() }
    var authorEmail: String
        get() = prefs.getString("authorEmail", "").orEmpty()
        set(value) { prefs.edit().putString("authorEmail", value.trim()).apply() }
    fun author(): PersonIdent {
        require(authorName.isNotBlank() && authorEmail.isNotBlank()) { "Set your commit name and email in Accounts first" }
        return PersonIdent(authorName, authorEmail)
    }
}
