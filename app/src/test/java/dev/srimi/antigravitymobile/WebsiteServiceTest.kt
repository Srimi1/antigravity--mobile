package dev.srimi.antigravitymobile

import dev.srimi.antigravitymobile.runtime.WebFiles
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.zip.ZipFile

class WebsiteServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(): Pair<WebsiteService, File> {
        val project = temporary.newFolder().apply { File(this, "index.html").writeText("<h1>Approved</h1>") }
        return WebsiteService(temporary.newFolder()) to project
    }

    @Test fun approvalUsesTheCopiedVersionAfterTheOriginalChanges() {
        val (service, project) = fixture()
        val copy = service.prepare("project", project, "", "index.html")
        File(project, "index.html").writeText("<h1>Changed later</h1>")
        assertEquals(copy.hash, service.claim(copy.id).hash)
        ZipFile(service.archive(copy.id)).use { zip ->
            assertEquals("<h1>Approved</h1>", zip.getInputStream(zip.getEntry("index.html")).reader().readText())
        }
    }
    @Test fun onlyOneConcurrentApprovalCanClaimTheCopy() {
        val (service, project) = fixture()
        val copy = service.prepare("project", project, "", "index.html")
        val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { pool.submit<Boolean> { start.await(); runCatching { service.claim(copy.id) }.isSuccess } }
            start.countDown()
            assertEquals(1, results.count { it.get() })
        } finally { pool.shutdownNow() }
    }
    @Test fun changedArchiveCannotConsumeApproval() {
        val (service, project) = fixture()
        val copy = service.prepare("project", project, "", "index.html")
        service.archive(copy.id).appendText("tampered")
        assertThrows(IllegalStateException::class.java) { service.claim(copy.id) }
        assertEquals("AWAITING_APPROVAL", service.find(copy.id).status)
    }
    @Test fun restartInterruptsPendingAndUncertainDispatchesWithoutReplay() {
        val (service, project) = fixture()
        val pending = service.prepare("project", project, "", "index.html")
        val uncertain = service.prepare("project", project, "", "index.html")
        service.claim(uncertain.id)
        service.recover()
        listOf(pending, uncertain).forEach {
            assertEquals("INTERRUPTED", service.find(it.id).status)
            assertFalse(service.archive(it.id).exists())
            assertThrows(IllegalStateException::class.java) { service.claim(it.id) }
        }
    }
    @Test fun declineAndCompletedPreviewCannotRunAgain() {
        val (service, project) = fixture()
        val declined = service.prepare("project", project, "", "index.html")
        service.decline(declined.id)
        assertEquals("DECLINED", service.find(declined.id).status)
        assertThrows(IllegalStateException::class.java) { service.claim(declined.id) }
        val ready = service.prepare("project", project, "", "index.html")
        service.claim(ready.id); service.finish(ready.id, "READY"); service.recover()
        assertEquals("READY", service.find(ready.id).status)
        assertThrows(IllegalStateException::class.java) { service.claim(ready.id) }
    }
    @Test fun frontendOutputIsCopiedAtZipRootWithoutProjectMetadata() {
        val (service, project) = fixture()
        File(project, "secret-source.kt").writeText("not public output")
        File(project, "dist").mkdir()
        File(project, "dist/index.html").writeText("<h1>Distribution</h1>")
        File(project, "dist/.env").writeText("excluded fixture")
        File(project, "dist/node_modules").mkdir()
        File(project, "dist/node_modules/dependency.js").writeText("excluded dependency")
        val copy = service.prepare("project", project, "dist", "index.html")
        assertEquals(listOf("index.html"), copy.files)
        ZipFile(service.archive(copy.id)).use { assertEquals(1, it.size()) }
    }
    @Test fun selectionCannotEscapeProjectOrFollowLinks() {
        val (service, project) = fixture()
        assertThrows(IllegalArgumentException::class.java) { service.prepare("p", project, "../elsewhere", "index.html") }
        val outside = temporary.newFolder().apply { File(this, "index.html").writeText("outside") }
        java.nio.file.Files.createSymbolicLink(File(project, "linked").toPath(), outside.toPath())
        assertThrows(IllegalStateException::class.java) { service.prepare("p", project, "linked", "index.html") }
        assertThrows(IllegalStateException::class.java) { service.prepare("p", project, "", "missing.js") }
    }
}
