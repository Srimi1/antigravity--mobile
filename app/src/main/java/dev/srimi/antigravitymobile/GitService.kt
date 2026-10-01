package dev.srimi.antigravitymobile

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.ByteArrayOutputStream
import java.io.File

data class GitStatus(
    val branch: String,
    val added: Set<String>, val changed: Set<String>, val modified: Set<String>, val untracked: Set<String>,
    val missing: Set<String>, val removed: Set<String>, val conflicting: Set<String>,
) {
    val clean: Boolean get() = listOf(added, changed, modified, untracked, missing, removed, conflicting).all { it.isEmpty() }
    val summary: String get() = if (clean) "Working tree clean on $branch" else buildString {
        append("On $branch: ")
        append(listOf("staged" to added.size + changed.size + removed.size, "modified" to modified.size,
            "untracked" to untracked.size, "deleted" to missing.size, "conflicts" to conflicting.size)
            .filter { it.second > 0 }.joinToString { "${it.second} ${it.first}" })
    }
}
data class GitCommitInfo(val id: String, val message: String, val author: String, val time: Long)
data class GitCredentials(val username: String, val token: String)

/**
 * Pure-Java Git through JGit. No `git` executable is used: Android app processes cannot rely on one.
 * The Android-specific behavior (system reader, file modes on app storage) is validated on the JVM only
 * until instrumentation runs on a device.
 */
class GitService(home: File) {
    init { GitRuntime.install(home) }

    fun isRepository(dir: File): Boolean = File(dir, ".git").isDirectory

    fun init(dir: File) { Git.init().setDirectory(dir).setInitialBranch("main").call().close() }

    fun clone(url: String, dir: File, credentials: GitCredentials?, isCancelled: () -> Boolean, progress: (String) -> Unit) {
        require(url.startsWith("https://")) { "Only HTTPS remotes are supported" }
        check(!dir.exists() || dir.list().isNullOrEmpty()) { "Destination is not empty" }
        Git.cloneRepository().setURI(url).setDirectory(dir).setCloneAllBranches(false)
            .setCredentialsProvider(provider(credentials)).setProgressMonitor(Monitor(isCancelled, progress))
            .call().close()
    }

    fun status(dir: File): GitStatus = Git.open(dir).use { git ->
        val status = git.status().call()
        GitStatus(git.repository.branch ?: "(detached)", status.added, status.changed, status.modified,
            status.untracked, status.missing, status.removed, status.conflicting)
    }

    /** Stages [paths] (additions, edits and deletions) or everything when null, then commits. */
    fun commit(dir: File, message: String, author: PersonIdent, paths: List<String>? = null): String = Git.open(dir).use { git ->
        require(message.isNotBlank()) { "Enter a commit message" }
        if (paths == null) {
            git.add().addFilepattern(".").call()
            git.add().addFilepattern(".").setUpdate(true).call()
        } else {
            require(paths.isNotEmpty()) { "Nothing selected to commit" }
            paths.forEach { path ->
                if (File(dir, path).exists()) git.add().addFilepattern(path).call()
                else git.rm().addFilepattern(path).setCached(true).call()
            }
        }
        val status = git.status().call()
        check(status.added.isNotEmpty() || status.changed.isNotEmpty() || status.removed.isNotEmpty()) { "Nothing to commit" }
        val commit = git.commit().setMessage(message.trim()).setAuthor(author).setCommitter(author)
        if (paths != null) paths.forEach { commit.setOnly(it) }
        commit.call().name
    }

    fun log(dir: File, max: Int = 50): List<GitCommitInfo> = Git.open(dir).use { git ->
        if (git.repository.resolve("HEAD") == null) return@use emptyList()
        git.log().setMaxCount(max).call().map {
            GitCommitInfo(it.name, it.shortMessage, it.authorIdent.name, it.commitTime * 1000L)
        }
    }

    /** Local branches, then remote-only branches (as `origin/name`). */
    fun branches(dir: File): List<String> = Git.open(dir).use { git ->
        val local = git.branchList().call().map { Repository.shortenRefName(it.name) }
        val remote = git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call().map { Repository.shortenRefName(it.name) }
            .filter { it.startsWith("origin/") && it != "origin/HEAD" && it.removePrefix("origin/") !in local }
        local.sorted() + remote.sorted()
    }

    /** Switches branch. Refuses when uncommitted changes would be carried over or lost. */
    fun checkout(dir: File, name: String, create: Boolean): String = Git.open(dir).use { git ->
        val target = name.trim()
        require(Repository.isValidRefName("refs/heads/${target.removePrefix("origin/")}")) { "Invalid branch name" }
        check(git.status().call().isClean) { "Commit or revert your changes before switching branches" }
        val local = target.removePrefix("origin/")
        val exists = git.repository.findRef("refs/heads/$local") != null
        when {
            create -> { check(!exists) { "Branch $local already exists" }; git.checkout().setCreateBranch(true).setName(local).call() }
            exists -> git.checkout().setName(local).call()
            else -> git.checkout().setCreateBranch(true).setName(local).setStartPoint("origin/$local")
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK).call()
        }
        local
    }

    fun setRemote(dir: File, url: String) = Git.open(dir).use { git ->
        require(url.startsWith("https://")) { "Only HTTPS remotes are supported" }
        git.repository.config.apply { setString("remote", "origin", "url", url)
            setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*"); save() }
    }

    fun remoteUrl(dir: File): String? = Git.open(dir).use { it.repository.config.getString("remote", "origin", "url") }

    fun pull(dir: File, credentials: GitCredentials?, isCancelled: () -> Boolean, progress: (String) -> Unit): String = Git.open(dir).use { git ->
        val result = git.pull().setCredentialsProvider(provider(credentials)).setProgressMonitor(Monitor(isCancelled, progress)).call()
        check(result.isSuccessful) { "Pull did not complete: ${result.mergeResult?.mergeStatus ?: "fetch failed"}" }
        result.mergeResult?.mergeStatus?.toString() ?: "Up to date"
    }

    fun push(dir: File, credentials: GitCredentials?, isCancelled: () -> Boolean, progress: (String) -> Unit): String = Git.open(dir).use { git ->
        val branch = git.repository.branch ?: error("Check out a branch before pushing")
        val results = git.push().setRemote("origin").setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
            .setCredentialsProvider(provider(credentials)).setProgressMonitor(Monitor(isCancelled, progress)).call()
        // Track the pushed branch so later pulls know where to fetch from.
        git.repository.config.apply {
            if (getString("branch", branch, "remote") == null) { setString("branch", branch, "remote", "origin")
                setString("branch", branch, "merge", "refs/heads/$branch"); save() }
        }
        val updates = results.flatMap { it.remoteUpdates }
        val failed = updates.filter { it.status.name !in setOf("OK", "UP_TO_DATE") }
        check(failed.isEmpty()) { "Push rejected: " + failed.joinToString { "${it.remoteName} ${it.status}" } }
        updates.joinToString { "${it.remoteName}: ${it.status}" }.ifEmpty { "Nothing to push" }
    }

    private fun provider(credentials: GitCredentials?): CredentialsProvider? =
        credentials?.let { UsernamePasswordCredentialsProvider(it.username.ifBlank { "token" }, it.token) }

    private class Monitor(private val cancelled: () -> Boolean, private val progress: (String) -> Unit) : ProgressMonitor {
        private var task = ""; private var total = 0; private var done = 0; private var reported = -1
        override fun start(totalTasks: Int) {}
        override fun beginTask(title: String?, totalWork: Int) { task = title.orEmpty(); total = totalWork; done = 0; reported = -1; progress(task) }
        override fun update(completed: Int) {
            done += completed
            if (total > 0) { val percent = done * 100 / total; if (percent / 10 != reported / 10) { reported = percent; progress("$task $percent%") } }
        }
        override fun endTask() {}
        override fun isCancelled(): Boolean = cancelled()
    }
}

/** Keeps JGit away from a host `git` binary and the (nonexistent) Android user home. */
object GitRuntime {
    @Volatile private var installed = false
    @Synchronized fun install(home: File) {
        if (installed) return
        home.mkdirs()
        // JGit's gc pid lock calls java.lang.management, which Android does not provide; never auto-gc.
        File(home, ".gitconfig").takeIf { !it.exists() }?.writeText("[gc]\n\tauto = 0\n\tautoPackLimit = 0\n")
        SystemReader.setInstance(AppSystemReader(SystemReader.getInstance(), home))
        installed = true
    }
    private class AppSystemReader(private val base: SystemReader, private val home: File) : SystemReader() {
        override fun getHostname(): String = "antigravity-mobile"
        override fun getenv(variable: String?): String? = if (variable == "HOME") home.path else base.getenv(variable)
        override fun getProperty(key: String?): String? = if (key == "user.home") home.path else base.getProperty(key)
        override fun openUserConfig(parent: Config?, fs: FS?): FileBasedConfig = FileBasedConfig(parent, File(home, ".gitconfig"), fs)
        override fun openSystemConfig(parent: Config?, fs: FS?): FileBasedConfig = FileBasedConfig(parent, File(home, "system.gitconfig"), fs)
        override fun openJGitConfig(parent: Config?, fs: FS?): FileBasedConfig = FileBasedConfig(parent, File(home, "jgit.config"), fs)
        override fun getCurrentTime(): Long = base.currentTime
        override fun getTimezone(time: Long): Int = base.getTimezone(time)
    }
}

object TextDiff {
    /** Unified diff for review screens. Binary content is summarized instead of rendered. */
    fun unified(path: String, before: ByteArray?, after: ByteArray?, context: Int = 3): String {
        val old = before ?: ByteArray(0)
        val new = after ?: ByteArray(0)
        if (RawText.isBinary(old) || RawText.isBinary(new)) return "Binary file $path (${old.size} → ${new.size} bytes)\n"
        val a = RawText(old)
        val b = RawText(new)
        val edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM).diff(RawTextComparator.DEFAULT, a, b)
        val out = ByteArrayOutputStream()
        out.write("--- ${if (before == null) "/dev/null" else "a/$path"}\n+++ ${if (after == null) "/dev/null" else "b/$path"}\n".toByteArray())
        DiffFormatter(out).use { formatter -> formatter.setContext(context); formatter.format(edits, a, b) }
        return out.toString(Charsets.UTF_8.name())
    }
    fun stats(diff: String): Pair<Int, Int> {
        val lines = diff.lineSequence().filterNot { it.startsWith("+++") || it.startsWith("---") }
        return lines.count { it.startsWith("+") } to diff.lineSequence().filterNot { it.startsWith("---") || it.startsWith("+++") }.count { it.startsWith("-") }
    }
}
