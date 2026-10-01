package dev.srimi.antigravitymobile

import android.content.Context
import dev.srimi.antigravitymobile.runtime.BuildProtocol as P
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class PreparedBuild(val id: String, val tasks: String, val snapshotHash: String)
data class BuildOutcome(val id: String, val status: String, val detail: String, val durationMs: Long, val apks: List<String>, val outputTail: String)

/** What the agent's build tools need from the phone. A fake implements it in JVM tests. */
interface BuildRunner {
    /** Null when builds can run; otherwise what the user must do first. */
    fun unavailableReason(): String?
    suspend fun prepare(tasks: String): PreparedBuild
    suspend fun decline(id: String)
    /** Starts an approved build and waits for its final state. Cancelling the coroutine asks the worker to stop. */
    suspend fun runApproved(id: String): BuildOutcome
    /** Opens Android's installer for an APK from a completed build. The user confirms there. */
    suspend fun install(buildId: String, apk: String?): String
}

/**
 * Adds on-phone Gradle build and APK install tools to the file tools. Both need the user's approval in the Agent
 * screen: a build is approved for one immutable snapshot (hash shown) and runs once; nothing is replayed.
 */
class AgentBuildTools(private val files: ToolHost, private val runner: BuildRunner) : ToolHost {
    private val prepared = ConcurrentHashMap<String, PreparedBuild>()
    @Volatile private var lastBuild: BuildOutcome? = null

    override val specs = files.specs + listOf(
        ToolSpec("build_project", "Build this Android Gradle project on the phone with the bundled toolchain (Java 17, Gradle 8.13, " +
            "Android SDK 36) after the user approves. Default task :app:assembleDebug; a unit-test task such as :app:testDebugUnitTest " +
            "also works. Takes several minutes. Returns status, APK names and the end of the build log.",
            """{"type":"object","properties":{"tasks":{"type":"string","description":"Space-separated Gradle tasks, e.g. :app:assembleDebug"}},"additionalProperties":false}"""),
        ToolSpec("install_apk", "Open Android's installer for an APK built by build_project in this task (the most recent successful " +
            "build by default). The user approves here and confirms in Android's installer.",
            """{"type":"object","properties":{"build_id":{"type":"string"},"apk":{"type":"string","description":"APK name from the build result"}},"additionalProperties":false}"""),
    )

    override fun requiresApproval(name: String) = name == "build_project" || name == "install_apk" || files.requiresApproval(name)

    private fun args(call: AgentItem.ToolCall) = runCatching { JSONObject(call.arguments.ifBlank { "{}" }) }.getOrDefault(JSONObject())

    override suspend fun describe(call: AgentItem.ToolCall): ToolPreview = when (call.name) {
        "build_project" -> {
            runner.unavailableReason()?.let { error(it) }
            val tasks = args(call).optString("tasks").trim().ifEmpty { ":app:assembleDebug" }
            P.validateTasks(tasks.split(Regex("\\s+")))
            val build = runner.prepare(tasks)
            prepared[call.callId] = build
            ToolPreview("Build on phone: ${build.tasks}",
                "Gradle tasks: ${build.tasks}\nSnapshot SHA-256: ${build.snapshotHash}\n\nThe build tools app runs these tasks on a copy " +
                    "of the project as it is now. Project build scripts can run code and download dependencies. It can take several minutes " +
                    "and runs once; it is never retried automatically.")
        }
        "install_apk" -> {
            val build = target(call)
            ToolPreview("Install ${apkName(call, build)} from build ${build.id.take(8)}",
                "Android's installer will open. Confirm there to install. Built in the build tools app with its own development signing key.")
        }
        else -> files.describe(call)
    }

    override suspend fun declined(call: AgentItem.ToolCall) {
        prepared.remove(call.callId)?.let { runner.decline(it.id) }
        files.declined(call)
    }

    override suspend fun execute(call: AgentItem.ToolCall): String = when (call.name) {
        "build_project" -> {
            val build = prepared.remove(call.callId) ?: error("This build was not prepared for approval")
            val outcome = runner.runApproved(build.id)
            if (outcome.status == "COMPLETED") lastBuild = outcome
            buildString {
                append("Build ${outcome.id.take(8)} ${outcome.status}")
                if (outcome.durationMs > 0) append(" in ${outcome.durationMs / 1000}s")
                append(". ${outcome.detail}\n")
                if (outcome.apks.isNotEmpty()) append("APKs: ${outcome.apks.joinToString()} (build_id ${outcome.id})\n")
                if (outcome.outputTail.isNotBlank()) append("End of build log:\n${outcome.outputTail}")
            }
        }
        "install_apk" -> { val build = target(call); runner.install(build.id, apkName(call, build)) }
        else -> files.execute(call)
    }

    private fun target(call: AgentItem.ToolCall): BuildOutcome {
        val requested = args(call).optString("build_id")
        val build = lastBuild ?: error("No successful build in this task yet. Run build_project first.")
        require(requested.isEmpty() || requested == build.id || requested == build.id.take(8)) { "Only the latest successful build in this task can be installed" }
        require(build.apks.isNotEmpty()) { "That build produced no APK. Build an assemble task such as :app:assembleDebug." }
        return build
    }
    private fun apkName(call: AgentItem.ToolCall, build: BuildOutcome): String =
        args(call).optString("apk").takeIf { it.isNotEmpty() }?.also { require(it in build.apks) { "Unknown APK $it" } } ?: build.apks.first()
}

/** The real runner: the approved companion build worker and Android's installer. */
class PhoneBuildRunner(private val services: AppContainer, private val project: ProjectRecord, private val context: Context) : BuildRunner {
    override fun unavailableReason(): String? = when {
        services.builds.client.installed() -> null
        services.builds.client.outdated() -> "The build tools need an update. Ask the user to tap Update build tools on the Build tab."
        else -> "The build tools are not installed. Ask the user to tap Install build tools on the Build tab, then try again."
    }
    override suspend fun prepare(tasks: String): PreparedBuild = services.builds.prepare(project, tasks).let { PreparedBuild(it.id, it.tasks, it.snapshotHash) }
    override suspend fun decline(id: String) { runCatching { services.builds.decline(id) } }
    override suspend fun runApproved(id: String): BuildOutcome {
        services.builds.approve(id)
        try {
            var record = services.builds.find(id) ?: error("Build record missing")
            while (record.status !in P.terminal && record.status != "DECLINED") {
                delay(2000); record = services.builds.find(id) ?: error("Build record missing")
            }
            if (record.status == "COMPLETED") {
                // The APK transfer finishes just after Gradle; give it a moment so the result lists the APKs.
                var waited = 0
                while (waited < 30 && services.builds.artifacts(id).isEmpty() && "APK" !in record.detail) {
                    delay(1000); waited++; record = services.builds.find(id) ?: record
                }
            }
            val output = services.builds.output.value[id].orEmpty()
            return BuildOutcome(id, record.status, record.detail, record.durationMs, services.builds.artifacts(id).map { it.name },
                output.takeLast(6000))
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { services.builds.cancel(id) }
            throw cancelled
        }
    }
    override suspend fun install(buildId: String, apk: String?): String {
        val file = services.builds.artifacts(buildId).firstOrNull { apk == null || it.name == apk } ?: error("APK not found for that build")
        return withContext(Dispatchers.Main) { ApkInstaller.install(context) { file.inputStream() } } +
            " Tell the user to confirm in Android's installer, then open the app from the launcher or the Build tab."
    }
}
