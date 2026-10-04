package dev.srimi.antigravitymobile

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Two real Compose builds of one project through the installed build tools: the second must reuse the dependency cache. */
@RunWith(AndroidJUnit4::class)
class BuildCacheDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun secondBuildOfSameProjectReusesDependencyCache() = runBlocking<Unit> {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(1000)
        val services = context.container
        services.ready.await()
        val project = services.projects.create("Cache measurement ${System.currentTimeMillis()}") { dir ->
            context.assets.open("hello-phone.zip").use { Archives.extract(it, dir) }
        }
        val runner = PhoneBuildRunner(services, project, context)
        assertNull("Install the matching build tools on the QA emulator first", runner.unavailableReason())

        fun build(label: String) = runBlocking {
            val started = System.currentTimeMillis()
            val prepared = runner.prepare(":app:assembleDebug")
            val outcome = withTimeout(45 * 60_000) { runner.runApproved(prepared.id) }
            val wall = System.currentTimeMillis() - started
            Log.i("CacheMeasure", "$label status=${outcome.status} gradleMs=${outcome.durationMs} wallMs=$wall detail=${outcome.detail}")
            assertEquals(outcome.detail + outcome.outputTail.takeLast(800), "COMPLETED", outcome.status)
            outcome
        }
        val first = build("first")
        val source = services.projects.directory(project).walkTopDown().first { it.name == "MainActivity.kt" }
        source.appendText("\n// second build, changed source\n")
        val second = build("second")
        val firstCache = services.builds.client.status(first.id).optString("cache")
        val secondCache = services.builds.client.status(second.id).optString("cache")
        Log.i("CacheMeasure", "worker cache first=$firstCache second=$secondCache")
        assertEquals("fresh", firstCache)
        assertEquals("reused", secondCache)
        Log.i("CacheMeasure", "speedup gradle ${first.durationMs}ms -> ${second.durationMs}ms")
    }
}
