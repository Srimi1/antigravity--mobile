package dev.srimi.antigravitymobile

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GitHubSupportTest {
    @Test fun githubRemotesAreRecognised() {
        assertEquals("Srimi1/antigravity--mobile", GitHubWire.fullName("https://github.com/Srimi1/antigravity--mobile.git"))
        assertEquals("a/b.c", GitHubWire.fullName("https://user@github.com/a/b.c"))
        assertNull(GitHubWire.fullName("https://gitlab.com/a/b.git"))
        assertNull(GitHubWire.fullName("https://github.com/a"))
        assertNull(GitHubWire.fullName(null))
    }
    @Test fun repositoriesAndUserParse() {
        val repos = GitHubWire.repos(JSONArray("""[{"full_name":"me/app","clone_url":"https://github.com/me/app.git","default_branch":"dev",
            "private":true,"description":null,"updated_at":"2026-10-01T00:00:00Z"}]"""))
        assertEquals(GitHubRepo("me/app", "https://github.com/me/app.git", "dev", true, "", "2026-10-01T00:00:00Z"), repos.single())
        assertEquals(GitHubUser("me", ""), GitHubWire.user(JSONObject("""{"login":"me","name":null}""")))
    }
    @Test fun errorsNeverEchoTokens() {
        val message = GitHubWire.describe(422, """{"message":"Validation Failed ghp_abcdefghijklmnopqrstuvwxyz123456","errors":[{"message":"name already exists on this account"}]}""")
        assertTrue(message, message.contains("name already exists"))
        assertFalse(message, message.contains("ghp_abc"))
        assertTrue(GitHubWire.describe(401, "{}").contains("invalid or expired"))
    }
    @Test fun chatGptCatalogKeepsEveryModelWithDisplayNames() {
        val models = ResponsesWire.catalog(JSONObject("""{"models":[{"slug":"b","display_name":"Hidden B","visibility":"hide"},
            {"slug":"a","display_name":"Model A","visibility":"list"},{"slug":"a","visibility":"list"},{"slug":"c"}]}"""))
        assertEquals(listOf(ChatModel("a", "Model A", true), ChatModel("c", "c", true), ChatModel("b", "Hidden B", false)), models)
    }

    @Test fun branchPushTrackingAndRemoteBranchCheckout() {
        val base = Files.createTempDirectory("github-flow").toFile()
        val service = GitService(File(base, "home"))
        val author = PersonIdent("Phone User", "phone@example.invalid")
        val remote = File(base, "remote.git").also { Git.init().setBare(true).setDirectory(it).setInitialBranch("main").call().close() }
        val repo = File(base, "repo").apply { mkdirs() }
        service.init(repo)
        File(repo, "README.md").writeText("hello\n")
        service.commit(repo, "Initial", author)
        Git.open(repo).use { it.repository.config.apply { setString("remote", "origin", "url", remote.toURI().toString())
            setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*"); save() } }
        service.push(repo, null, { false }, {})
        assertEquals("origin", Git.open(repo).use { it.repository.config.getString("branch", "main", "remote") })

        assertEquals("feature", service.checkout(repo, "feature", create = true))
        File(repo, "feature.txt").writeText("x\n")
        assertThrows(IllegalStateException::class.java) { service.checkout(repo, "main", create = false) }
        service.commit(repo, "Feature", author)
        service.push(repo, null, { false }, {})
        assertNotNull(Git.open(remote).use { it.repository.findRef("refs/heads/feature") })
        assertThrows(IllegalStateException::class.java) { service.checkout(repo, "feature", create = true) }
        assertThrows(IllegalArgumentException::class.java) { service.checkout(repo, "bad name", create = true) }

        // A second clone sees the branch only on the remote, then checks it out with tracking.
        val other = File(base, "other")
        Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(other).call().close()
        assertTrue("origin/feature" in service.branches(other))
        assertEquals("feature", service.checkout(other, "origin/feature", create = false))
        assertTrue(File(other, "feature.txt").isFile)
        assertEquals(listOf("feature", "main"), service.branches(other))
        assertThrows(IllegalArgumentException::class.java) { service.setRemote(repo, "git@github.com:a/b.git") }
    }
}
