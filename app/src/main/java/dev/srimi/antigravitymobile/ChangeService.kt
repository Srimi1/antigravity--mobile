package dev.srimi.antigravitymobile

import java.io.File
import java.util.UUID

/**
 * Durable review ledger for agent edits. Each change set keeps byte snapshots of every touched file
 * as it was before the first agent edit and after the latest one. Revert is refused when the current
 * file no longer matches the recorded agent result, so a later user edit is never overwritten.
 */
class ChangeService(private val dao: ChangeDao, private val snapshotRoot: File, private val clock: () -> Long = System::currentTimeMillis) {
    init { snapshotRoot.mkdirs() }

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
        val temp = File(file.parentFile, ".${file.name}.tmp")
        temp.writeBytes(bytes)
        check(temp.renameTo(file) || (file.delete() && temp.renameTo(file))) { "Could not save change snapshot" }
    }
    private fun load(setId: String, side: String, path: String, exists: Boolean): ByteArray? =
        if (exists) snapshot(setId, side, path).readBytes() else null

    suspend fun open(projectId: String, conversationId: String?, summary: String): ChangeSetRecord {
        val now = clock()
        val set = ChangeSetRecord(UUID.randomUUID().toString(), projectId, conversationId,
            summary.take(160), "OPEN", "Agent task is editing files", now, now)
        dao.saveSet(set)
        return set
    }

    /**
     * Applies an edit through [workspace] and records it. [content] null deletes the file.
     * Order matters for crash safety: the before snapshot and a provisional record are saved first,
     * so an interrupted write can only make a later revert refuse, never silently lose data.
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
        workspace.compareAndApply(path, current, content)
        store(setId, "after", path, content)
        dao.saveFile(record.copy(afterExists = content != null, updatedAt = clock()))
    }

    /** Closes an agent task's set: REVIEW if anything changed, otherwise it is discarded. */
    suspend fun finish(setId: String) {
        val set = dao.findSet(setId) ?: return
        if (set.status != "OPEN") return
        if (diffs(setId).isEmpty()) { discard(setId); return }
        dao.saveSet(set.copy(status = "REVIEW", detail = "Waiting for review", updatedAt = clock()))
    }
    private suspend fun discard(setId: String) {
        dao.deleteFiles(setId)
        dao.deleteSet(setId)
        File(snapshotRoot, setId).deleteRecursively()
    }

    /** Sets left OPEN by a killed process become reviewable; nothing is re-applied. */
    suspend fun recoverInterrupted() {
        dao.setsWithStatus("OPEN").forEach { set ->
            if (dao.files(set.id).isEmpty()) discard(set.id)
            else dao.saveSet(set.copy(status = "REVIEW", detail = "Agent task was interrupted. Review before keeping these edits.", updatedAt = clock()))
        }
    }

    suspend fun diffs(setId: String): List<FileDiff> = dao.files(setId).mapNotNull { file ->
        val before = load(setId, "before", file.path, file.beforeExists)
        val after = load(setId, "after", file.path, file.afterExists)
        if (before.contentEqualsNullable(after)) null else FileDiff(file.path, before, after)
    }

    suspend fun accept(setId: String) = transition(setId, setOf("REVIEW"), "ACCEPTED", "Kept. Commit it from Changes or the Git panel.")

    /** Restores the pre-task state. Refused when any file changed after the agent's last edit. */
    suspend fun revert(setId: String, workspace: WorkspaceService) {
        val set = dao.findSet(setId) ?: error("Change set was not found")
        check(set.status == "REVIEW" || set.status == "ACCEPTED") { "Only unreviewed or accepted changes can be reverted" }
        val diffs = diffs(setId)
        diffs.forEach { diff ->
            val current = if (workspace.exists(diff.path)) workspace.readBytes(diff.path) else null
            check(current.contentEqualsNullable(diff.after)) { "Later edit detected in ${diff.path}; revert refused" }
        }
        diffs.forEach { diff ->
            if (diff.before == null) workspace.delete(diff.path) else workspace.writeBytes(diff.path, diff.before)
        }
        dao.saveSet(set.copy(status = "REVERTED", detail = "Restored ${diffs.size} file(s)", updatedAt = clock()))
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
