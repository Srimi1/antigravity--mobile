package dev.srimi.antigravitymobile.linux

/** Fixed interactive commands: no user text is interpolated, and no login output is returned to the app. */
object LinuxTerminalCommands {
    const val PROOT = "${TermuxProtocol.PREFIX}/bin/proot-distro"
    private val environment = listOf("/usr/bin/env", "PATH=/opt/agm/node/bin:/root/.local/bin:/usr/local/bin:/usr/bin:/bin")

    private fun executable(tool: CliTool): String = when (tool) {
        CliTool.CODEX -> "/opt/agm/node/bin/codex"
        CliTool.ANTIGRAVITY -> "/root/.local/bin/agy"
        CliTool.GEMINI -> "/opt/agm/node/bin/gemini"
        CliTool.CLAUDE_CODE -> "/usr/bin/claude"
        CliTool.CUSTOM -> error("Select an installed official CLI")
    }

    fun open(tool: CliTool? = null): TermuxCommand {
        val program = when (tool) {
            null -> listOf("/bin/bash", "--login")
            CliTool.CODEX -> listOf(executable(tool), "login", "--device-auth")
            else -> listOf(executable(tool))
        }
        return TermuxCommand(PROOT, listOf("login", "agm-debian", "--") + environment + program,
            background = false, label = tool?.label ?: "Debian (Antigravity Mobile)")
    }

    fun preflight(tool: CliTool? = null): TermuxCommand {
        val check = if (tool == null) listOf("/bin/true") else listOf("/usr/bin/test", "-x", executable(tool))
        return TermuxCommand(PROOT, listOf("login", "agm-debian", "--") + check, timeoutMs = 30_000,
            label = "Check Debian terminal")
    }
}
