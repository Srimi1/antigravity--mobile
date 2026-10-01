package dev.srimi.antigravitymobile.bridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CliEventStoreTest {
    private fun event(sequence: Int, text: String = "text") = JSONObject().put("sequence", sequence).put("kind", "cli").put("text", text)
    @Test fun restartRetainsCompleteEventsAndDuplicateNeverAppends() {
        val root = Files.createTempDirectory("cli-events").toFile()
        try {
            val store = CliEventStore(root)
            store.append("task", event(1)); store.append("task", event(1)); store.append("task", event(2))
            val path = File(root, "task.jsonl")
            val length = path.length()
            path.appendText("{\"sequence\":3,")
            val recovered = CliEventStore(root)
            assertEquals(listOf(1L, 2L), recovered.read("task").map { it.integer("sequence") })
            assertEquals(length, path.length())
            recovered.append("task", event(3))
            assertEquals(3, CliEventStore(root).read("task").size)
        } finally { root.deleteRecursively() }
    }
    @Test fun changedIdentityGapsAndOversizeNeverAdvanceJournal() {
        val root = Files.createTempDirectory("cli-events").toFile()
        try {
            val store = CliEventStore(root); store.append("task", event(1))
            for (value in listOf(event(1, "changed"), event(3), event(2, "x".repeat(CliEventStore.MAX_EVENT)))) {
                assertThrows(IllegalStateException::class.java) { store.append("task", value) }
            }
            assertEquals(1, CliEventStore(root).read("task").size)
        } finally { root.deleteRecursively() }
    }
}
