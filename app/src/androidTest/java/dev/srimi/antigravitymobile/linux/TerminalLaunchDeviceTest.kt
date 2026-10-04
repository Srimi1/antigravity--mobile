package dev.srimi.antigravitymobile.linux

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalLaunchDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun interactiveDispatchOpensTermuxAndDoesNotCaptureLoginOutput() = runBlocking {
        assumeTrue("Install Termux and grant RUN_COMMAND on the test emulator", AndroidTermuxGateway(context).status().let { it.installed && it.runCommandPermission })
        val services = mutableListOf<Intent>()
        val activities = mutableListOf<Intent>()
        val recording = object : ContextWrapper(context) {
            override fun getApplicationContext() = this
            override fun startForegroundService(service: Intent): ComponentName { services += service; return service.component!! }
            override fun startActivity(intent: Intent) { activities += intent }
        }
        AndroidTermuxGateway(recording).run(GoogleCliCommands.open())
        assertEquals(1, services.size)
        assertFalse(services.single().getBooleanExtra(TermuxProtocol.EXTRA_BACKGROUND, true))
        assertFalse("Login output must not be returned to the app", services.single().hasExtra(TermuxProtocol.EXTRA_PENDING_INTENT))
        assertEquals(1, activities.size)
        assertEquals(TermuxProtocol.PACKAGE, activities.single().component?.packageName)
        assertTrue(activities.single().flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun missingDebianOrCliNeverClaimsAnInteractiveTerminalWasOpened() = runBlocking {
        val commands = mutableListOf<TermuxCommand>()
        val gateway = object : TermuxGateway {
            override fun status() = TermuxStatus(true, "fixture", true, true, false)
            override suspend fun run(command: TermuxCommand): TermuxResult {
                commands += command
                return TermuxResult(1, "", "distribution missing", -1, null, 1)
            }
        }
        val error = runCatching { TermuxLinuxRuntime(context, gateway).openTerminal(CliTool.ANTIGRAVITY) }.exceptionOrNull()
        assertTrue("$error", error is TermuxUnavailable.Failed)
        assertEquals(1, commands.size)
        assertTrue(commands.single().background)
    }

    @Test fun successfulDebianPreflightUsesExplicitCliPathAndEnvironment() = runBlocking {
        val commands = mutableListOf<TermuxCommand>()
        val gateway = object : TermuxGateway {
            override fun status() = TermuxStatus(true, "fixture", true, true, false)
            override suspend fun run(command: TermuxCommand): TermuxResult {
                commands += command
                return TermuxResult(if (command.background) 0 else null, "", "", -1, null, 1)
            }
        }
        TermuxLinuxRuntime(context, gateway).openTerminal(CliTool.CODEX)
        assertEquals(2, commands.size)
        assertEquals(listOf("/usr/bin/test", "-x", "/opt/agm/node/bin/codex"), commands.first().arguments.takeLast(3))
        val terminal = commands.last()
        assertFalse(terminal.background)
        assertTrue(terminal.arguments.any { it.startsWith("PATH=/opt/agm/node/bin:") })
        assertEquals(listOf("/opt/agm/node/bin/codex", "login", "--device-auth"), terminal.arguments.takeLast(3))
    }
}
