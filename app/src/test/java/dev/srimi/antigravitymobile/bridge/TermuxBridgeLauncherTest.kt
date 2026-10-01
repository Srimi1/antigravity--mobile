package dev.srimi.antigravitymobile.bridge

import dev.srimi.antigravitymobile.linux.TermuxCommand
import dev.srimi.antigravitymobile.linux.TermuxGateway
import dev.srimi.antigravitymobile.linux.TermuxResult
import dev.srimi.antigravitymobile.linux.TermuxStatus
import dev.srimi.antigravitymobile.runtime.AgentBackend
import dev.srimi.antigravitymobile.runtime.RuntimeUnavailableException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Runs the real Termux launcher script and bundled helper on the host. `proot-distro` and `timeout` are shims;
 * this proves stdin-only pairing and daemon reuse, not Termux, proot or ARM64 behaviour.
 */
class TermuxBridgeLauncherTest {
    private class HostTermux(private val home: File, private val path: String) : TermuxGateway {
        val commands = mutableListOf<TermuxCommand>()
        override fun status() = TermuxStatus(true, "fixture", true, true, false)
        override suspend fun run(command: TermuxCommand): TermuxResult {
            commands += command
            val process = ProcessBuilder(listOf(command.executable) + command.arguments).apply {
                environment()["HOME"] = home.path; environment()["PATH"] = path
            }.start()
            command.stdin?.let { input -> process.outputStream.bufferedWriter().use { it.write(input) } }
            check(process.waitFor(60, TimeUnit.SECONDS)) { "launcher did not return; detached daemon kept it open" }
            return TermuxResult(process.exitValue(), process.inputStream.bufferedReader().readText(),
                process.errorStream.bufferedReader().readText(), null, null, 0)
        }
    }

    @Test fun launchesPairedHelperOnceAndKeepsSecretOffArgvAndDisk() = runBlocking {
        val python = File("/usr/bin/python3")
        assumeTrue("host fixture requires /usr/bin/python3", python.canExecute())
        val root = Files.createTempDirectory("agm-launcher").toFile().canonicalFile
        val bin = File(root, "bin").apply { mkdirs() }
        // proot-distro login <name> -- /usr/bin/python3 ARGS -> host python3 ARGS
        File(bin, "proot-distro").apply { writeText("#!/bin/bash\nshift 3\nshift\nexec ${python.path} \"$@\"\n"); setExecutable(true) }
        File(bin, "timeout").apply { writeText("#!/bin/bash\nshift\nexec \"$@\"\n"); setExecutable(true) }
        val home = File(root, "home").apply { mkdirs() }
        val termux = HostTermux(home, "${bin.path}:/usr/bin:/bin")
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        val helperRoot = File(root, "bridge")
        var pid: Int? = null
        val launcher = TermuxBridgeLauncher(termux, { BridgePairing("fixture-pair", secret) },
            { mapOf("codex" to "/fixture/codex") }, helperRoot = helperRoot.path,
            prootDistro = File(bin, "proot-distro").path, shell = "/bin/bash")
        try {
            launcher.connect().use { connection ->
                assertEquals("NOT_FOUND", connection.call(JSONObject().put("op", "status").put("taskId", "task-1")).getString("state"))
            }
            pid = JSONObject(File(helperRoot, "endpoint.json").readText()).getInt("pid")
            val second = TermuxBridgeLauncher(termux, { BridgePairing("fixture-pair", secret) },
                { mapOf("codex" to "/fixture/codex") }, helperRoot = helperRoot.path,
                prootDistro = File(bin, "proot-distro").path, shell = "/bin/bash")
            second.connect().close()
            assertEquals("existing daemon reused", pid, JSONObject(File(helperRoot, "endpoint.json").readText()).getInt("pid"))
            // Gate evidence comes from the helper's probe; a host machine never opens a CLI backend.
            val gate = CliCapabilityGate(MemoryPreferences(), second)
            assertTrue(gate.unavailable(AgentBackend.Codex)!!.reason.contains("not verified"))
            val probe = gate.verify(AgentBackend.Codex)
            assertNull("fixture binary does not exist", probe.version)
            assertNotNull(gate.unavailable(AgentBackend.Codex))
            val encoded = BridgeSecurity.base64(secret)
            assertTrue(termux.commands.all { command -> command.arguments.none { encoded in it } })
            assertTrue(root.walkTopDown().filter { it.isFile && it.length() < 1_000_000 }.none { encoded in it.readText(Charsets.ISO_8859_1) })
            assertTrue("launch FIFOs removed", File(home, ".agm/bridge-launch").list().orEmpty().isEmpty())
            val stranger = TermuxBridgeLauncher(termux, { BridgePairing("other-pair", ByteArray(32).also(SecureRandom()::nextBytes)) },
                { mapOf("codex" to "/fixture/codex") }, helperRoot = helperRoot.path,
                prootDistro = File(bin, "proot-distro").path, shell = "/bin/bash")
            val refused = assertThrows(RuntimeUnavailableException::class.java) { runBlocking { stranger.connect().close() } }
            assertTrue(refused.message!!.contains("earlier pairing"))
        } finally {
            pid?.let { ProcessBuilder("kill", it.toString()).start().waitFor() }
            root.deleteRecursively()
        }
    }

    @Test fun noInstalledCliIsRuntimeUnavailable() = runBlocking {
        val launcher = TermuxBridgeLauncher(HostTermux(File("/tmp"), "/usr/bin:/bin"), { BridgePairing("fixture-pair", ByteArray(32)) }, { emptyMap() })
        val error = assertThrows(RuntimeUnavailableException::class.java) { runBlocking { launcher.connect() } }
        assertTrue(error.message!!.contains("Install Codex CLI"))
    }
}

/** Minimal in-memory preferences for JVM tests; only string reads and edits are used by the gate. */
class MemoryPreferences : android.content.SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    override fun getAll() = values.toMap()
    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = defValue
    override fun getLong(key: String, defValue: Long) = defValue
    override fun getFloat(key: String, defValue: Float) = defValue
    override fun getBoolean(key: String, defValue: Boolean) = defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = this
        override fun putInt(key: String, value: Int) = this
        override fun putLong(key: String, value: Long) = this
        override fun putFloat(key: String, value: Float) = this
        override fun putBoolean(key: String, value: Boolean) = this
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { values.clear() }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() { pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value } }
    }
}

class CliCapabilityGateTest {
    private fun gate(record: String?): CliCapabilityGate {
        val prefs = MemoryPreferences()
        val launcher = TermuxBridgeLauncher(object : TermuxGateway {
            override fun status() = TermuxStatus(false, null, false, null, false)
            override suspend fun run(command: TermuxCommand) = error("not used")
        }, { BridgePairing("fixture-pair", ByteArray(32)) }, { emptyMap() }, helperSource = "helper".toByteArray())
        record?.let { prefs.edit().putString("cliProbe.codex", it.replace("HASH", launcher.helperHash)).apply() }
        return CliCapabilityGate(prefs, launcher)
    }
    private fun record(machine: String = "aarch64", version: String = "codex-cli 0.159.3", sandbox: String = "confirmed", hash: String = "HASH") =
        JSONObject().put("backend", "codex").put("machine", machine).put("version", version).put("sandbox", sandbox).put("helperHash", hash).put("at", 1).toString()

    @Test fun opensOnlyForArm64VersionedConfirmedSandboxOfCurrentHelper() {
        assertNull(gate(record()).unavailable(AgentBackend.Codex))
        assertTrue(gate(null).unavailable(AgentBackend.Codex)!!.reason.contains("not verified"))
        assertTrue(gate(record(machine = "x86_64")).unavailable(AgentBackend.Codex)!!.reason.contains("ARM64"))
        assertTrue(gate(record(version = "")).unavailable(AgentBackend.Codex)!!.reason.contains("did not run"))
        assertTrue(gate(record(sandbox = "escaped")).unavailable(AgentBackend.Codex)!!.reason.contains("escaped"))
        assertTrue(gate(record(sandbox = "unavailable")).unavailable(AgentBackend.Codex)!!.reason.contains("stays disabled"))
        assertTrue(gate(record(hash = "0".repeat(64))).unavailable(AgentBackend.Codex)!!.reason.contains("verify"))
        assertNotNull("other backend unaffected", gate(record()).unavailable(AgentBackend.AntigravityCli))
    }
}
