package dev.srimi.antigravitymobile.linux

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Talks to a real Termux on the test device. The expected outcome is chosen with the instrumentation argument
 * `termuxCase`: `denied` (permission not granted), `external-off` (allow-external-apps unset) or `ready`.
 * Skipped when Termux is not installed. Never run against the owner's phone without the phone lock.
 */
@RunWith(AndroidJUnit4::class)
class TermuxDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val case = InstrumentationRegistry.getArguments().getString("termuxCase").orEmpty()
    private val gateway = AndroidTermuxGateway(context)

    @Test fun realTermuxBehavesAsExpected() = runBlocking {
        val status = gateway.status()
        assumeTrue("Termux not installed", status.installed)
        val echo = TermuxCommand(TermuxProtocol.BASH, listOf("-c", "echo out; echo err >&2; exit 3"), timeoutMs = 30_000)
        when (case) {
            "denied" -> {
                assertFalse(status.runCommandPermission)
                assertTrue(runCatching { gateway.run(echo) }.exceptionOrNull() is TermuxUnavailable.PermissionDenied)
                assertEquals(LinuxState.Failed, TermuxLinuxRuntime(context, gateway).status().state)
            }
            "external-off" -> {
                assertTrue(status.runCommandPermission)
                assertTrue(runCatching { gateway.run(echo) }.exceptionOrNull() is TermuxUnavailable.ExternalAppsDisabled)
                assertEquals(false, gateway.status().externalAppsAllowed)
            }
            "ready" -> {
                val result = gateway.run(echo)
                assertEquals(3, result.exitCode); assertEquals("out", result.stdout.trim()); assertEquals("err", result.stderr.trim())
                val stdin = gateway.run(TermuxCommand(TermuxProtocol.BASH, listOf("-c", "cat"), stdin = "a\nb", timeoutMs = 30_000))
                assertEquals("a\nb", stdin.stdout.trimEnd())
                assertEquals(true, gateway.status().externalAppsAllowed)
                val linux = TermuxLinuxRuntime(context, gateway).status()
                assertTrue(linux.detail, linux.state == LinuxState.NotInstalled || linux.state == LinuxState.Stopped || linux.state == LinuxState.Running)
            }
            "install" -> {
                val runtime = TermuxLinuxRuntime(context, gateway)
                val steps = mutableListOf<String>()
                val installed = runtime.ensureInstalled(false) { steps += it }
                android.util.Log.i("TermuxDeviceTest", "steps=$steps detail=${installed.detail}")
                assertEquals(installed.detail, LinuxState.Stopped, installed.state)
                assertTrue(installed.detail, installed.detail.contains("Debian 12"))
                assertEquals(LinuxState.Stopped, runtime.start(false).state)
                val usage = runtime.storageUsage()
                assertTrue((usage.distributionBytes ?: 0) > 50_000_000)
                assertTrue(runtime.installedClis().none { it.installed })
                val cleaned = runtime.cleanup(setOf(CleanupItem.PackageCache))
                assertEquals(setOf(CleanupItem.PackageCache), cleaned.removed)
                // Installing again is idempotent.
                assertEquals(LinuxState.Stopped, runtime.ensureInstalled(false).state)
            }
            "cli" -> {
                val runtime = TermuxLinuxRuntime(context, gateway)
                val steps = mutableListOf<String>()
                val tool = CliTool.entries.first { it.id == (InstrumentationRegistry.getArguments().getString("cliTool") ?: "codex") }
                val codex = runtime.installCli(tool) { steps += it }
                android.util.Log.i("TermuxDeviceTest", "cli ${tool.id} steps=${steps.distinct()} path=${codex.binaryPath} version=${codex.version}")
                assertNotNull(codex.binaryPath)
                assertTrue(codex.version.orEmpty(), codex.version.orEmpty().isNotBlank())
                assertNull(codex.signInVerifiedAt)
            }
            "desktop" -> {
                val runtime = TermuxLinuxRuntime(context, gateway)
                assertTrue("Termux:X11 app missing", gateway.status().x11Installed)
                val installed = runtime.ensureInstalled(true) { android.util.Log.i("TermuxDeviceTest", "desktop step=$it") }
                assertTrue(installed.detail, installed.desktopInstalled)
                val running = runtime.start(true)
                assertEquals(running.detail, LinuxState.Running, running.state)
                Thread.sleep(8_000)
                assertEquals(LinuxState.Running, runtime.status().state)
            }
            "desktop-stop" -> {
                val runtime = TermuxLinuxRuntime(context, gateway)
                assertEquals(LinuxState.Stopped, runtime.stop().state)
            }
            "remove" -> {
                val runtime = TermuxLinuxRuntime(context, gateway)
                assertEquals(setOf(CleanupItem.Distribution), runtime.cleanup(setOf(CleanupItem.Distribution)).removed)
                assertEquals(LinuxState.NotInstalled, runtime.status().state)
            }
            else -> fail("Pass -e termuxCase denied|external-off|ready|install|remove")
        }
    }
}
