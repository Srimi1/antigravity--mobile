package dev.srimi.antigravitymobile

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectArchiveTest {
    private val base = Files.createTempDirectory("archive").toFile()

    @Test fun exportAndImportRoundTripPreservesGitAndSkipsSymlinks() {
        val source = File(base, "source").apply { mkdirs() }
        File(source, "src").mkdirs(); File(source, "src/A.kt").writeText("a")
        File(source, ".git").mkdirs(); File(source, ".git/HEAD").writeText("ref: refs/heads/main\n")
        val outside = Files.createTempDirectory("outside").toFile().apply { File(this, "secret").writeText("s") }
        Files.createSymbolicLink(File(source, "link").toPath(), outside.toPath())
        val zip = ByteArrayOutputStream().also { Archives.write(source, it) }.toByteArray()
        val target = File(base, "target").apply { mkdirs() }
        Archives.extract(ByteArrayInputStream(zip), target)
        assertEquals("a", File(target, "src/A.kt").readText())
        assertTrue(File(target, ".git/HEAD").isFile)
        assertFalse(File(target, "link").exists())
        val noGit = ByteArrayOutputStream().also { Archives.write(source, it, includeGit = false) }.toByteArray()
        val slim = File(base, "slim").apply { mkdirs() }
        Archives.extract(ByteArrayInputStream(noGit), slim)
        assertFalse(File(slim, ".git").exists())
    }

    @Test fun extractionRejectsTraversal() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry("../evil.txt")); it.write(1); it.closeEntry() }
        }.toByteArray()
        val target = File(base, "t").apply { mkdirs() }
        assertThrows(IllegalStateException::class.java) { Archives.extract(ByteArrayInputStream(zip), target) }
        assertFalse(File(base, "evil.txt").exists())
    }

    @Test fun deleteTreeDoesNotFollowLinks() {
        val outside = Files.createTempDirectory("keep").toFile().apply { File(this, "file").writeText("keep") }
        val tree = File(base, "tree").apply { mkdirs() }
        Files.createSymbolicLink(File(tree, "link").toPath(), outside.toPath())
        Archives.deleteTree(tree)
        assertFalse(tree.exists())
        assertEquals("keep", File(outside, "file").readText())
    }

    @Test fun workspaceDeleteRemovesLinkNotTarget() {
        val outside = Files.createTempDirectory("keep2").toFile().apply { File(this, "file").writeText("keep") }
        val workspace = WorkspaceService(File(base, "ws"), File(base, "cp"))
        workspace.write("dir/a.txt", "a")
        Files.createSymbolicLink(File(base, "ws/dir/link").toPath(), outside.toPath())
        assertEquals(listOf("a.txt"), workspace.listDirectory("dir").map { it.name })
        workspace.delete("dir", recursive = true)
        assertFalse(workspace.exists("dir"))
        assertEquals("keep", File(outside, "file").readText())
    }

    @Test fun buildInspectorReportsBlockedWithProjectFacts() {
        val project = File(base, "android").apply { mkdirs() }
        File(project, "settings.gradle.kts").writeText("include(\":app\")")
        File(project, "app").mkdirs(); File(project, "app/build.gradle.kts").writeText("plugins { id(\"com.android.application\") }")
        File(project, "out").mkdirs(); File(project, "out/app.apk").writeBytes(byteArrayOf(1))
        val report = BuildInspector.inspect(project)
        assertTrue(report.gradleProject && report.androidApp && !report.wrapper)
        assertEquals(listOf("out/app.apk"), report.apks)
        assertEquals(CheckStatus.BLOCKED, report.status)
    }
}
