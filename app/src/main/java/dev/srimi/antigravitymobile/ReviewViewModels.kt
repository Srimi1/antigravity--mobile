package dev.srimi.antigravitymobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class FileReview(val path: String, val kind: String, val diff: String, val added: Int, val removed: Int)
data class ChangesState(
    val project: ProjectRecord? = null,
    val sets: List<ChangeSetRecord> = emptyList(),
    val files: Map<String, List<FileReview>> = emptyMap(),
    val isRepo: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChangesViewModel(application: Application) : AndroidViewModel(application) {
    private val services = application.container
    private val mutable = MutableStateFlow(ChangesState())
    val state: StateFlow<ChangesState> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            services.selectedProjectId.flatMapLatest { id ->
                val project = id?.let { services.projects.find(it) }
                mutable.update { ChangesState(project = project) }
                if (project == null) flowOf(emptyList()) else services.database.changes().observeSets(project.id)
            }.collect { sets ->
                mutable.update { it.copy(sets = sets) }
                refreshRepo()
                // Load diffs for anything still needing a decision; finished sets load on demand.
                sets.filter { it.status == "REVIEW" || it.status == "ACCEPTED" }.forEach { load(it.id, force = true) }
            }
        }
    }
    private fun refreshRepo() {
        val project = state.value.project ?: return
        viewModelScope.launch {
            val repo = withContext(Dispatchers.IO) { services.git.isRepository(services.projects.directory(project)) }
            mutable.update { it.copy(isRepo = repo) }
        }
    }
    fun dismissMessage() = mutable.update { it.copy(message = null) }

    fun load(setId: String, force: Boolean = false) {
        if (!force && setId in state.value.files) return
        viewModelScope.launch {
            val reviews = withContext(Dispatchers.IO) {
                services.changes.diffs(setId).map { diff ->
                    val text = TextDiff.unified(diff.path, diff.before, diff.after)
                    val (added, removed) = TextDiff.stats(text)
                    FileReview(diff.path, diff.kind, text, added, removed)
                }
            }
            mutable.update { it.copy(files = it.files + (setId to reviews)) }
        }
    }

    private fun action(block: suspend () -> String) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val message = try { withContext(Dispatchers.IO) { block() } } catch (error: Exception) { friendly(error) }
            mutable.update { it.copy(busy = false, message = message) }
        }
    }
    fun accept(setId: String) = action { services.changes.accept(setId); "Kept. Commit accepted changes when ready." }
    fun revert(setId: String) = action {
        val project = state.value.project ?: error("Select a project")
        services.changes.revert(setId, services.workspace(project))
        "Reverted"
    }
    fun commitAccepted(message: String) = action {
        val project = state.value.project ?: error("Select a project")
        val dir = services.projects.directory(project)
        check(services.git.isRepository(dir)) { "Initialize Git for this project in Projects first" }
        val accepted = state.value.sets.filter { it.status == "ACCEPTED" }
        check(accepted.isNotEmpty()) { "Accept a change set first" }
        val paths = accepted.flatMap { services.changes.diffs(it.id).map(ChangeService.FileDiff::path) }.distinct()
        val commit = services.git.commit(dir, message, services.author(), paths)
        services.changes.markCommitted(accepted.map { it.id }, commit)
        "Committed ${paths.size} file(s) as ${commit.take(10)}"
    }
}

data class AccountsState(
    val accounts: List<AccountState> = emptyList(),
    val models: List<String> = emptyList(),
    val preferredModel: String? = null,
    val gitUser: String = "",
    val hasGitToken: Boolean = false,
    val authorName: String = "",
    val authorEmail: String = "",
    val busy: String? = null,
    val output: String = "",
    val message: String? = null,
)

class AccountsViewModel(application: Application) : AndroidViewModel(application) {
    private val services = application.container
    private val mutable = MutableStateFlow(AccountsState())
    val state: StateFlow<AccountsState> = mutable.asStateFlow()
    private var job: Job? = null

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val credentials = services.gitCredentials
                state.value.copy(accounts = services.accountStates(), preferredModel = services.chatgpt.preferredModel,
                    gitUser = credentials?.username.orEmpty(), hasGitToken = credentials?.token?.isNotEmpty() == true,
                    authorName = services.authorName, authorEmail = services.authorEmail)
            }
            mutable.update { snapshot.copy(busy = it.busy, output = it.output, message = it.message, models = it.models) }
        }
    }
    fun dismissMessage() = mutable.update { it.copy(message = null) }

    private fun run(label: String, block: suspend () -> String) {
        if (state.value.busy != null) return
        mutable.update { it.copy(busy = label, message = null) }
        job = viewModelScope.launch {
            val message = try { block() } catch (_: CancellationException) { "$label stopped" } catch (error: Exception) { "$label failed: ${friendly(error)}" }
            mutable.update { it.copy(busy = null, message = message) }
            refresh()
        }
    }
    fun cancel() { services.chatgpt.cancel(); job?.cancel() }

    fun connectChatGpt(openBrowser: (String) -> Unit) = run("ChatGPT sign-in") {
        services.chatgpt.authenticate(openBrowser)
        "Signed in. Signature, nonce, PKCE and plan scope were validated. Send a test request to verify plan access."
    }
    fun verifyChatGpt() = run("Test request") {
        mutable.update { it.copy(output = "") }
        var completed = false
        services.chatgpt.streamTurn("Reply with the single word: ready").collect { event ->
            when (event) {
                is ProviderEvent.Text -> mutable.update { it.copy(output = (it.output + event.delta).takeLast(2000)) }
                ProviderEvent.Completed -> completed = true
                is ProviderEvent.Item -> Unit
            }
        }
        check(completed) { "The response did not complete" }
        "A subscription response completed. No API key was used."
    }
    fun renew() = run("Renewal") { services.chatgpt.renewCredentials(); "Credentials renewed" }
    fun disconnect() = run("Disconnect") {
        if (services.chatgpt.disconnect()) "Disconnected and remote revocation confirmed"
        else "Local credentials removed. Remote revocation was not confirmed; also disconnect the app in ChatGPT settings."
    }
    fun loadModels() = run("Load models") {
        val models = services.chatgpt.listModels()
        mutable.update { it.copy(models = models) }
        if (models.isEmpty()) "No models are listed for this account" else "${models.size} model(s) available"
    }
    fun chooseModel(model: String?) { services.chatgpt.preferredModel = model; refresh() }

    fun saveGitToken(user: String, token: String) = run("Save Git token") {
        require(token.isNotBlank()) { "Enter a token" }
        withContext(Dispatchers.IO) { services.gitCredentials = GitCredentials(user.trim(), token.trim()) }
        "Token saved in Keystore-encrypted storage"
    }
    fun clearGitToken() = run("Remove Git token") {
        withContext(Dispatchers.IO) { services.gitCredentials = null }
        "Token removed from this phone"
    }
    fun saveAuthor(name: String, email: String) = run("Save author") {
        require(name.isNotBlank() && email.contains('@')) { "Enter a name and a valid email" }
        services.authorName = name; services.authorEmail = email
        "Commit author saved"
    }
}

data class BuildState(
    val report: BuildInspector.Report? = null,
    val project: ProjectRecord? = null,
    val checking: Boolean = false,
    val preparing: Boolean = false,
    val workerInstalled: Boolean = false,
    val workerOutdated: Boolean = false,
    val records: List<BuildRecord> = emptyList(),
    val output: Map<String,String> = emptyMap(),
    val approval: BuildRecord? = null,
    val message: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class BuildViewModel(application: Application) : AndroidViewModel(application) {
    private val services = application.container
    private val mutable = MutableStateFlow(BuildState())
    val state: StateFlow<BuildState> = mutable.asStateFlow()
    init {
        viewModelScope.launch {
            try {
                services.ready.await()
                services.selectedProjectId.flatMapLatest { id ->
                    val project = id?.let { services.projects.find(it) }
                    mutable.update { BuildState(project = project, workerInstalled = services.builds.client.installed(),
                        workerOutdated = services.builds.client.outdated(), output = it.output) }
                    inspect()
                    if (project == null) flowOf(emptyList()) else services.database.builds().observe(project.id)
                }.collect { records -> mutable.update { it.copy(records = records) } }
            } catch (error: Exception) { mutable.update { it.copy(message = friendly(error)) } }
        }
        viewModelScope.launch { services.builds.output.collect { output -> mutable.update { it.copy(output = output) } } }
    }
    fun refreshTools() { mutable.update { it.copy(workerInstalled = services.builds.client.installed(), workerOutdated = services.builds.client.outdated()) } }
    fun dismissMessage() { mutable.update { it.copy(message = null) } }
    fun inspect() {
        val project = state.value.project ?: return
        mutable.update { it.copy(checking = true) }
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) { BuildInspector.inspect(services.projects.directory(project)) }
                mutable.update { if (it.project?.id == project.id) it.copy(report = report, checking = false) else it }
            } catch (error: Exception) { mutable.update { it.copy(checking = false, message = friendly(error)) } }
        }
    }
    fun prepare(tasks: String) {
        val project = state.value.project ?: return
        if (state.value.preparing) return
        mutable.update { it.copy(preparing = true) }
        viewModelScope.launch {
            try {
                val record = services.builds.prepare(project, tasks)
                if (state.value.project?.id == project.id) mutable.update { it.copy(preparing = false, approval = record) }
                else { services.builds.decline(record.id); mutable.update { it.copy(preparing = false) } }
            } catch (error: Exception) { mutable.update { it.copy(preparing = false, message = friendly(error)) } }
        }
    }
    fun review(record: BuildRecord) { if (record.status == "AWAITING_APPROVAL") mutable.update { it.copy(approval = record) } }
    fun approve() {
        val id = state.value.approval?.id ?: return
        mutable.update { it.copy(approval = null) }
        services.scope.launch {
            try { services.builds.approve(id) }
            catch (error: Exception) { mutable.update { it.copy(message = friendly(error)) } }
        }
    }
    fun decline() {
        val id = state.value.approval?.id ?: return
        mutable.update { it.copy(approval = null) }
        services.scope.launch { services.builds.decline(id) }
    }
    fun cancel(id: String) { services.scope.launch { services.builds.cancel(id) } }
    fun refresh(id: String) { services.builds.refresh(id) }
    fun artifacts(id: String) = services.builds.artifacts(id)
    fun apkFile(path: String): java.io.File? = state.value.project?.let {
        val file = java.io.File(services.projects.directory(it), path).canonicalFile
        file.takeIf { f -> f.path.startsWith(services.projects.directory(it).canonicalPath + java.io.File.separator) && f.isFile }
    }
}
