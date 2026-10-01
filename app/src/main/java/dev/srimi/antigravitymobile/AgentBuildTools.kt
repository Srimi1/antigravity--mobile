package dev.srimi.antigravitymobile

import android.content.Context
import dev.srimi.antigravitymobile.runtime.*
import dev.srimi.antigravitymobile.runtime.BuildProtocol as P
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class PreparedBuild(val id: String, val tasks: String, val snapshotHash: String)
data class BuildOutcome(val id: String, val status: String, val detail: String, val durationMs: Long,
    val apks: List<String>, val outputTail: String, val logRef: String? = null) {
    fun report(): String = buildString {
        append("Build $id $status")
        if (durationMs > 0) append(" in ${durationMs / 1000}s")
        append(". $detail\n")
        if (apks.isNotEmpty()) append("Verified APKs: ${apks.joinToString()} (build_id $id)\n")
        if (outputTail.isNotBlank()) append("Recorded build log tail (not the full log):\n$outputTail")
    }
    fun toolOutcome(): ToolOutcome = when (status) {
        "COMPLETED" -> ToolOutcome.Success(report())
        "FAILED" -> ToolOutcome.Failed(report(), logRef)
        "CANCELLED", "DECLINED" -> ToolOutcome.Cancelled
        "INTERRUPTED" -> ToolOutcome.Interrupted
        else -> ToolOutcome.RuntimeUnavailable("Build $id is $status; outcome not confirmed. Never dispatch it again.")
    }
}

/** Recorded build access is project-scoped. Awaiting an existing build must never dispatch it. */
interface BuildRunner {
    fun unavailableReason(): String?
    suspend fun prepare(tasks: String): PreparedBuild
    suspend fun resolvePending(id: String, decision: ApprovalDecision)
    suspend fun runApproved(id: String): BuildOutcome
    suspend fun awaitExisting(id: String): BuildOutcome
    suspend fun recorded(id: String? = null, successfulOnly: Boolean = false): BuildOutcome?
    suspend fun install(buildId: String, apk: String?): String
}

class AgentBuildTools(private val files: ToolHost, private val runner: BuildRunner) : ToolHost {
    private val prepared = ConcurrentHashMap<String, PreparedBuild>()
    private val installTargets = ConcurrentHashMap<String, Pair<String, String>>()
    override val specs = files.specs + listOf(
        ToolSpec("build_project", "Build this project's approved immutable snapshot on the phone. Default :app:assembleDebug; " +
            ":app:testDebugUnitTest runs unit tests. Takes minutes; approval permits one build only. Returns the recorded worker outcome and log tail.",
            """{"type":"object","properties":{"tasks":{"type":"string"}},"additionalProperties":false}"""),
        ToolSpec("install_apk", "Open Android's installer for a verified APK from a successful build of this project, including earlier messages. " +
            "Requires separate approval. Opening the installer is not proof of installation or launch.",
            """{"type":"object","properties":{"build_id":{"type":"string"},"apk":{"type":"string"}},"additionalProperties":false}"""),
        ToolSpec("build_result", "Read an actual recorded worker outcome for this project. Latest build by default. Use for follow-up questions; never infer success from approval.",
            """{"type":"object","properties":{"build_id":{"type":"string"}},"additionalProperties":false}"""),
        ToolSpec("read_build_log", "Read the persisted worker log tail for this project's build, latest by default. This is a bounded tail, not a fabricated result or the full log.",
            """{"type":"object","properties":{"build_id":{"type":"string"}},"additionalProperties":false}"""),
    )
    override fun requiresApproval(name: String) = approvalCategory(name) != null
    override fun approvalCategory(name: String): ApprovalCategory? = when (name) {
        "build_project" -> ApprovalCategory.Build
        "install_apk" -> ApprovalCategory.Install
        "build_result", "read_build_log" -> null
        else -> files.approvalCategory(name)
    }
    private fun args(call: AgentItem.ToolCall) = JSONObject(call.arguments.ifBlank { "{}" })

    override suspend fun describe(call: AgentItem.ToolCall): ToolPreview = when (call.name) {
        "build_project" -> {
            runner.unavailableReason()?.let { throw RuntimeUnavailableException(it) }
            val tasks = args(call).optString("tasks").trim().ifEmpty { ":app:assembleDebug" }
            P.validateTasks(tasks.split(Regex("\\s+")))
            check(!prepared.containsKey(call.callId)) { "Build already prepared" }
            val build = runner.prepare(tasks)
            prepared[call.callId] = build
            ToolPreview("Build on phone: ${build.tasks}",
                "Build ID: ${build.id}\nGradle tasks: ${build.tasks}\nSnapshot SHA-256: ${build.snapshotHash}\n\n" +
                    "Build scripts run code and may download dependencies in the separate build tools app. This approval permits exactly one snapshot build; it never authorizes file edits or installation.", build.id)
        }
        "install_apk" -> {
            val build = target(call)
            val apk = apkName(call, build)
            installTargets[call.callId] = build.id to apk
            ToolPreview("Open installer for $apk from build ${build.id.take(8)}",
                "Confirm installation in Android's installer. Installer launch is recorded separately from installation success.", build.id)
        }
        "build_result", "read_build_log" -> ToolPreview("Read recorded ${call.name.replace('_', ' ')}")
        else -> files.describe(call)
    }
    override suspend fun resolve(call: AgentItem.ToolCall, decision: ApprovalDecision) {
        prepared.remove(call.callId)?.let { runner.resolvePending(it.id, decision) }
        installTargets.remove(call.callId)
        files.resolve(call, decision)
    }
    override suspend fun execute(call: AgentItem.ToolCall): ToolOutcome = when (call.name) {
        "build_project" -> {
            val build = prepared.remove(call.callId) ?: error("This build was not prepared for approval")
            runner.runApproved(build.id).toolOutcome()
        }
        "install_apk" -> {
            val (id, apk) = installTargets.remove(call.callId) ?: error("This installer action was not prepared for approval")
            ToolOutcome.Success(runner.install(id, apk))
        }
        "build_result", "read_build_log" -> {
            val build = runner.recorded(args(call).optString("build_id").ifBlank { null })
                ?: return ToolOutcome.Failed("No recorded build for this project")
            ToolOutcome.Success(if (call.name == "build_result") build.report() else
                "Build ${build.id} ${build.status}\nRecorded log tail:\n${build.outputTail.ifBlank { "No worker output was recorded." }}")
        }
        else -> files.execute(call)
    }
    private suspend fun target(call: AgentItem.ToolCall): BuildOutcome {
        val build = runner.recorded(args(call).optString("build_id").ifBlank { null }, successfulOnly = true)
            ?: error("No successful build with verified APKs for this project. Run build_project first.")
        require(build.status == "COMPLETED" && build.apks.isNotEmpty()) { "That build has no verified APK" }
        return build
    }
    private fun apkName(call: AgentItem.ToolCall, build: BuildOutcome): String = args(call).optString("apk")
        .takeIf { it.isNotEmpty() }?.also { require(it in build.apks) { "Unknown APK $it" } } ?: build.apks.first()
}

class PhoneBuildRunner(private val services: AppContainer, private val project: ProjectRecord, private val context: Context,
    private val taskId: String? = null) : BuildRunner {
    override fun unavailableReason(): String? = when {
        services.builds.client.installed() -> null
        services.builds.client.outdated() -> "Update build tools on the Build tab."
        else -> "Install build tools on the Build tab."
    }
    override suspend fun prepare(tasks: String) = services.builds.prepare(project, tasks, taskId).let { PreparedBuild(it.id, it.tasks, it.snapshotHash) }
    override suspend fun resolvePending(id: String, decision: ApprovalDecision) {
        services.builds.resolvePending(id, when (decision) {
            is ApprovalDecision.Declined -> "DECLINED"
            is ApprovalDecision.Cancelled -> "CANCELLED"
            is ApprovalDecision.Interrupted -> "INTERRUPTED"
            is ApprovalDecision.Approved -> return
        })
    }
    override suspend fun runApproved(id: String): BuildOutcome {
        services.builds.approve(id, taskId)
        try { return awaitExisting(id) }
        catch (cancelled: CancellationException) {
            withContext(NonCancellable) { services.builds.cancel(id) }
            throw cancelled
        }
    }
    override suspend fun awaitExisting(id: String): BuildOutcome {
        var record = services.builds.find(id) ?: error("Build record missing")
        require(record.projectId == project.id) { "Build belongs to another project" }
        while (record.status !in P.terminal && record.status != "DECLINED") {
            delay(500); record = services.builds.find(id) ?: error("Build record missing")
        }
        if (record.status == "COMPLETED" && record.artifactState == "PENDING") {
            services.builds.refresh(id)
            // Wait for the recorded transfer outcome, not an arbitrary grace period.
            while (record.artifactState == "PENDING") {
                delay(500); record = services.builds.find(id) ?: error("Build record missing")
            }
        }
        return outcome(record)
    }
    override suspend fun recorded(id: String?, successfulOnly: Boolean): BuildOutcome? {
        val records = services.database.builds().forProject(project.id)
        val record = if (id != null) records.firstOrNull { it.id == id }
            else records.firstOrNull { !successfulOnly || (it.status == "COMPLETED" && services.builds.artifacts(it.id).isNotEmpty()) }
        if (record == null || (successfulOnly && record.status != "COMPLETED")) return null
        return outcome(record).takeIf { !successfulOnly || it.apks.isNotEmpty() }
    }
    private suspend fun outcome(record: BuildRecord): BuildOutcome = BuildOutcome(record.id, record.status, record.detail,
        record.durationMs, if (record.status == "COMPLETED" && record.artifactState == "READY") services.builds.artifacts(record.id).map { it.name } else emptyList(),
        services.builds.log(record.id).takeLast(6000), services.builds.logReference(record.id))
    override suspend fun install(buildId: String, apk: String?): String {
        val build = services.builds.find(buildId) ?: error("Build record missing")
        require(build.projectId == project.id && build.status == "COMPLETED" && build.artifactState == "READY") { "Only a verified successful build from this project can be installed" }
        val file = services.builds.artifacts(buildId).firstOrNull { apk == null || it.name == apk } ?: error("Verified APK not found")
        val result = withContext(Dispatchers.Main) { ApkInstaller.launch(context) { file.inputStream() } }
        if (!result.opened) throw RuntimeUnavailableException(result.message)
        return result.message
    }
}
