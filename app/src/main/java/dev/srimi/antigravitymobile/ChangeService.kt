package dev.srimi.antigravitymobile

import java.io.File
import java.util.UUID

/**
 * Durable review ledger for agent edits. Each change set keeps byte snapshots of every touched file
 * as it was before the first agent edit and after the latest one. Revert is refused when the current
 * file no longer matches the recorded agent result, so a later user edit is never overwritten.
 */
class ChangeService(
    private val dao: ChangeDao,
    private val snapshotRoot: File,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Resolves a project's workspace so interrupted writes can be reconciled with the real file. */
    private val workspaceFor: suspend (projectId: String) -> WorkspaceService? = { null },
) {
    init { snapshotRoot.mkdirs() }
    private companion object {
        const val SCRATCH = "scratch"
        const val PENDING_PATH = "pending.path"
        const val PENDING_BYTES = "pending.bin"
    }

    data class FileDiff(val path: String, val before: ByteArray?, val after: ByteArray?) {
        val kind: String get() = when { before == null -> "added"; after == null -> "deleted"; else -> "modified" }
    }

    private fun snapshot(setId: String, side: String, path: String): File {
        // Paths are validated by WorkspaceService before they reach this point.
        require(path.split('/').none { it == ".." || it == "." || it.isEmpty() }) { "Invalid change path" }
        return File(snapshotRoot, "$setId/$side/$path")
    }
    private fun store(setId: String, side: String, path: String, bytes: ByteArray?) {
        val file = snapshot(setId, side, path)
        if (bytes == null) { file.delete(); return }
        file.parentFile!!.mkdirs()
        // Scratch files live outside the before/after trees with unique names, so they can never
        // collide with the snapshot of another project file (for example `.A.kt.tmp` next to `A.kt`).
        val scratch = File(snapshotRoot, "$setId/$SCRATCH").apply { mkdirs() }
        val temp = File.createTempFile("snapshot", null, scratch)
        try {
            temp.writeBytes(bytes)
            check(temp.renameTo(file) || (file.delete() && temp.renameTo(file))) { "Could not save change snapshot" }
        } finally { temp.delete() }
    }
    private fun load(setId: String, side: String, path: String, exists: Boolean): ByteArray? =
        if (exists) snapshot(setId, side, path).readBytes() else null

    /**
     * Durable record of the one workspace write in progress for a set: the path, and the intended bytes
     * (absent for a delete). Written before the workspace changes and cleared once the after snapshot and
     * record are saved, so a process death in between is detected instead of looking like a no-op.
     */
    private data class Pending(val path: String, val content: ByteArray?)
    private fun pendingDir(setId: String) = File(snapshotRoot, setId)
    private fun writePending(setId: String, path: String, content: ByteArray?) {
        val dir = pendingDir(setId).apply { mkdirs() }
        val bytes = File(dir, PENDING_BYTES)
        if (content == null) bytes.delete() else atomicWrite(bytes, content)
        atomicWrite(File(dir, PENDING_PATH), (if (content == null) "D " else "W ").plus(path).toByteArray())
    }
    private fun readPending(setId: String): Pending? {
        val marker = File(pendingDir(setId), PENDING_PATH).takeIf { it.isFile } ?: return null
        val text = marker.readText()
        val path = text.substring(2)
        return Pending(path, if (text.startsWith("W ")) File(pendingDir(setId), PENDING_BYTES).readBytes() else null)
    }
    private fun clearPending(setId: String) {
        File(pendingDir(setId), PENDING_PATH).delete()
        File(pendingDir(setId), PENDING_BYTES).delete()
    }
    private fun atomicWrite(file: File, bytes: ByteArray) {
        val scratch = File(file.parentFile, SCRATCH).apply { mkdirs() }
        val temp = File.createTempFile("pending", null, scratch)
        try {
            temp.outputStream().use { it.write(bytes); it.fd.sync() }
            check(temp.renameTo(file) || (file.delete() && temp.renameTo(file))) { "Could not save change record" }
        } finally { temp.delete() }
    }
    private suspend fun commitPending(setId: String, path: String, content: ByteArray?) {
        val record = dao.file(setId, path) ?: error("Change record for $path is missing")
        store(setId, "after", path, content)
        dao.saveFile(record.copy(afterExists = content != null, updatedAt = clock()))
        clearPending(setId)
    }

    /**
     * Settles a write interrupted between the workspace change and the after snapshot. Returns null when
     * nothing was pending or it was settled, otherwise a message for the set's detail.
     */
    private suspend fun reconcile(set: ChangeSetRecord): String? {
        val pending = readPending(set.id) ?: return null
        val workspace = workspaceFor(set.projectId)
            ?: return "Agent task stopped while writing ${pending.path}. Check that file before keeping these edits."
        val current = if (workspace.exists(pending.path) && !workspace.isDirectory(pending.path)) workspace.readBytes(pending.path) else null
        val record = dao.file(set.id, pending.path)
        val recorded = record?.let { load(set.id, "after", it.path, it.afterExists) }
        return when {
            record != null && current.contentEqualsNullable(pending.content) -> { commitPending(set.id, pending.path, pending.content); null }
            current.contentEqualsNullable(recorded) -> { clearPending(set.id); null }
            else -> { clearPending(set.id)
                "${pending.path} changed in an unexpected way while the agent was writing it. Revert will refuse that file." }
        }
    }

    suspend fun open(projectId: String, conversationId: String?, summary: String): ChangeSetRecord {
        val now = clock()
        val set = ChangeSetRecord(UUID.randomUUID().toString(), projectId, conversationId,
            summary.take(160), "OPEN", "Agent task is editing files", now, now)
        dao.saveSet(set)
        return set
    }

    /**
     * Applies an edit through [workspace] and records it. [content] null deletes the file.
     * Order matters for crash safety: the before snapshot, a provisional record and the pending intent
     * are saved first, so an interrupted write is reconciled with the workspace on recovery and is
     * never mistaken for "no change".
     */
    suspend fun apply(setId: String, workspace: WorkspaceService, path: String, content: ByteArray?, expected: FileBaseline? = null) {
        workspace.normalize(path)
        val set = dao.findSet(setId) ?: error("Change set was not found")
        check(set.status == "OPEN") { "Change set is no longer open" }
        val current = if (workspace.exists(path) && !workspace.isDirectory(path)) workspace.readBytes(path) else null
        check(!workspace.exists(path) || current != null) { "$path is a directory" }
        if (expected != null) check(current.contentEqualsNullable(expected.bytes)) { "Later edit detected in $path; approved action refused" }
        var record = dao.file(setId, path)
        if (record == null) {
            store(setId, "before", path, current)
            store(setId, "after", path, current)
            record = ChangeFileRecord(setId, path, current != null, current != null, clock())
            dao.saveFile(record)
        }
        writePending(setId, path, content)
        try { workspace.compareAndApply(path, current, content) }
        catch (error: Exception) { clearPending(setId); throw error } // workspace unchanged: the write is atomic
        commitPending(setId, path, content)
    }

    /** Closes an agent task's set: REVIEW if anything changed, otherwise it is discarded. */
    suspend fun finish(setId: String) {
        val set = dao.findSet(setId) ?: return
        if (set.status != "OPEN") return
        val problem = reconcile(set)
        if (problem == null && diffs(setId).isEmpty()) { discard(setId); return }
        dao.saveSet(set.copy(status = "REVIEW", detail = problem ?: "Waiting for review", updatedAt = clock()))
    }
    private suspend fun discard(setId: String) {
        dao.deleteFiles(setId)
        dao.deleteSet(setId)
        File(snapshotRoot, setId).deleteRecursively()
    }

    /** Sets left OPEN by a killed process become reviewable; nothing is re-applied. */
    suspend fun recoverInterrupted() {
        dao.setsWithStatus("OPEN").forEach { set ->
            val problem = reconcile(set)
            if (problem == null && dao.files(set.id).isEmpty()) discard(set.id)
            else dao.saveSet(set.copy(status = "REVIEW", detail = problem ?: "Agent task was interrupted. Review before keeping these edits.", updatedAt = clock()))
        }
    }

    suspend fun diffs(setId: String): List<FileDiff> = dao.files(setId).mapNotNull { file ->
        val before = load(setId, "before", file.path, file.beforeExists)
        val after = load(setId, "after", file.path, file.afterExists)
        if (before.contentEqualsNullable(after)) null else FileDiff(file.path, before, after)
    }

    suspend fun accept(setId: String) = transition(setId, setOf("REVIEW"), "ACCEPTED", "Kept. Commit it from Changes or the Git panel.")

    /**
     * Restores the pre-task state into [workspace], which must belong to [projectId] when given.
     * Refused before any write when a file changed after the agent's last edit. Each file is then restored
     * with an atomic compare-and-apply, so an edit made during the revert is never overwritten; such files
     * are reported and the set stays reviewable (files already restored are accepted on retry).
     */
    suspend fun revert(setId: String, workspace: WorkspaceService, projectId: String? = null) {
        val set = dao.findSet(setId) ?: error("Change set was not found")
        if (projectId != null) check(set.projectId == projectId) { "This change set belongs to another project" }
        check(set.status == "REVIEW" || set.status == "ACCEPTED") { "Only unreviewed or accepted changes can be reverted" }
        val diffs = diffs(setId)
        fun current(path: String) = if (workspace.exists(path) && !workspace.isDirectory(path)) workspace.readBytes(path) else null
        diffs.forEach { diff ->
            val current = current(diff.path)
            check(current.contentEqualsNullable(diff.after) || current.contentEqualsNullable(diff.before)) {
                "Later edit detected in ${diff.path}; revert refused" }
        }
        val conflicts = mutableListOf<String>()
        diffs.forEach { diff ->
            if (current(diff.path).contentEqualsNullable(diff.before)) return@forEach
            try { workspace.compareAndApply(diff.path, diff.after, diff.before) }
            catch (_: IllegalStateException) { conflicts += diff.path }
        }
        if (conflicts.isEmpty()) {
            dao.saveSet(set.copy(status = "REVERTED", detail = "Restored ${diffs.size} file(s)", updatedAt = clock()))
            return
        }
        val detail = "Partly reverted: ${conflicts.joinToString()} changed during the revert and were left as they are."
        dao.saveSet(set.copy(detail = detail, updatedAt = clock()))
        error(detail)
    }

    suspend fun markCommitted(setIds: List<String>, commitId: String) = setIds.forEach {
        transition(it, setOf("ACCEPTED", "REVIEW"), "COMMITTED", "Committed as ${commitId.take(10)}")
    }

    private suspend fun transition(setId: String, from: Set<String>, to: String, detail: String) {
        val set = dao.findSet(setId) ?: error("Change set was not found")
        check(set.status in from) { "Change set is ${set.status.lowercase()}" }
        dao.saveSet(set.copy(status = to, detail = detail, updatedAt = clock()))
    }

    suspend fun forgetProject(projectId: String) = dao.sets(projectId).forEach { discard(it.id) }
}

fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    if (this == null || other == null) this == null && other == null else contentEquals(other)
