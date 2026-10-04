package dev.srimi.antigravitymobile.linux

/** Google's installer selects its Android/Bionic ARM64 build in Termux and checks the upstream SHA-512. */
object GoogleCliCommands {
    const val ROOT = "${TermuxProtocol.HOME}/.agm/google-cli"
    const val BINARY = "$ROOT/bin/agy"
    // An interactive session owns downloads, browser sign-in and all credential output. Nothing is piped back.
    val script = """
        set -eu
        mkdir -p "${'$'}1/bin"
        chmod 700 "${'$'}1" "${'$'}1/bin"
        cd "${'$'}1"
        if [ ! -x "${'$'}1/bin/agy" ]; then
          printf 'Installing the official Google CLI for Android. Keep Termux open.\n'
          installer="${'$'}(mktemp "${'$'}1/install.XXXXXX")"
          trap 'rm -f "${'$'}installer"' EXIT
          curl --fail --show-error --location --proto '=https' https://antigravity.google/cli/install.sh -o "${'$'}installer"
          bash "${'$'}installer" --dir "${'$'}1/bin"
          rm -f "${'$'}installer"
          trap - EXIT
        fi
        exec "${'$'}1/bin/agy"
    """.trimIndent()

    fun open() = TermuxCommand(TermuxProtocol.BASH, listOf("-c", script, "agm-google-cli", ROOT),
        workingDirectory = TermuxProtocol.HOME, background = false, label = "Google sign-in (Gemini)")

    suspend fun open(termux: TermuxGateway): TermuxResult {
        // This checks both Android's permission and Termux's allow-external-apps policy before dispatch.
        val ready = termux.run(TermuxCommand(TermuxProtocol.BASH, listOf("-c", "exit 0"), timeoutMs = 15_000))
        if (!ready.succeeded) throw TermuxUnavailable.Failed("Termux could not start. Open Termux and finish its initial setup, then retry.")
        return termux.run(open())
    }
}
