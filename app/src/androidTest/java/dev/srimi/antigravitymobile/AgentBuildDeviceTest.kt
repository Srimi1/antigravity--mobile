package dev.srimi.antigravitymobile

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The agent's real build runner against the installed build tools: one successful and one failing Compose build. */
@RunWith(AndroidJUnit4::class)
class AgentBuildDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun agentRunnerBuildsComposeAppAndReturnsFailureLogs() = runBlocking {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(1000)
        val services = context.container
        services.ready.await()
        val project = services.projects.create("Agent build proof") { dir ->
            context.assets.open("hello-phone.zip").use { Archives.extract(it, dir) }
        }
        val runner = PhoneBuildRunner(services, project, context)
        assertNull("Install the matching build tools on the QA emulator first", runner.unavailableReason())

        val good = runner.prepare(":app:assembleDebug")
        val passed = withTimeout(20 * 60_000) { runner.runApproved(good.id) }
        assertEquals(passed.detail, "COMPLETED", passed.status)
        assertTrue(passed.apks.toString(), passed.apks.isNotEmpty())
        assertTrue(passed.outputTail.takeLast(400), passed.outputTail.contains("BUILD SUCCESSFUL"))

        val source = services.projects.directory(project).walkTopDown().first { it.name == "MainActivity.kt" }
        source.appendText("\nfun brokenOnPurpose(): Int = notDefinedAnywhere\n")
        val bad = runner.prepare(":app:assembleDebug")
        val failed = withTimeout(20 * 60_000) { runner.runApproved(bad.id) }
        assertEquals("FAILED", failed.status)
        assertTrue(failed.outputTail.takeLast(600), failed.outputTail.contains("notDefinedAnywhere"))
        assertTrue(failed.apks.isEmpty())
    }
}
