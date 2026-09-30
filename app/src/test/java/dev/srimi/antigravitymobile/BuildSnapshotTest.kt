package dev.srimi.antigravitymobile

import dev.srimi.antigravitymobile.runtime.BuildProtocol
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile

class BuildSnapshotTest {
    @Test fun snapshotIncludesBuildNamedSourceButExcludesMetadataAndGradleOutput() {
        val base = Files.createTempDirectory("build-source").toFile()
        try {
            val project = File(base,"project").apply { mkdirs() }
            mapOf("build.gradle" to "plugins {}", "app/build.gradle.kts" to "plugins {}", "src/build/A.kt" to "source",
                ".git/config" to "private", "local.properties" to "sdk.dir=host", ".gradle/cache" to "old", "app/build/out.apk" to "old").forEach { (path,text) ->
                File(project,path).apply { parentFile!!.mkdirs(); writeText(text) }
            }
            val zip = File(base,"source.zip")
            val hash = BuildSnapshot.write(project,zip)
            ZipFile(zip).use { assertEquals(setOf("build.gradle", "app/build.gradle.kts", "src/build/A.kt"), it.entries().asSequence().map { e -> e.name }.toSet()) }
            assertEquals(BuildSnapshot.sha256(zip),hash)
            File(project,"src/build/A.kt").writeText("later")
            ZipFile(zip).use { assertEquals("source", it.getInputStream(it.getEntry("src/build/A.kt")).bufferedReader().readText()) }
        } finally { Archives.deleteTree(base) }
    }
    @Test fun sourceLinksAreRefusedAndExcludedGitLinksAreNotRead() {
        val base = Files.createTempDirectory("build-link").toFile()
        try {
            val project = File(base,"project").apply { mkdirs() }
            val outside = File(base,"secret").apply { writeText("private") }
            Files.createSymbolicLink(File(project,".git").toPath(),outside.toPath())
            File(project,"A.kt").writeText("source")
            BuildSnapshot.write(project,File(base,"ok.zip"))
            Files.createSymbolicLink(File(project,"leak").toPath(),outside.toPath())
            assertThrows(IllegalStateException::class.java) { BuildSnapshot.write(project,File(base,"bad.zip")) }
        } finally { Archives.deleteTree(base) }
    }
    @Test fun snapshotCannotIncludeItselfAndTaskOptionsAreRefused() {
        val base = Files.createTempDirectory("build-self").toFile()
        try {
            assertThrows(IllegalStateException::class.java) { BuildSnapshot.write(base,File(base,"copy.zip")) }
            BuildProtocol.validateId(UUID.randomUUID().toString())
            BuildProtocol.validateTasks(listOf(":app:assembleDebug", "test"))
            assertThrows(IllegalArgumentException::class.java) { BuildProtocol.validateTasks(listOf("--init-script")) }
            assertThrows(IllegalArgumentException::class.java) { BuildProtocol.validateId("------------------------------------") }
        } finally { Archives.deleteTree(base) }
    }
}
