package dev.srimi.antigravitymobile.linux

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GoogleCliCommandsTest {
    @Test fun failedPreflightNeverDispatchesAnInteractiveLogin() = runBlocking {
        val commands = mutableListOf<TermuxCommand>()
        val gateway = gateway(commands) { TermuxResult(1, "", "bootstrap missing", -1, null, 1) }
        assertTrue(runCatching { GoogleCliCommands.open(gateway) }.exceptionOrNull() is TermuxUnavailable.Failed)
        assertEquals(1, commands.size)
        assertTrue(commands.single().background)
    }

    @Test fun externalAppDenialNeverDispatchesDownloadOrLogin() = runBlocking {
        val commands = mutableListOf<TermuxCommand>()
        val gateway = gateway(commands) { throw TermuxUnavailable.ExternalAppsDisabled() }
        assertTrue(runCatching { GoogleCliCommands.open(gateway) }.exceptionOrNull() is TermuxUnavailable.ExternalAppsDisabled)
        assertEquals(1, commands.size)
    }

    @Test fun signInOutputStaysInOneInteractiveTermuxSession() = runBlocking {
        val commands = mutableListOf<TermuxCommand>()
        GoogleCliCommands.open(gateway(commands) { TermuxResult(0, "", "", -1, null, 1) })
        assertEquals(2, commands.size)
        val login = commands.last()
        assertFalse(login.background)
        assertNull(login.stdin)
        assertEquals(TermuxProtocol.BASH, login.executable)
        assertEquals(GoogleCliCommands.ROOT, login.arguments.last())
        assertFalse(login.arguments.joinToString().contains("API_KEY"))
    }

    @Test fun anExistingCliIsReopenedWithoutDownloadingOrOverwritingIt() {
        shellFixture { root, tools, marker ->
            val binary = File(root, "bin/agy").apply { parentFile.mkdirs(); writeText("#!/bin/sh\nprintf 'opened' > \"\$OPEN_MARKER\"\n"); setExecutable(true) }
            val before = binary.readBytes()
            File(tools, "curl").apply { writeText("#!/bin/sh\nexit 91\n"); setExecutable(true) }
            assertEquals(0, runScript(root, tools, marker))
            assertEquals("opened", marker.readText())
            assertArrayEquals(before, binary.readBytes())
        }
    }

    @Test fun aDownloadFailureDoesNotLaunchAnythingOrLeaveAnInstaller() {
        shellFixture { root, tools, marker ->
            File(tools, "curl").apply { writeText("#!/bin/sh\nexit 91\n"); setExecutable(true) }
            assertEquals(91, runScript(root, tools, marker))
            assertFalse(marker.exists())
            assertFalse(File(root, "bin/agy").exists())
            assertTrue(root.listFiles()!!.none { it.name.startsWith("install.") })
        }
    }

    private fun gateway(commands: MutableList<TermuxCommand>, answer: (TermuxCommand) -> TermuxResult) = object : TermuxGateway {
        override fun status() = TermuxStatus(true, "fixture", true, true, false)
        override suspend fun run(command: TermuxCommand): TermuxResult { commands += command; return answer(command) }
    }
    private fun shellFixture(block: (File, File, File) -> Unit) {
        val dir = Files.createTempDirectory("agm-google-cli-test").toFile()
        try { block(File(dir, "owned-cli"), File(dir, "tools").apply { mkdirs() }, File(dir, "opened")) }
        finally { dir.deleteRecursively() }
    }
    private fun runScript(root: File, tools: File, marker: File): Int {
        val builder = ProcessBuilder("/bin/bash", "-c", GoogleCliCommands.script, "fixture", root.path).redirectErrorStream(true)
        builder.environment()["PATH"] = "${tools.path}:${System.getenv("PATH") ?: "/usr/bin:/bin"}"
        builder.environment()["OPEN_MARKER"] = marker.path
        val process = builder.start()
        process.inputStream.bufferedReader().use { it.readText() }
        return process.waitFor()
    }
}
