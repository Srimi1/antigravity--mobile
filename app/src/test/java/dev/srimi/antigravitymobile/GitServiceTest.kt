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
}
