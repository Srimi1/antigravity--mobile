package dev.srimi.antigravitymobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceProbeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun launcherIconUsesAdaptiveArtwork() {
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue(icon is android.graphics.drawable.AdaptiveIconDrawable)
        val foreground = (icon as android.graphics.drawable.AdaptiveIconDrawable).foreground
        assertTrue(foreground is android.graphics.drawable.BitmapDrawable)
        assertTrue((foreground as android.graphics.drawable.BitmapDrawable).bitmap.hasAlpha())
    }
    private fun executor() = NativeExecutionService(
        File(context.applicationInfo.nativeLibraryDir, "libexecution_probe.so"), context.filesDir)
    @Test fun apkContainsCompleteComposeSourceArchive() {
        val entries = mutableSetOf<String>()
        java.util.zip.ZipInputStream(context.assets.open("hello-phone.zip")).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) { entries += entry.name; entry = zip.nextEntry }
        }
        assertTrue("app/build.gradle.kts" in entries)
        assertTrue("gradlew" in entries)
        assertTrue("gradle/wrapper/gradle-wrapper.jar" in entries)
    }
    @Test fun generatedProjectHasExecutableWrapperAndReportReflectsResult(): Unit = runBlocking {
        val holder = androidx.lifecycle.ViewModelStore()
        val model = withContext(Dispatchers.Main) {
            androidx.lifecycle.ViewModelProvider(holder,
                androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(context.applicationContext as android.app.Application))
                .get(ProbeViewModel::class.java)
        }
        try {
            withTimeout(5000) { model.state.first { it.ready } }
            withContext(Dispatchers.Main) { model.generateSample() }
            withTimeout(10000) { model.state.first { !it.running } }
            val report = org.json.JSONObject(model.report())
            val checks = report.getJSONArray("checks")
            val generation = (0 until checks.length()).map { checks.getJSONObject(it) }
                .first { it.getString("name") == "Generate Compose sample source" }
            assertEquals("PASSED", generation.getString("status"))
            val folder = generation.getString("detail").substringAfter("written to ").substringBefore(".")
            val source = File(context.filesDir, "workspaces/probe/$folder")
            assertTrue(File(source, "gradlew").canExecute())
            assertTrue(File(source, "app/src/main/java/dev/srimi/hellophone/MainActivity.kt").isFile)
            assertEquals("BLOCKED", report.getString("fullProductGate"))
            source.deleteRecursively()
        } finally { withContext(Dispatchers.Main) { holder.clear() } }
    }
    @Test fun packagedArm64ExecutableRunsAndReturnsNonzeroExit() = runBlocking {
        val service = executor()
        val version = service.execute("version")
        assertEquals(0, version.exitCode)
        assertTrue(version.output.contains("Android/Bionic"))
        assertEquals(7, service.execute("exit-7").exitCode)
    }
    @Test fun cancellationStopsProcessWithinThreeSeconds() = runBlocking {
        val service = executor()
        val ready = CompletableDeferred<Unit>()
        val command = async { service.execute("wait") { if (it == "ready") ready.complete(Unit) } }
        withTimeout(5000) { ready.await() }
        service.cancel()
        assertNotEquals(0, withTimeout(3000) { command.await() }.exitCode)
    }
    @Test fun interruptedRunsAreRecoveredWithoutReplay() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SessionStore::class.java).build()
        try {
            db.checks().save(CheckRecord("pending", "command", "RUNNING", "pending", 1))
            db.checks().interruptUnfinished()
            assertEquals("INTERRUPTED", db.checks().all().single().status)
        } finally { db.close() }
    }
    @Test fun coroutineCancellationReleasesExecutionSlot() = runBlocking {
        val service = executor()
        val ready = CompletableDeferred<Unit>()
        val command = launch { service.execute("wait") { if (it == "ready") ready.complete(Unit) } }
        withTimeout(5000) { ready.await() }
        withTimeout(3000) { command.cancelAndJoin() }
        assertEquals(0, withTimeout(3000) { service.execute("version") }.exitCode)
    }
    @Test fun encryptedCredentialStorageRoundTrip() {
        val store = CredentialStore(context, "test.credentials")
        val fixture = org.json.JSONObject().put("client_id", "test-only").put("access_token", "test-token")
        store.save(fixture)
        assertEquals("test-token", store.read()!!.getString("access_token"))
        val disk = File(context.noBackupFilesDir, "test.credentials").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(disk.contains("test-token"))
        store.save(org.json.JSONObject().put("client_id", "test-only"))
        File(context.noBackupFilesDir, "test.credentials").delete()
    }
}
