package dev.srimi.antigravitymobile.bridge

import dev.srimi.antigravitymobile.*
import dev.srimi.antigravitymobile.runtime.ApprovalDecision
import dev.srimi.antigravitymobile.runtime.ToolOutcome
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** No file tools: a CLI edits its private copy itself. Only the native build/install tools are exposed. */
internal object NoFileTools : ToolHost {
    override val specs = emptyList<ToolSpec>()
    override fun requiresApproval(name: String) = false
    override fun approvalCategory(name: String): dev.srimi.antigravitymobile.runtime.ApprovalCategory? = null
    override suspend fun describe(call: AgentItem.ToolCall): ToolPreview = throw BridgeProtocolException()
    override suspend fun execute(call: AgentItem.ToolCall): ToolOutcome = throw BridgeProtocolException()
}

/**
 * Builds the CLI's current private workspace, not the native project: it captures the copy, materializes it in app storage
 * and hands it to the same one-shot approval/worker path as a native build. Installs only builds from this task.
 */
internal class CliBuildRunner(
    private val services: AppContainer,
    private val project: ProjectRecord,
    private val taskId: String,
    private val delegate: BuildRunner,
    private val workspaces: CliWorkspaceStore,
    private val capture: suspend (File) -> File,
    private val root: File,
) : BuildRunner by delegate {
    override suspend fun prepare(tasks: String): PreparedBuild {
        val stamp = UUID.randomUUID().toString()
        val area = File(root, taskId).apply { mkdirs() }
        check(!Files.isSymbolicLink(area.toPath())) { "CLI build area cannot be a link" }
        val archive = File(area, "$stamp.zip")
        val copy = File(area, stamp)
        try {
            workspaces.materialize(capture(archive), copy)
            val record = services.builds.prepareFrom(project, copy, tasks, taskId)
            return PreparedBuild(record.id, record.tasks, record.snapshotHash)
        } finally {
            archive.delete()
            if (copy.exists()) Files.walk(copy.toPath()).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
    override suspend fun recorded(id: String?, successfulOnly: Boolean): BuildOutcome? {
        val mine = services.database.builds().forProject(project.id).filter { it.agentTaskId == taskId }
        val candidates = if (id != null) mine.filter { it.id == id } else mine
        return candidates.firstNotNullOfOrNull { delegate.recorded(it.id, successfulOnly) }
    }
    override suspend fun install(buildId: String, apk: String?): String {
        check(services.builds.find(buildId)?.agentTaskId == taskId) { "Only a build from this CLI task can be installed" }
        return delegate.install(buildId, apk)
    }
    override suspend fun resolvePending(id: String, decision: ApprovalDecision) = delegate.resolvePending(id, decision)
}
