package dev.srimi.antigravitymobile

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Real JGit operations on the JVM. Android device validation is still pending. */
class GitServiceTest {
    private val base = Files.createTempDirectory("git-check").toFile()
    private val git = GitService(File(base, "home"))
    private val author = PersonIdent("Phone User", "phone@example.invalid")

    @Test fun initCommitStatusAndLog() {
        val repo = File(base, "repo").apply { mkdirs() }
        git.init(repo)
        assertTrue(git.isRepository(repo))
        File(repo, "README.md").writeText("hello\n")
        File(repo, "src").mkdirs(); File(repo, "src/Main.kt").writeText("fun main() {}\n")
        assertEquals(setOf("README.md", "src/Main.kt"), git.status(repo).untracked)
        val first = git.commit(repo, "Initial commit", author)
        assertTrue(git.status(repo).clean)
        assertEquals("main", git.status(repo).branch)
        File(repo, "README.md").writeText("changed\n")
        File(repo, "src/Main.kt").delete()
        File(repo, "new.txt").writeText("new\n")
        val second = git.commit(repo, "Update", author)
        assertTrue(git.status(repo).clean)
        val log = git.log(repo)
        assertEquals(listOf(second, first), log.map { it.id })
        assertEquals("Update", log.first().message)
        assertThrows(IllegalStateException::class.java) { git.commit(repo, "Empty", author) }
    }

    @Test fun commitOnlySelectedPathsLeavesOtherWorkUncommitted() {
        val repo = File(base, "partial").apply { mkdirs() }
        git.init(repo)
        File(repo, "keep.txt").writeText("1\n"); File(repo, "gone.txt").writeText("x\n")
        git.commit(repo, "Base", author)
        File(repo, "agent.kt").writeText("agent\n")
        File(repo, "gone.txt").delete()
        File(repo, "keep.txt").writeText("user work in progress\n")
        git.commit(repo, "Agent change", author, listOf("agent.kt", "gone.txt"))
        val status = git.status(repo)
        assertEquals(setOf("keep.txt"), status.modified)
        assertTrue(status.untracked.isEmpty() && status.missing.isEmpty() && status.added.isEmpty())
        Git.open(repo).use { assertEquals("Agent change", it.log().setMaxCount(1).call().first().shortMessage) }
    }

    @Test fun appConfigDisablesAutomaticGcAndAvoidsHostConfig() {
        val config = org.eclipse.jgit.util.SystemReader.getInstance().userConfig
        assertEquals(0, config.getInt("gc", "auto", -1))
        assertTrue(org.eclipse.jgit.util.SystemReader.getInstance().getProperty("user.home").endsWith("home"))
    }

    @Test fun cloneRejectsNonHttpsRemotes() {
        assertThrows(IllegalArgumentException::class.java) {
            git.clone("file:///tmp/x", File(base, "clone"), null, { false }) {}
        }
    }

    @Test fun unifiedDiffShowsChangedLines() {
        val diff = TextDiff.unified("A.kt", "a\nb\nc\n".toByteArray(), "a\nB\nc\nd\n".toByteArray())
        assertTrue(diff.startsWith("--- a/A.kt\n+++ b/A.kt\n"))
        assertTrue(diff.contains("-b\n") && diff.contains("+B\n") && diff.contains("+d\n"))
        assertEquals(2 to 1, TextDiff.stats(diff))
        assertTrue(TextDiff.unified("new.kt", null, "x\n".toByteArray()).startsWith("--- /dev/null"))
        assertTrue(TextDiff.unified("bin", byteArrayOf(0, 1), byteArrayOf(0, 2)).startsWith("Binary file"))
    }

    @Test fun savedTokenIsScopedToHttpsGitHub() {
        assertTrue(GitCredentialScope.allows("https://github.com/owner/repo.git"))
        assertTrue(GitCredentialScope.allows("https://GitHub.com:443/owner/repo"))
        for (url in listOf("https://evil.example/owner/repo.git", "https://github.com@evil.example/repo",
            "https://github.com.evil.example/repo", "https://evil.example/github.com/repo", "http://github.com/owner/repo",
            "https://gist.github.com/x", "https://github.com:8443/owner/repo", "ssh://git@github.com/owner/repo", "", "not a url")) {
            assertFalse(url, GitCredentialScope.allows(url))
        }
        val provider = GitHubScopedCredentials(GitCredentials("user", "secret-token"))
        val user = org.eclipse.jgit.transport.CredentialItem.Username()
        val pass = org.eclipse.jgit.transport.CredentialItem.Password()
        assertFalse(provider.get(org.eclipse.jgit.transport.URIish("https://evil.example/r.git"), user, pass))
        assertNull(pass.value)
        assertTrue(provider.get(org.eclipse.jgit.transport.URIish("https://github.com/o/r.git"), user, pass))
        assertEquals("secret-token", String(pass.value))
    }

    @Test fun importedNonGitHubRemoteNeverReceivesToken() {
        val repo = File(base, "imported").apply { mkdirs() }
        git.init(repo)
        File(repo, "a.txt").writeText("a\n"); git.commit(repo, "a", author)
        // Simulates an imported .git/config whose fetch URL looks like GitHub but whose push URL does not.
        Git.open(repo).use { g -> g.repository.config.apply {
            setString("remote", "origin", "url", "https://github.com/owner/repo.git")
            setString("remote", "origin", "pushurl", "https://127.0.0.1:1/owner/repo.git"); save() } }
        val messages = mutableListOf<String>()
        runCatching { git.push(repo, GitCredentials("user", "secret-token"), { false }) { messages += it } }
        assertTrue(messages.toString(), messages.any { it.startsWith("Saved GitHub token not sent") })
    }
}
