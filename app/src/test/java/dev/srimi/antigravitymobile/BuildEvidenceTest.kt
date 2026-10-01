package dev.srimi.antigravitymobile

import dev.srimi.antigravitymobile.runtime.BuildEvidenceStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class BuildEvidenceTest {
    @Test fun restartRetainsLogsAndArtifactReferencesButChangedApkIsRefused() {
        val root = Files.createTempDirectory("build-evidence").toFile()
        try {
            val id = UUID.randomUUID().toString()
            val apk = File(root, "$id/apks/artifact-0.apk").apply { parentFile!!.mkdirs(); writeText("test artifact bytes") }
            val store = BuildEvidenceStore(root)
            store.saveLog(id, "e: MainActivity.kt:12: Unresolved reference\nBUILD FAILED")
            store.commitArtifacts(id, listOf(apk))
            val restarted = BuildEvidenceStore(root)
            assertEquals("e: MainActivity.kt:12: Unresolved reference\nBUILD FAILED", restarted.log(id))
            assertEquals(listOf(apk.canonicalFile), restarted.artifacts(id))
            apk.writeText("changed")
            assertTrue(restarted.artifacts(id).isEmpty())
        } finally { root.deleteRecursively() }
    }
}
