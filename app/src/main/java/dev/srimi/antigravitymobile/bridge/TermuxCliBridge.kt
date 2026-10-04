package dev.srimi.antigravitymobile.bridge

import android.content.SharedPreferences
import dev.srimi.antigravitymobile.CredentialStore
import dev.srimi.antigravitymobile.linux.TermuxCommand
import dev.srimi.antigravitymobile.linux.TermuxGateway
import dev.srimi.antigravitymobile.linux.TermuxProtocol
import dev.srimi.antigravitymobile.linux.TermuxUnavailable
import dev.srimi.antigravitymobile.runtime.AgentBackend
import dev.srimi.antigravitymobile.runtime.RuntimeUnavailableException
import dev.srimi.antigravitymobile.runtime.ToolOutcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

internal fun AgentBackend.helperId(): String = when (this) {
    AgentBackend.Codex -> "codex"
    AgentBackend.AntigravityCli -> "antigravity"
    AgentBackend.Native -> throw IllegalArgumentException("Native backend has no CLI helper")
}

class BridgePairing(val id: String, val secret: ByteArray) {
    init { BridgeSecurity.checkPair(id); require(secret.size == 32) }
}

/** One pairing per app install. The secret stays in Keystore-encrypted storage and only travels on Termux stdin. */
class BridgePairingStore(private val store: CredentialStore) {
    @Synchronized fun current(): BridgePairing {
        store.read()?.let { value -> return BridgePairing(value.getString("pairId"), BridgeSecurity.unbase64(value.getString("secret"))) }
        val created = BridgePairing("pair-" + UUID.randomUUID().toString().replace("-", ""), ByteArray(32).also(SecureRandom()::nextBytes))
        store.save(JSONObject().put("pairId", created.id).put("secret", BridgeSecurity.base64(created.secret)))
        return created
    }
}

/**
 * Starts the paired helper inside `agm-debian` through the user's Termux and connects to it.
 * Argv carries only fixed launcher code; pairing material and the helper source travel on stdin.
 */
class TermuxBridgeLauncher(
    private val termux: TermuxGateway,
    private val pairing: () -> BridgePairing,
    private val installations: suspend () -> Map<String, String>,
    private val helperSource: ByteArray = defaultHelper(),
    private val connector: suspend (Int, String, ByteArray) -> PairedBridgeConnection = PairedBridgeConnection.Companion::connect,
    private val helperRoot: String = "/root/agm-work/bridge",
    private val prootDistro: String = PROOT_DISTRO,
    private val shell: String = TermuxProtocol.BASH,
) {
    companion object {
        const val PROOT_DISTRO = "${TermuxProtocol.PREFIX}/bin/proot-distro"
        fun defaultHelper(): ByteArray = TermuxBridgeLauncher::class.java
            .getResourceAsStream("/dev/srimi/antigravitymobile/bridge/agm_bridge.py")!!.use { it.readBytes() }
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /**
         * Runs inside Debian: verifies the helper bytes, then lets the helper install itself and either print the running
         * daemon's endpoint or exec into the daemon (which prints its own endpoint), keeping proot's first process alive.
         */
        internal const val LAUNCHER = """import base64,hashlib,json,sys,types
try:
    c=json.loads(sys.stdin.readline()); s=base64.b64decode(sys.stdin.readline().strip(),validate=True)
    if hashlib.sha256(s).hexdigest()!=c.get("helperHash"): raise ValueError()
    m=types.ModuleType("agm_bridge"); exec(compile(s,"agm_bridge.py","exec"),m.__dict__)
    print(json.dumps(m.bootstrap(c,s,sys.argv[1],sys.executable,True)),flush=True)
except Exception as e:
    print(json.dumps({"error":type(e).__name__}),flush=True)
"""

        /**
         * Runs in Termux. proot keeps the RUN_COMMAND open while any process inside lives, so the launcher is detached
         * (as Lane B's helper does) and stdin is handed over through private FIFOs that never hold data on disk.
         */
        internal const val SCRIPT = """set -eu
umask 077
dir="${'$'}HOME/.agm/bridge-launch"
mkdir -p "${'$'}dir"; chmod 700 "${'$'}dir"
in="${'$'}dir/in.${'$'}${'$'}"; out="${'$'}dir/out.${'$'}${'$'}"
rm -f "${'$'}in" "${'$'}out"
mkfifo -m 600 "${'$'}in" "${'$'}out"
trap 'rm -f "${'$'}in" "${'$'}out"' EXIT
exec 3<>"${'$'}out"
if command -v setsid >/dev/null 2>&1; then detach=setsid; else detach=; fi
${'$'}detach nohup "${'$'}2" login agm-debian -- /usr/bin/python3 -c "${'$'}1" "${'$'}3" < "${'$'}in" > "${'$'}out" 2>/dev/null 3<&- &
timeout 30 sh -c 'cat > "${'$'}0"' "${'$'}in" || { echo '{"error":"start"}'; exit 3; }
n=0
while [ ${'$'}n -lt 20 ] && IFS= read -r -t 45 line <&3; do
  case "${'$'}line" in "{"*) printf '%s\n' "${'$'}line"; exit 0 ;; esac
  n=${'$'}((n+1))
done
echo '{"error":"start"}'
exit 3
"""
    }
    private val mutex = Mutex()
    private var port: Int? = null
    /** Last validated loopback port; exposed for device tests of unpaired-client rejection. */
    val endpointPort: Int? get() = port
    val helperHash: String by lazy { sha256(helperSource) }

    suspend fun connect(): PairedBridgeConnection = mutex.withLock {
        val pair = pairing()
        port?.let { known ->
            // Any failure, including a stranger now owning the port, falls through to a validated relaunch.
            try { return connector(known, pair.id, pair.secret) } catch (_: Exception) { port = null }
        }
        val endpoint = launch(pair)
        connector(endpoint, pair.id, pair.secret).also { port = endpoint }
    }

    private suspend fun launch(pair: BridgePairing): Int {
        val installs = installations()
        if (installs.isEmpty()) throw RuntimeUnavailableException("Install Codex CLI or Antigravity CLI in Build › Linux setup first")
        val config = JSONObject().put("pairId", pair.id).put("secret", BridgeSecurity.base64(pair.secret))
            .put("helperHash", helperHash).put("installations", JSONObject(installs))
        val result = try {
            termux.run(TermuxCommand(shell, listOf("-c", SCRIPT, "agm-bridge", LAUNCHER, prootDistro, helperRoot),
                stdin = config.toString() + "\n" + Base64.getEncoder().encodeToString(helperSource) + "\n",
                timeoutMs = 120_000, label = "Antigravity Mobile CLI bridge"))
        } catch (unavailable: TermuxUnavailable) { throw RuntimeUnavailableException(unavailable.message ?: "Termux unavailable") }
        val line = result.stdout.lineSequence().firstOrNull { it.startsWith("{") }
            ?: throw RuntimeUnavailableException("CLI bridge did not start in Debian")
        val value = BridgeSecurity.json(line, 2048)
        if (value.has("error")) throw RuntimeUnavailableException(when (value.optString("error")) {
            "ProtocolError" -> "A CLI bridge from an earlier pairing or app version is running. Stop Linux in Build › Linux setup, then retry."
            else -> "CLI bridge did not start in Debian. Check that Debian 12 and python3 are installed (Build › Linux setup)."
        })
        BridgeSecurity.fields(value, setOf("pairId", "port", "pid", "helperHash"))
        if (value.requiredText("pairId", 64) != pair.id || value.requiredText("helperHash", 64) != helperHash) throw BridgeProtocolException()
        val endpoint = value.integer("port")
        if (endpoint !in 1024..65535) throw BridgeProtocolException()
        return endpoint.toInt()
    }
}

data class CliProbe(val backend: String, val machine: String, val version: String?, val sandbox: String,
    val helperHash: String, val at: Long, val environment: Long = 0)

/**
 * A CLI backend opens only on a device whose own probe proved ARM64 execution, a working CLI binary and a sandbox
 * that refuses writes outside its workspace. Evidence is per helper version and per Linux environment generation
 * (bumped when the app installs or removes Debian or CLIs); anything else is RuntimeUnavailable. A CLI updated by
 * hand inside Termux is not detected; verify again after doing that.
 */
class CliCapabilityGate(private val prefs: SharedPreferences, private val launcher: TermuxBridgeLauncher) {
    private fun key(backend: AgentBackend) = "cliProbe.${backend.helperId()}"
    private val environment: Long get() = prefs.getLong(ENVIRONMENT, 0)
    /** Call after the app installs, updates or removes Debian or a CLI: earlier probes no longer describe it. */
    fun environmentChanged() { prefs.edit().putLong(ENVIRONMENT, environment + 1).apply() }
    fun recorded(backend: AgentBackend): CliProbe? = prefs.getString(key(backend), null)?.let { raw ->
        runCatching { JSONObject(raw).let { CliProbe(it.getString("backend"), it.getString("machine"),
            it.optString("version").ifBlank { null }, it.getString("sandbox"), it.getString("helperHash"), it.getLong("at"), it.optLong("environment", 0)) } }.getOrNull()
    }
    fun unavailable(backend: AgentBackend): ToolOutcome.RuntimeUnavailable? {
        val label = if (backend == AgentBackend.Codex) "Codex CLI" else "Antigravity CLI"
        val probe = recorded(backend) ?: return ToolOutcome.RuntimeUnavailable("$label is not verified on this phone. Run Verify in Build › Local CLI agents.")
        return when {
            probe.helperHash != launcher.helperHash -> ToolOutcome.RuntimeUnavailable("App updated; verify $label again in Build › Local CLI agents.")
            probe.environment != environment -> ToolOutcome.RuntimeUnavailable("Debian or its CLIs changed since $label was verified; verify again in Build › Local CLI agents.")
            probe.machine != "aarch64" -> ToolOutcome.RuntimeUnavailable("$label needs ARM64 Debian; this device reported ${probe.machine}.")
            probe.version == null -> ToolOutcome.RuntimeUnavailable("$label did not run in Debian. Reinstall it in Build › Linux setup.")
            probe.sandbox == "unsupported" -> ToolOutcome.RuntimeUnavailable("$label has no verifiable headless sandbox yet; it stays disabled.")
            probe.sandbox != "confirmed" -> ToolOutcome.RuntimeUnavailable("$label sandbox check: ${probe.sandbox}. It stays disabled on this phone.")
            else -> null
        }
    }
    suspend fun verify(backend: AgentBackend, now: Long = System.currentTimeMillis()): CliProbe {
        val value = launcher.connect().use { it.call(JSONObject().put("op", "probe").put("backend", backend.helperId())) }
        BridgeSecurity.fields(value, setOf("backend", "machine", "version", "sandbox"))
        if (value.requiredText("backend", 32) != backend.helperId()) throw BridgeProtocolException()
        val sandbox = value.requiredText("sandbox", 32)
        if (sandbox !in setOf("confirmed", "escaped", "unavailable", "unsupported")) throw BridgeProtocolException()
        val probe = CliProbe(backend.helperId(), value.requiredText("machine", 32), value.nullableText("version", 120), sandbox, launcher.helperHash, now, environment)
        prefs.edit().putString(key(backend), JSONObject().put("backend", probe.backend).put("machine", probe.machine)
            .put("version", probe.version ?: "").put("sandbox", probe.sandbox).put("helperHash", probe.helperHash).put("at", probe.at).put("environment", probe.environment).toString()).apply()
        return probe
    }
    private companion object { const val ENVIRONMENT = "cliEnvironmentGeneration" }
}
