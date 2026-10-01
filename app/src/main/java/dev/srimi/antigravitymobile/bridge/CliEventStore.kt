package dev.srimi.antigravitymobile.bridge

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files

/** Private replay journal. Recording a received event never grants permission to repeat a CLI write. */
class CliEventStore(private val root: File) {
    companion object { const val MAX_BYTES = 16 * 1024 * 1024; const val MAX_EVENTS = 5000; const val MAX_EVENT = 80 * 1024 }
    private val cache = mutableMapOf<String, MutableList<String>>()
    init { check(!Files.isSymbolicLink(root.toPath())); root.mkdirs() }
    private fun file(task: String): File {
        BridgeSecurity.checkPair(task)
        return File(root, "$task.jsonl").also { check(!Files.isSymbolicLink(it.toPath())) }
    }
    private fun load(task: String): MutableList<String> = cache.getOrPut(task) {
        val path = file(task)
        if (!path.exists()) return@getOrPut mutableListOf()
        check(path.isFile && path.length() <= MAX_BYTES) { "CLI event journal exceeds limits" }
        val bytes = path.readBytes()
        val end = bytes.indexOfLast { it == '\n'.code.toByte() } + 1
        // A crash can leave only the final append incomplete. Complete events remain authoritative.
        if (end != bytes.size) RandomAccessFile(path, "rw").use { it.setLength(end.toLong()); it.fd.sync() }
        if (end == 0) return@getOrPut mutableListOf()
        BridgeSecurity.text(bytes.copyOf(end)).removeSuffix("\n").split('\n').mapIndexed { index, line ->
            check(index < MAX_EVENTS)
            BridgeSecurity.json(line, MAX_EVENT).also { check(it.integer("sequence") == index + 1L) }.toString()
        }.toMutableList()
    }
    @Synchronized fun read(task: String): List<JSONObject> = load(task).map { BridgeSecurity.json(it, MAX_EVENT) }
    @Synchronized fun append(task: String, event: JSONObject) {
        val recorded = load(task)
        val sequence = event.integer("sequence")
        if (sequence in 1..recorded.size.toLong()) {
            check(recorded[sequence.toInt() - 1] == event.toString()) { "CLI event identity changed" }
            return
        }
        check(recorded.size < MAX_EVENTS && sequence == recorded.size + 1L) { "CLI event sequence gap" }
        val bytes = (event.toString() + "\n").toByteArray(Charsets.UTF_8)
        val path = file(task)
        check(bytes.size <= MAX_EVENT && path.length() + bytes.size <= MAX_BYTES) { "CLI event journal exceeds limits" }
        try { FileOutputStream(path, true).use { output -> output.write(bytes); output.fd.sync() } }
        catch (error: Exception) { cache.remove(task); throw error }
        recorded += event.toString()
    }
}
