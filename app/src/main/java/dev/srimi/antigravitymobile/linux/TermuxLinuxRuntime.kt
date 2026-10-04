package dev.srimi.antigravitymobile.linux

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay

/** Pure interpretation of the helper's output, testable on the JVM. */
object LinuxHelperOutput {
    const val HELPER = "${TermuxProtocol.HOME}/.agm/agm-linux.sh"

    fun status(kv: List<Pair<String, String>>, termux: TermuxStatus): LinuxStatus {
        val map = kv.toMap()
        if (!termux.installed) return LinuxStatus(LinuxState.TermuxMissing, "Install Termux first (F-Droid or GitHub), then come back.")
        val desktop = map["desktop"] == "installed"
        val install = map["job_install"]
        return when {
            install == "running" -> LinuxStatus(LinuxState.Installing, "Installing Debian 12…", desktopInstalled = desktop)
            install?.startsWith("failed:") == true ->
                LinuxStatus(LinuxState.Failed, failure(install.removePrefix("failed:")), desktopInstalled = desktop)
            install == "interrupted" -> LinuxStatus(LinuxState.Failed,
                "The last install was interrupted. Install again to continue.", desktopInstalled = desktop)
            map["proot_distro"] == "legacy" && map["distribution"] != "installed" ->
                LinuxStatus(LinuxState.Failed, failure("proot-distro-too-old"))
            map["distribution"] != "installed" -> LinuxStatus(LinuxState.NotInstalled, "Debian 12 is not installed.")
            map["desktop_running"] == "yes" -> LinuxStatus(LinuxState.Running, "Debian ${map["debian_version"]} with the XFCE desktop is running.",
                desktopInstalled = desktop, desktopRunning = true)
            else -> LinuxStatus(LinuxState.Stopped, "Debian ${map["debian_version"] ?: "12"} is installed" + if (desktop) " with the XFCE desktop." else ".",
                desktopInstalled = desktop)
        }
    }

    fun failure(code: String): String = when (code) {
        "proot-distro-too-old" -> "Termux's proot-distro is too old for pinned Debian 12. In Termux run: pkg upgrade proot-distro (the app does not change your Termux packages on its own)."
        "proot-distro" -> "Installing proot-distro in Termux failed. Check Termux's internet access and run pkg update in Termux."
        "debian" -> "Downloading Debian 12 failed. Check the network and storage space, then install again."
        "not-debian-12" -> "The downloaded image was not Debian 12; nothing else was changed."
        "base-packages" -> "Installing base packages inside Debian failed. Install again to retry."
        "termux-x11" -> "Installing Termux:X11 support in Termux failed."
        "xfce" -> "Installing the XFCE desktop inside Debian failed."
        "node" -> "Downloading Node.js failed or its checksum did not match."
        "npm" -> "npm could not install the CLI."
        "claude-code", "antigravity" -> "The official installer failed."
        else -> "Failed ($code)."
    }

    fun storage(kv: List<Pair<String, String>>, now: Long): StorageUsage {
        val map = kv.toMap()
        fun bytes(key: String) = map[key]?.toLongOrNull()?.times(1024)
        return StorageUsage(bytes("distribution_kb"), bytes("package_cache_kb"), bytes("cli_kb"), bytes("workspace_kb"), now)
    }

    fun clis(kv: List<Pair<String, String>>): List<CliInstall> {
        val map = kv.toMap()
        return CliTool.entries.filter { it != CliTool.CUSTOM }.map { tool ->
            val path = map["cli_${tool.id}"]?.takeIf { it.startsWith("/") }
            CliInstall(tool, path, map["version_${tool.id}"]?.takeIf { path != null && it.isNotBlank() }?.take(80))
        }
    }

    fun cleanupArg(item: CleanupItem): String = when (item) {
        CleanupItem.PackageCache -> "package-cache"
        CleanupItem.Workspaces -> "workspaces"
        CleanupItem.CliInstalls -> "cli-installs"
        CleanupItem.Desktop -> "desktop"
        CleanupItem.Distribution -> "distribution"
    }

    fun cleanup(kv: List<Pair<String, String>>, selection: Set<CleanupItem>): CleanupResult {
        val removed = kv.filter { it.first == "removed" }.map { it.second }.toSet()
        val byArg = selection.associateBy(::cleanupArg)
        return CleanupResult(byArg.filterKeys { it in removed }.values.toSet(), null,
            byArg.filterKeys { it !in removed }.values.associateWith { "Not removed" })
    }
}

/**
 * Debian 12 (proot-distro container `agm-debian`) inside the user's Termux. The helper script is copied into
 * Termux before each use, so app updates also update it. Long installs run detached in Termux and are polled.
 */
class TermuxLinuxRuntime(context: Context, private val termux: TermuxGateway = AndroidTermuxGateway(context)) : LinuxRuntime {
    private val app = context.applicationContext
    private val script: String by lazy {
        javaClass.getResourceAsStream("/dev/srimi/antigravitymobile/linux/agm-linux.sh")!!.bufferedReader().use { it.readText() }
    }

    private suspend fun installHelper() {
        val result = termux.run(TermuxCommand(TermuxProtocol.BASH, listOf("-c", "mkdir -p \"\$HOME/.agm\" && cat > \"\$HOME/.agm/agm-linux.sh\""),
            stdin = script, timeoutMs = 30_000, label = "Antigravity Mobile setup"))
        if (result.exitCode != 0) throw TermuxUnavailable.Failed("Could not prepare the helper in Termux (exit ${result.exitCode})")
    }

    private suspend fun helper(vararg args: String, timeoutMs: Long = 60_000): List<Pair<String, String>> {
        installHelper()
        val result = termux.run(TermuxCommand(TermuxProtocol.BASH, listOf(LinuxHelperOutput.HELPER) + args, timeoutMs = timeoutMs,
            label = "Antigravity Mobile: ${args.first()}"))
        val kv = TermuxProtocol.keyValues(result.stdout)
        kv.firstOrNull { it.first == "error" }?.let { throw TermuxUnavailable.Failed(it.second.take(200)) }
        if (result.exitCode != 0) throw TermuxUnavailable.Failed("Helper exited with ${result.exitCode}: ${result.stderr.lineSequence().lastOrNull { it.isNotBlank() }?.take(200).orEmpty()}")
        return kv
    }

    override suspend fun status(): LinuxStatus {
        val t = termux.status()
        if (!t.installed) return LinuxHelperOutput.status(emptyList(), t)
        if (!t.runCommandPermission) return LinuxStatus(LinuxState.Failed, "Allow Antigravity Mobile to run commands in Termux (permission step below).")
        return LinuxHelperOutput.status(helper("status"), t)
    }

    private suspend fun poll(job: String, onProgress: (String) -> Unit): String {
        val deadline = System.currentTimeMillis() + 90 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            val kv = helper("progress", job)
            kv.lastOrNull { it.first == "step" }?.let { onProgress(it.second) }
            val state = kv.firstOrNull { it.first == "state" }?.second ?: "idle"
            if (state != "running") return state
            delay(3_000)
        }
        return "running"
    }

    override suspend fun ensureInstalled(desktop: Boolean, onProgress: (String) -> Unit): LinuxStatus {
        helper("install", if (desktop) "desktop" else "shell")
        val state = poll("install", onProgress)
        return when {
            state == "done" -> status()
            state.startsWith("failed:") -> LinuxStatus(LinuxState.Failed, LinuxHelperOutput.failure(state.removePrefix("failed:")))
            state == "running" -> LinuxStatus(LinuxState.Installing, "Still installing in Termux; check again later.")
            else -> LinuxStatus(LinuxState.Failed, "The install stopped ($state). Install again to continue.")
        }
    }

    override suspend fun start(desktop: Boolean): LinuxStatus {
        helper("start", if (desktop) "desktop" else "shell")
        if (desktop) runCatching {
            app.startActivity(Intent().setClassName(TermuxProtocol.X11_PACKAGE, TermuxProtocol.X11_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return status()
    }

    override suspend fun stop(): LinuxStatus { helper("stop"); return status() }

    override suspend fun storageUsage(): StorageUsage = LinuxHelperOutput.storage(helper("storage"), System.currentTimeMillis())

    override suspend fun cleanup(selection: Set<CleanupItem>): CleanupResult {
        if (selection.isEmpty()) return CleanupResult(emptySet(), 0, emptyMap())
        val before = runCatching { storageUsage().totalBytes }.getOrNull()
        val result = LinuxHelperOutput.cleanup(helper("cleanup", *selection.map(LinuxHelperOutput::cleanupArg).toTypedArray(), timeoutMs = 10 * 60_000), selection)
        val after = if (CleanupItem.Distribution in result.removed) 0 else runCatching { storageUsage().totalBytes }.getOrNull()
        return result.copy(freedBytes = if (before != null && after != null) (before - after).coerceAtLeast(0) else null)
    }

    override suspend fun installedClis(): List<CliInstall> = LinuxHelperOutput.clis(helper("clis", timeoutMs = 120_000))

    override suspend fun installCli(tool: CliTool, onProgress: (String) -> Unit): CliInstall {
        require(tool != CliTool.CUSTOM) { "Custom executables are configured, not installed" }
        helper("install-cli", tool.id)
        val state = poll("cli-${tool.id}", onProgress)
        if (state != "done") throw TermuxUnavailable.Failed(
            if (state.startsWith("failed:")) LinuxHelperOutput.failure(state.removePrefix("failed:")) else "Install did not finish ($state)")
        return installedClis().first { it.tool == tool }
    }

    /** Opens a Termux terminal already inside Debian, for the official CLI's own sign-in. */
    suspend fun openTerminal(tool: CliTool? = null): TermuxResult {
        // Unlike an interactive session, this returns an immediate result for permission, settings,
        // missing Debian and missing executables. Never claim the terminal opened after a failed check.
        val ready = termux.run(LinuxTerminalCommands.preflight(tool))
        if (!ready.succeeded) throw TermuxUnavailable.Failed(
            "Could not start ${tool?.label ?: "Debian"}. Install it in Build/Accounts first. " +
                ready.stderr.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty().take(160))
        return termux.run(LinuxTerminalCommands.open(tool))
    }
}
