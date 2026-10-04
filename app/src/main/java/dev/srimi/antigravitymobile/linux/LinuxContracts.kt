package dev.srimi.antigravitymobile.linux

/*
 * Lane B Day 0 contract (frozen after Day 0; changes go through docs/lanes/REQUESTS.md).
 * The Linux userland runs inside the user's own Termux app (Debian 12 ARM64 via proot-distro, no root).
 * An existing Termux installation and its files are never removed or reset by this app.
 */

/**
 * What the app can see of Termux. [externalAppsAllowed] is null until a command has run, because Termux's
 * `allow-external-apps` property can only be observed through a RUN_COMMAND result.
 */
data class TermuxStatus(
    val installed: Boolean,
    val versionName: String?,
    /** Android granted `com.termux.permission.RUN_COMMAND` to this app. */
    val runCommandPermission: Boolean,
    val externalAppsAllowed: Boolean?,
    val x11Installed: Boolean,
    /**
     * False when Termux was installed after this app was installed or last updated. Android then never registered
     * the RUN_COMMAND permission for this app: it is missing from Settings and the dialog fails silently until this
     * app is updated or reinstalled.
     */
    val permissionRegistered: Boolean = true,
) {
    val ready: Boolean get() = installed && runCommandPermission && externalAppsAllowed != false
}

/**
 * A command for Termux's RUN_COMMAND service. Arguments are passed as a list, never joined into a shell string.
 * [executable] is an absolute path inside Termux, for example `/data/data/com.termux/files/usr/bin/bash`.
 */
data class TermuxCommand(
    val executable: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val stdin: String? = null,
    /** Run without opening a Termux terminal session. */
    val background: Boolean = true,
    val timeoutMs: Long = 120_000,
    /** Short label shown in Termux's notification. */
    val label: String? = null,
)

/**
 * Termux's reply. [exitCode] is null when the command never started; [errorCode]/[errorMessage] then carry
 * Termux's own `err`/`errmsg` (for example permission denied or external apps disabled). Output is truncated
 * by Termux to about 100 KB.
 */
data class TermuxResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val errorCode: Int?,
    val errorMessage: String?,
    val durationMs: Long,
) {
    val succeeded: Boolean get() = exitCode == 0 && (errorCode == null || errorCode == -1)
}

/** Why Termux cannot be used; shown as a runtime limitation, never as a user decision. */
sealed class TermuxUnavailable(message: String) : Exception(message) {
    class NotInstalled : TermuxUnavailable("Termux is not installed")
    class PermissionDenied : TermuxUnavailable("Antigravity Mobile is not allowed to run commands in Termux. Build → Linux on this phone → Allow, " +
        "or Android Settings → Apps → Antigravity Mobile → Permissions → Additional permissions → Run commands in Termux environment → Allow")
    class ExternalAppsDisabled : TermuxUnavailable("Termux is not accepting commands from other apps (allow-external-apps is off)")
    class TimedOut(val afterMs: Long) : TermuxUnavailable("Termux did not answer within ${afterMs / 1000}s")
    class Failed(message: String) : TermuxUnavailable(message)
}

interface TermuxGateway {
    fun status(): TermuxStatus
    /** Runs [command] and waits for Termux's result. Throws [TermuxUnavailable] when the command could not run. */
    suspend fun run(command: TermuxCommand): TermuxResult
}

enum class LinuxState { TermuxMissing, NotInstalled, Installing, Stopped, Starting, Running, Stopping, Failed }

data class LinuxStatus(
    val state: LinuxState,
    /** Plain-language detail, for example the failing step. */
    val detail: String,
    val distribution: String = "debian",
    val desktopInstalled: Boolean = false,
    val desktopRunning: Boolean = false,
)

/** Bytes used inside Termux by what this app installed. Null = could not be measured. */
data class StorageUsage(
    val distributionBytes: Long?,
    val packageCacheBytes: Long?,
    val cliBytes: Long?,
    val workspaceBytes: Long?,
    val measuredAt: Long,
) {
    val totalBytes: Long? get() = listOf(distributionBytes, packageCacheBytes, cliBytes, workspaceBytes)
        .takeIf { parts -> parts.all { it != null } }?.sumOf { it!! }
}

/**
 * What cleanup may remove. Only things this app installed: never Termux's own `$HOME` files, other proot
 * distributions, or Termux packages the user installed.
 */
enum class CleanupItem { PackageCache, Workspaces, CliInstalls, Desktop, Distribution }

data class CleanupResult(val removed: Set<CleanupItem>, val freedBytes: Long?, val failures: Map<CleanupItem, String>)

/**
 * Workspaces and Distribution delete `/root/agm-work`, which holds the CLI bridge's task journals, uploads,
 * copied workspaces and exports. They are refused while a CLI-backed task holds the task slot (running,
 * awaiting approval, paused or with an unconfirmed cancellation), so recovery state is never removed under it.
 */
object CleanupPolicy {
    val TOUCHES_CLI_STATE = setOf(CleanupItem.Workspaces, CleanupItem.Distribution)
    fun blockedReason(selection: Set<CleanupItem>, activeBackend: String?): String? =
        if (activeBackend == null || activeBackend == "Native" || selection.none { it in TOUCHES_CLI_STATE }) null
        else "A CLI agent task is still active or paused. Finish, cancel or discard it in Agent before removing project copies or the Debian container."
}

enum class CliTool(val id: String, val label: String) {
    CODEX("codex", "Codex CLI"),
    ANTIGRAVITY("antigravity", "Antigravity CLI"),
    GEMINI("gemini", "Gemini CLI"),
    CLAUDE_CODE("claude-code", "Claude Code"),
    CUSTOM("custom", "Custom executable"),
}

/**
 * An installed CLI inside the Debian userland. Sign-in happens inside the official client; this app never reads
 * its credentials. [signInVerifiedAt] is set only after a real inference completed through that client.
 */
data class CliInstall(
    val tool: CliTool,
    /** Absolute path inside the Debian root, for example `/usr/local/bin/codex`. Null when not installed. */
    val binaryPath: String?,
    val version: String?,
    /** For [CliTool.CUSTOM]: the user's profile name. */
    val profileName: String? = null,
    val signInVerifiedAt: Long? = null,
) {
    val installed: Boolean get() = binaryPath != null
}

interface LinuxRuntime {
    suspend fun status(): LinuxStatus
    /** Installs proot-distro and Debian 12 if missing; idempotent. [onProgress] gets short step names. */
    suspend fun ensureInstalled(desktop: Boolean, onProgress: (String) -> Unit = {}): LinuxStatus
    suspend fun start(desktop: Boolean): LinuxStatus
    suspend fun stop(): LinuxStatus
    suspend fun storageUsage(): StorageUsage
    suspend fun cleanup(selection: Set<CleanupItem>): CleanupResult
    suspend fun installedClis(): List<CliInstall>
    suspend fun installCli(tool: CliTool, onProgress: (String) -> Unit = {}): CliInstall
}

/**
 * What the Allow button does for Termux's RUN_COMMAND runtime permission. Once Android stops showing its dialog
 * (two denials or a dismissed dialog mark it user-fixed), requesting again returns at once with no UI, so the only
 * working path is the app's Settings page (Permissions → Additional permissions).
 */
enum class TermuxPermissionStep {
    Done, RequestDialog, OpenSettings, Explain, UpdateApp;

    companion object {
        /** Android registers another app's custom permission for this app only if that app was installed first. */
        fun registered(termuxFirstInstall: Long, appLastUpdate: Long): Boolean = termuxFirstInstall <= appLastUpdate

        fun onAllow(granted: Boolean, askedBefore: Boolean, showRationale: Boolean, registered: Boolean = true): TermuxPermissionStep = when {
            granted -> Done
            !registered -> UpdateApp
            !askedBefore || showRationale -> RequestDialog
            else -> OpenSettings
        }
        fun afterResult(granted: Boolean, showRationale: Boolean): TermuxPermissionStep = when {
            granted -> Done
            showRationale -> Explain
            else -> OpenSettings
        }
    }
}
