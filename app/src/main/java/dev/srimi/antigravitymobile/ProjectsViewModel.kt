package dev.srimi.antigravitymobile

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import dev.srimi.antigravitymobile.runtime.BuildProtocol as P
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

data class EditorState(val path: String, val original: String, val text: String, val readOnly: Boolean, val note: String? = null) {
    val dirty: Boolean get() = !readOnly && text != original
}
data class GitPanel(val isRepo: Boolean, val status: GitStatus? = null, val log: List<GitCommitInfo> = emptyList(), val remote: String? = null,
                    val branches: List<String> = emptyList()) {
    val gitHubRepo: String? get() = GitHubWire.fullName(remote)
}
data class ProjectsState(
    val projects: List<ProjectRecord> = emptyList(),
    val selected: ProjectRecord? = null,
    val directory: String = "",
    val entries: List<WorkspaceService.Entry> = emptyList(),
    val editor: EditorState? = null,
    val git: GitPanel? = null,
    val busy: String? = null,
    val progress: String = "",
    val message: String? = null,
    val websiteRoot: String = "",
    val websiteEntry: String = "index.html",
    val websiteApproval: WebsiteCopy? = null,
    val gitHubRepos: List<GitHubRepo> = emptyList(),
    val gitHubLoading: Boolean = false,
    val lastPullRequest: String? = null,
)

class ProjectsViewModel(application: Application) : AndroidViewModel(application) {
    private companion object { const val MAX_EDITABLE = 400_000L }
    private val services = application.container
    private val mutable = MutableStateFlow(ProjectsState())
    val state: StateFlow<ProjectsState> = mutable.asStateFlow()
    private var job: Job? = null
    @Volatile private var cancelRequested = false

    init {
        viewModelScope.launch { services.projects.observe().collect { list -> mutable.update { it.copy(projects = list) } } }
        viewModelScope.launch {
            services.selectedProjectId.collect { id ->
                val project = id?.let { services.projects.find(it) }
                if (id != null && project == null) services.selectProject(null)
                mutable.update { it.copy(selected = project, directory = "", editor = null, git = null,
                    websiteRoot = services.prefs.getString("webRoot.$id", "").orEmpty(),
                    websiteEntry = services.prefs.getString("webEntry.$id", "index.html") ?: "index.html", websiteApproval = null) }
                if (project != null) { refreshFiles(); refreshGit() }
            }
        }
    }

    private fun workspace(): WorkspaceService? = state.value.selected?.let(services::workspace)
    private fun dir(): File? = state.value.selected?.let(services.projects::directory)
    fun dismissMessage() = mutable.update { it.copy(message = null) }

    /** Runs one operation at a time; failures become a readable message instead of a crash. */
    private fun operation(label: String, block: suspend () -> String?) {
        if (state.value.busy != null) return
        cancelRequested = false
        mutable.update { it.copy(busy = label, progress = "", message = null) }
        job = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { block() }
                mutable.update { it.copy(message = result) }
            } catch (_: CancellationException) {
                mutable.update { it.copy(message = "$label stopped") }
            } catch (error: Exception) {
                mutable.update { it.copy(message = "$label failed: ${friendly(error)}") }
            } finally {
                mutable.update { it.copy(busy = null, progress = "") }
            }
        }
    }
    fun cancel() { cancelRequested = true; job?.cancel() }
    private fun progress(text: String) = mutable.update { it.copy(progress = text) }

    fun create(name: String, initGit: Boolean) = operation("Create project") {
        val project = services.projects.create(name) { dir ->
            File(dir, "README.md").writeText("# ${name.trim()}\n")
            if (initGit) services.git.init(dir)
        }
        open(project); "Created ${project.name}"
    }
    fun createFromTemplate(name: String) = operation("Create Compose app") {
        val project = services.projects.create(name) { dir ->
            getApplication<Application>().assets.open("hello-phone.zip").use { Archives.extract(it, dir) }
            services.git.init(dir)
        }
        open(project)
        "Created ${project.name} from the Compose template. Open Build to review a local build with the installed tools."
    }
    fun createWebsite(name: String) = operation("Create website") {
        val project = services.projects.create(name) { dir ->
            getApplication<Application>().assets.open("hello-web.zip").use { Archives.extract(it, dir) }
            services.git.init(dir)
        }
        open(project)
        "Created ${project.name}. Open the Website tab to preview or export saved files."
    }

    fun websiteRoot(value: String) {
        mutable.update { it.copy(websiteRoot = value) }
        state.value.selected?.let { services.prefs.edit().putString("webRoot.${it.id}", value).apply() }
    }
    fun websiteEntry(value: String) {
        mutable.update { it.copy(websiteEntry = value) }
        state.value.selected?.let { services.prefs.edit().putString("webEntry.${it.id}", value).apply() }
    }
    fun previewHtml(path: String) {
        websiteRoot(path.substringBeforeLast('/', "")); websiteEntry(path.substringAfterLast('/')); prepareWebsite()
    }
    fun prepareWebsite() {
        val current = state.value
        val project = current.selected ?: return
        operation("Prepare website preview") {
            services.ready.await()
            check(current.editor?.dirty != true) { "Save or discard the editor changes before previewing" }
            check(services.builds.client.installed()) { "Install or update the matching tools on the Build tab first" }
            val copy = runInterruptible { services.websites.prepare(project.id, services.projects.directory(project),
                current.websiteRoot.trim(), current.websiteEntry.trim()) }
            mutable.update { it.copy(websiteApproval = copy) }
            null
        }
    }
    fun declineWebsite() {
        val copy = state.value.websiteApproval ?: return
        if (state.value.busy != null) return
        mutable.update { it.copy(websiteApproval = null) }
        operation("Decline preview") { services.websites.decline(copy.id); "Preview was not opened" }
    }
    fun approveWebsite() {
        val copy = state.value.websiteApproval ?: return
        if (state.value.busy != null) return
        mutable.update { it.copy(websiteApproval = null) }
        operation("Open website preview") {
            val approved = services.websites.claim(copy.id)
            try {
                services.builds.client.preparePreview(approved.id, services.websites.archive(approved.id), approved.hash, approved.entry)
                services.websites.finish(approved.id, "READY")
                withContext(Dispatchers.Main) {
                    getApplication<Application>().startActivity(Intent().setComponent(ComponentName(P.WORKER, P.PREVIEW_ACTIVITY))
                        .putExtra("id", approved.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
                "Opened the approved website copy. Reload uses that copy; saved edits need a new preview."
            } finally {
                withContext(NonCancellable) {
                    if (services.websites.find(approved.id).status == "DISPATCHING") services.websites.finish(approved.id, "INTERRUPTED")
                }
            }
        }
    }
    fun exportWebsite(target: Uri) {
        val current = state.value
        val project = current.selected ?: return
        operation("Export website") {
            services.ready.await()
            check(current.editor?.dirty != true) { "Save or discard the editor changes before exporting" }
            val copy = runInterruptible { services.websites.prepare(project.id, services.projects.directory(project),
                current.websiteRoot.trim(), current.websiteEntry.trim()) }
            services.websites.claim(copy.id)
            try {
                getApplication<Application>().contentResolver.openOutputStream(target, "wt")!!.use { output ->
                    services.websites.archive(copy.id).inputStream().use { it.copyTo(output) }
                }
                services.websites.finish(copy.id, "EXPORTED")
                "Exported ${copy.files.size} website files. The selected HTML entry is ${copy.entry}."
            } finally {
                if (services.websites.find(copy.id).status == "DISPATCHING") services.websites.finish(copy.id, "INTERRUPTED")
            }
        }
    }
    fun clone(url: String, name: String) = operation("Clone") {
        val clean = url.trim()
        val project = services.projects.create(name.ifBlank { clean.substringAfterLast('/').removeSuffix(".git") }) { dir ->
            services.git.clone(clean, dir, services.gitCredentials?.takeIf { it.token.isNotEmpty() }, { cancelRequested }, ::progress)
        }
        open(project); "Cloned into ${project.name}"
    }
    fun importFolder(tree: Uri) = operation("Import") {
        val resolver = getApplication<Application>().contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val name = resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Imported project"
        var total = 0L
        var count = 0
        val project = services.projects.create(name) { dest ->
            fun walk(documentId: String, directory: File) {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
                resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        check(!cancelRequested) { "Import stopped" }
                        val id = cursor.getString(0)
                        val child = cursor.getString(1)
                        if (child.isNullOrBlank() || child == "." || child == ".." || '/' in child) continue
                        val target = File(directory, child)
                        if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) { target.mkdirs(); walk(id, target) }
                        else {
                            check(++count <= Archives.MAX_ENTRIES) { "Folder has too many files" }
                            resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))?.use { input ->
                                target.outputStream().use { output ->
                                    val buffer = ByteArray(16 * 1024)
                                    var read = input.read(buffer)
                                    while (read >= 0) {
                                        total += read
                                        check(total <= Archives.MAX_TOTAL_BYTES) { "Folder is larger than 512 MB" }
                                        output.write(buffer, 0, read)
                                        read = input.read(buffer)
                                    }
                                }
                            }
                            if (child == "gradlew") target.setExecutable(true, true)
                            if (count % 50 == 0) progress("$count files copied")
                        }
                    }
                }
            }
            walk(rootId, dest)
        }
        open(project); "Imported $count files into ${project.name}. The original folder was not changed."
    }
    fun export(target: Uri, includeGit: Boolean) {
        val project = state.value.selected ?: return
        operation("Export") {
            getApplication<Application>().contentResolver.openOutputStream(target, "wt")!!.use {
                Archives.write(services.projects.directory(project), it, includeGit)
            }
            "Exported ${project.name}"
        }
    }
    fun delete(project: ProjectRecord) = operation("Delete project") {
        val runtime = services.database.runtime()
        check(runtime.active()?.projectId != project.id) { "An agent task (running or paused) uses this project; stop it first" }
        services.changes.forgetProject(project.id)
        services.database.withTransaction {
            check(runtime.active()?.projectId != project.id) { "An agent task (running or paused) uses this project; stop it first" }
            val conversations = services.database.conversations()
            conversations.observeForProject(project.id).first().forEach {
                runtime.deleteActionsForConversation(it.id); runtime.deleteTasksForConversation(it.id)
            }
            conversations.apply { deleteMessagesForProject(project.id); deleteActionsForProject(project.id); deleteForProject(project.id) }
        }
        services.projects.delete(project)
        if (services.selectedProjectId.value == project.id) services.selectProject(null)
        "Deleted the app's copy of ${project.name}"
    }
    fun rename(name: String) {
        val project = state.value.selected ?: return
        operation("Rename") {
            services.projects.rename(project, name)
            mutable.update { it.copy(selected = services.projects.find(project.id)) }
            null
        }
    }

    private suspend fun open(project: ProjectRecord) {
        services.projects.touch(project)
        services.selectProject(project.id)
    }
    fun select(project: ProjectRecord) { if (state.value.busy == null) viewModelScope.launch { open(project) } }
    fun closeProject() = services.selectProject(null)

    fun refreshFiles() {
        val workspace = workspace() ?: return
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) {
                try { workspace.listDirectory(state.value.directory).filter { it.name != ".git" } } catch (_: Exception) { emptyList() }
            }
            mutable.update { it.copy(entries = entries) }
        }
    }
    fun navigate(path: String) { mutable.update { it.copy(directory = path) }; refreshFiles() }
    fun up() = navigate(state.value.directory.substringBeforeLast('/', ""))

    fun openFile(path: String) {
        val workspace = workspace() ?: return
        viewModelScope.launch {
            val editor = withContext(Dispatchers.IO) {
                try {
                    val size = workspace.size(path)
                    if (size > MAX_EDITABLE) return@withContext EditorState(path, "", "", true, "File is ${size / 1024} KB. Files over ${MAX_EDITABLE / 1000} KB are not opened in the editor.")
                    val bytes = workspace.readBytes(path)
                    if (bytes.take(8000).contains(0.toByte())) EditorState(path, "", "", true, "Binary file (${bytes.size} bytes) is not editable here.")
                    else bytes.toString(Charsets.UTF_8).let { EditorState(path, it, it, false) }
                } catch (error: Exception) { EditorState(path, "", "", true, friendly(error)) }
            }
            mutable.update { it.copy(editor = editor) }
        }
    }
    fun edit(text: String) = mutable.update { state -> state.copy(editor = state.editor?.copy(text = text)) }
    fun closeEditor() = mutable.update { it.copy(editor = null) }
    fun saveFile() {
        val workspace = workspace() ?: return
        val editor = state.value.editor ?: return
        if (!editor.dirty) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Refuse to overwrite a change made elsewhere (for example by an agent task) since the file was opened.
                    val current = if (workspace.exists(editor.path)) workspace.read(editor.path) else null
                    check(current == null || current == editor.original) { "${editor.path} changed since you opened it. Close and reopen it." }
                    workspace.write(editor.path, editor.text)
                }
                mutable.update { it.copy(editor = editor.copy(original = editor.text), message = "Saved ${editor.path}") }
                refreshGit()
            } catch (error: Exception) { mutable.update { it.copy(message = "Save failed: ${friendly(error)}") } }
        }
    }
    private fun child(name: String): String {
        val clean = name.trim().trim('/')
        require(clean.isNotEmpty()) { "Enter a name" }
        val path = if (state.value.directory.isEmpty()) clean else "${state.value.directory}/$clean"
        require(path.split('/').none { it == ".git" }) { "The .git directory is managed by Git" }
        return path
    }
    fun newFile(name: String) = operation("New file") {
        val workspace = workspace() ?: return@operation null
        val path = child(name)
        check(!workspace.exists(path)) { "$path already exists" }
        workspace.write(path, "")
        withContext(Dispatchers.Main) { refreshFiles(); openFile(path) }
        null
    }
    fun newFolder(name: String) = operation("New folder") {
        val workspace = workspace() ?: return@operation null
        workspace.createDirectory(child(name))
        withContext(Dispatchers.Main) { refreshFiles() }
        null
    }
    fun deleteEntry(entry: WorkspaceService.Entry) = operation("Delete") {
        workspace()?.delete(entry.path, recursive = true)
        withContext(Dispatchers.Main) { refreshFiles(); refreshGit() }
        "Deleted ${entry.path}"
    }

    fun refreshGit() {
        val dir = dir() ?: return
        viewModelScope.launch {
            val panel = withContext(Dispatchers.IO) {
                if (!services.git.isRepository(dir)) GitPanel(false)
                else try { GitPanel(true, services.git.status(dir), services.git.log(dir), services.git.remoteUrl(dir), services.git.branches(dir)) }
                catch (error: Exception) { GitPanel(true).also { mutable.update { s -> s.copy(message = "Git: ${friendly(error)}") } } }
            }
            mutable.update { it.copy(git = panel) }
        }
    }
    fun initGit() = operation("Initialize Git") {
        services.git.init(dir() ?: return@operation null)
        withContext(Dispatchers.Main) { refreshGit() }
        "Initialized a Git repository on branch main"
    }
    fun commitAll(message: String) = operation("Commit") {
        val id = services.git.commit(dir() ?: return@operation null, message, services.author())
        withContext(Dispatchers.Main) { refreshGit() }
        "Committed ${id.take(10)}"
    }
    fun pull() = operation("Pull") {
        val result = services.git.pull(dir() ?: return@operation null, services.gitCredentials?.takeIf { it.token.isNotEmpty() }, { cancelRequested }, ::progress)
        withContext(Dispatchers.Main) { refreshGit(); refreshFiles() }
        "Pull: $result"
    }
    fun loadGitHubRepos(query: String) {
        if (state.value.gitHubLoading) return
        mutable.update { it.copy(gitHubLoading = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                if (query.isBlank()) { if (services.hasGitCredentials) services.github.repos() else emptyList() } else services.github.search(query.trim())
            } }
            mutable.update { it.copy(gitHubLoading = false, gitHubRepos = result.getOrDefault(it.gitHubRepos),
                message = result.exceptionOrNull()?.let { e -> "GitHub: ${friendly(e)}" }) }
        }
    }
    fun cloneGitHub(repo: GitHubRepo) = clone(repo.cloneUrl, repo.fullName.substringAfter('/'))
    fun switchBranch(name: String, create: Boolean) = operation(if (create) "Create branch" else "Switch branch") {
        val branch = services.git.checkout(dir() ?: return@operation null, name, create)
        withContext(Dispatchers.Main) { refreshGit(); refreshFiles() }
        if (create) "Created and switched to $branch. Push to publish it." else "Switched to $branch"
    }
    fun publishToGitHub(name: String, private: Boolean) = operation("Publish to GitHub") {
        val dir = dir() ?: return@operation null
        val credentials = services.gitCredentials?.takeIf { it.token.isNotEmpty() } ?: error("Sign in to GitHub in Accounts first")
        check(services.git.remoteUrl(dir) == null) { "This project already has a remote" }
        check(services.git.log(dir, 1).isNotEmpty()) { "Commit at least once before publishing" }
        val repo = services.github.createRepository(name.trim(), private, "Created with Antigravity Mobile")
        services.git.setRemote(dir, repo.cloneUrl)
        services.git.push(dir, credentials, { cancelRequested }, ::progress)
        withContext(Dispatchers.Main) { refreshGit() }
        "Published to ${repo.fullName} (${if (repo.private) "private" else "public"})"
    }
    fun createPullRequest(title: String, body: String) = operation("Create pull request") {
        val dir = dir() ?: return@operation null
        val fullName = GitHubWire.fullName(services.git.remoteUrl(dir)) ?: error("The origin remote is not a GitHub repository")
        val branch = services.git.status(dir).branch
        val base = services.github.repository(fullName).defaultBranch
        check(branch != base) { "Create a branch for your changes first; you are on the default branch $base" }
        services.git.push(dir, services.gitCredentials?.takeIf { it.token.isNotEmpty() }, { cancelRequested }, ::progress)
        val pull = services.github.createPullRequest(fullName, branch, base, title.trim().ifEmpty { branch }, body)
        mutable.update { it.copy(lastPullRequest = pull.url) }
        withContext(Dispatchers.Main) { refreshGit() }
        "Opened pull request #${pull.number} from $branch into $base"
    }
    fun push() = operation("Push") {
        val result = services.git.push(dir() ?: return@operation null, services.gitCredentials?.takeIf { it.token.isNotEmpty() }, { cancelRequested }, ::progress)
        withContext(Dispatchers.Main) { refreshGit() }
        "Push: $result"
    }
}

/** Short, user-facing error text. Never includes credentials: our own messages and exception types only. */
fun friendly(error: Throwable): String {
    val message = error.message?.lineSequence()?.firstOrNull()?.take(240)
    return if (message.isNullOrBlank()) error.javaClass.simpleName else message
}
