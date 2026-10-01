package dev.srimi.antigravitymobile.bridge

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercises the bundled Python server and Kotlin client together; no CLI, credentials or inference. */
class BridgeInteropTest {
    @Test fun authenticatedKotlinClientTalksToBundledPythonHelper() = runBlocking {
        val python = File("/usr/bin/python3")
        assumeTrue("Python stdlib interop fixture requires /usr/bin/python3", python.canExecute())
        val root = Files.createTempDirectory("agm-bridge-interop").toFile()
        val helper = File(root, "helper.py")
        javaClass.getResourceAsStream("/dev/srimi/antigravitymobile/bridge/agm_bridge.py")!!.use { input -> helper.outputStream().use(input::copyTo) }
        val script = """
            import importlib.util,json,sys
            config=json.loads(sys.stdin.readline())
            spec=importlib.util.spec_from_file_location('agm_bridge',config['helper'])
            module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
            supervisor=module.TaskSupervisor(config['root'],{})
            server=module.LoopbackServer(0,config['pair'],bytes.fromhex(config['key']),supervisor)
            print(server.server_address[1],flush=True)
            server.serve_forever()
        """.trimIndent()
        val process = ProcessBuilder(python.path, "-u", "-c", script).start()
        val reader = Executors.newSingleThreadExecutor()
        try {
            val key = ByteArray(32).also(SecureRandom()::nextBytes)
            process.outputStream.bufferedWriter().use { output ->
                output.appendLine(JSONObject().put("helper", helper.path).put("root", File(root, "runtime").path)
                    .put("pair", "fixture-pair").put("key", key.joinToString("") { "%02x".format(it) }).toString())
            }
            val port = reader.submit<String> { process.inputStream.bufferedReader().readLine() }.get(5, TimeUnit.SECONDS).toInt()
            PairedBridgeConnection.connect(port, "fixture-pair", key).use { connection ->
                val status = connection.call(JSONObject().put("op", "status").put("taskId", "task-1"))
                assertEquals("NOT_FOUND", status.getString("state"))
                assertEquals(0, status.getInt("events"))
            }
            assertThrows(Exception::class.java) {
                runBlocking { PairedBridgeConnection.connect(port, "fixture-pair", ByteArray(32).also(SecureRandom()::nextBytes)).close() }
            }
            assertTrue("failed authentication must not dispatch", File(root, "runtime").listFiles().orEmpty().isEmpty())
        } finally {
            process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS)
            reader.shutdownNow(); root.deleteRecursively()
        }
    }
}
