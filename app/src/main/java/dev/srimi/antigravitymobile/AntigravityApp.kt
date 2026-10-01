package dev.srimi.antigravitymobile

import android.app.Application
import android.content.Context
import androidx.room.Room
import dev.srimi.antigravitymobile.runtime.NativeAgentTaskRunner
import dev.srimi.antigravitymobile.runtime.RoomProviderUsageStore
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
    private val taskFactory: (AppContainer, Context) -> NativeAgentTaskRunner = { services, app -> NativeAgentTaskRunner(services, app) }) {
    private val app = context.applicationContext
    val database: SessionStore = databaseOverride ?: Room.databaseBuilder(app, SessionStore::class.java, "probe.db")
        .addMigrations(SessionStore.MIGRATION_1_2, SessionStore.MIGRATION_2_3, SessionStore.MIGRATION_3_4).build()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val prefs = app.getSharedPreferences("app", Context.MODE_PRIVATE)
    val projects = ProjectRepository(database.projects(), File(app.filesDir, "projects"))
    val changes = ChangeService(database.changes(), File(app.filesDir, "changesets"))
    val git = GitService(File(app.noBackupFilesDir, "git-home"))
    val chatgpt = ChatGptProbeAdapter(app)
    val gemini = GeminiAdapter(app)
    val claude = ClaudeAdapter(app)
    /** Which connected provider the Agent uses. Each provider is used only when the user picked it. */
    var agentProvider: ProviderId
        get() = runCatching { ProviderId.valueOf(prefs.getString("agentProvider", ProviderId.CHATGPT.name)!!) }.getOrDefault(ProviderId.CHATGPT)
        set(value) { prefs.edit().putString("agentProvider", value.name).apply() }
    fun agentModel(provider: ProviderId = agentProvider): AgentModel = when (provider) {
        ProviderId.GEMINI -> gemini; ProviderId.CLAUDE_KEY -> claude; else -> chatgpt }
    fun agentAccount(provider: ProviderId = agentProvider): AccountState = when (provider) {
        ProviderId.GEMINI -> gemini.accountState(); ProviderId.CLAUDE_KEY -> claude.accountState(); else -> chatgpt.accountState() }
    val builds = BuildCoordinator(app, database.builds(), projects, scope, database.runtime())
    val tasks by lazy { taskFactory(this, app) }
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

    fun accountStates(): List<AccountState> = listOf(chatgpt.accountState(), gemini.accountState(), claude.accountState(), ProviderPolicy.claude, ProviderPolicy.google)

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
