package dev.srimi.antigravitymobile.bridge

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import dev.srimi.antigravitymobile.runtime.AgentBackend
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
    private suspend fun withHelper(test: suspend (File, Int, ByteArray) -> Unit) {
        val python = File("/usr/bin/python3")
        assumeTrue("Python stdlib interop fixture requires /usr/bin/python3", python.canExecute())
        // Unix-domain socket paths have a short platform limit, including macOS's /private prefix.
        val root = Files.createTempDirectory(File("/tmp").toPath(), "agm-bridge-interop").toFile().canonicalFile
        val helper = File(root, "helper.py")
        javaClass.getResourceAsStream("/dev/srimi/antigravitymobile/bridge/agm_bridge.py")!!.use { input -> helper.outputStream().use(input::copyTo) }
        val script = """
            import importlib.util,json,sys
            config=json.loads(sys.stdin.readline())
            spec=importlib.util.spec_from_file_location('agm_bridge',config['helper'])
            module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
            import pathlib
            binary=pathlib.Path(config['root']).parent/'fixture-cli'
            binary.write_text('#!/bin/sh\nexec sleep 30\n'); binary.chmod(0o700)
            supervisor=module.TaskSupervisor(config['root'],{'codex':str(binary)})
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
            test(root, port, key)
        } finally {
            process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS)
            reader.shutdownNow(); root.deleteRecursively()
        }
    }

    @Test fun authenticatedKotlinClientTalksToBundledPythonHelper() = runBlocking {
        withHelper { root, port, key ->
            PairedBridgeConnection.connect(port, "fixture-pair", key).use { connection ->
                val status = connection.call(JSONObject().put("op", "status").put("taskId", "task-1"))
                assertEquals("NOT_FOUND", status.getString("state"))
                assertEquals(0, status.getInt("events"))
            }
            assertThrows(Exception::class.java) {
                runBlocking { PairedBridgeConnection.connect(port, "fixture-pair", ByteArray(32).also(SecureRandom()::nextBytes)).close() }
            }
            assertTrue("failed authentication must not dispatch", File(root, "runtime").listFiles().orEmpty().none { it.name.startsWith("task-") })
            val store = CliWorkspaceStore(File(root, "native-snapshots"))
            val project = File(root, "project").apply { mkdirs() }
            File(project, "Game.kt").writeText("before")
            val endpoint = PairedCliBridge({ PairedBridgeConnection.connect(port, "fixture-pair", key) }, { null }, File(root, "runtime").canonicalPath)
            val source = endpoint.prepare(store.create("task-2", "project-1", project))
            File(source, "Game.kt").writeText("CLI fixture edit")
            val changes = store.differences("task-2", endpoint.capture("task-2", File(root, "returned.zip")))
            assertEquals("CLI fixture edit", String(changes.single().after!!))
            assertEquals("before", File(project, "Game.kt").readText())
        }
    }

    @Test fun nativeMcpRequestsReachKotlinAndCanBeAnswered() = runBlocking {
        withHelper { root, port, key ->
            val runtime = File(root, "runtime").canonicalFile
            val endpoint = PairedCliBridge({ PairedBridgeConnection.connect(port, "fixture-pair", key) }, { null }, runtime.path)
            val project = File(root, "project").apply { mkdirs() }
            File(project, "Game.kt").writeText("source")
            endpoint.prepare(CliWorkspaceStore(File(root, "snapshots")).create("task-native", "project-1", project))
            assertTrue(endpoint.start("task-native", AgentBackend.Codex, null))
            val request = """
                import socket,sys
                with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as connection:
                    connection.connect(sys.argv[1])
                    connection.sendall(b'{"tool":"build_project","arguments":{"tasks":":app:assembleDebug"}}\n')
                    print(connection.makefile('r').readline(),flush=True)
            """.trimIndent()
            val client = ProcessBuilder("/usr/bin/python3", "-u", "-c", request, File(runtime, "mcp/task-native.sock").path).start()
            try {
                val event = withTimeout(5_000) {
                    var page = endpoint.observe("task-native", 0)
                    while (page.events.isEmpty()) { delay(20); page = endpoint.observe("task-native", 0) }
                    page.events.single()
                }
                assertEquals("native_request", event.getString("kind"))
                assertEquals("build_project", event.getString("tool"))
                assertTrue(endpoint.nativeAnswer("task-native", event.getLong("requestId"), "fixture build failed", true))
                assertTrue(client.waitFor(5, TimeUnit.SECONDS))
                assertEquals(0, client.exitValue())
                val answer = JSONObject(client.inputStream.bufferedReader().readLine())
                assertEquals("fixture build failed", answer.getString("text"))
                assertTrue(answer.getBoolean("isError"))
            } finally {
                client.destroyForcibly(); client.waitFor(3, TimeUnit.SECONDS)
                endpoint.cancel("task-native")
            }
        }
    }

    @Test fun retryAfterCommittedUploadReusesWorkspaceWithoutReplacingFiles() = runBlocking {
        withHelper { root, port, key ->
            val runtime = File(root, "runtime").canonicalFile
            fun endpoint() = PairedCliBridge({ PairedBridgeConnection.connect(port, "fixture-pair", key) }, { null }, runtime.path)
            val project = File(root, "project").apply { mkdirs() }
            File(project, "Game.kt").writeText("before")
            val snapshot = CliWorkspaceStore(File(root, "snapshots")).create("task-upload", "project-1", project)
            val cwd = endpoint().prepare(snapshot)
            val source = File(cwd, "Game.kt")
            source.writeText("private edit retained")
            val modified = source.lastModified()
            // A new client models recovery before the native runner persisted the Start checkpoint.
            assertEquals(cwd, endpoint().prepare(snapshot))
            assertEquals("private edit retained", source.readText())
            assertEquals(modified, source.lastModified())
            assertEquals("before", File(project, "Game.kt").readText())
            assertThrows(Exception::class.java) {
                runBlocking { endpoint().prepare(snapshot.copy(archiveHash = "0".repeat(64))) }
            }
        }
    }
}
