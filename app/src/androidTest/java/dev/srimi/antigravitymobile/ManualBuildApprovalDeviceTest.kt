package dev.srimi.antigravitymobile

import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Build tab semantics: dismissal is cancellation; only the labelled Decline records DECLINED. */
@RunWith(AndroidJUnit4::class)
class ManualBuildApprovalDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun cancelBackAndExplicitDeclineHaveDifferentRecordedResults() = runBlocking {
        val context = instrumentation.targetContext
        val services = context.container
        services.ready.await()
        val project = services.projects.create("Build approval UI fixture") {
            it.resolve("settings.gradle.kts").writeText("rootProject.name = \"approval-fixture\"\n")
            it.resolve("build.gradle.kts").writeText("println(\"THIS_BUILD_MUST_NOT_RUN\")\n")
        }
        services.selectProject(project.id)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        click("Build")

        suspend fun prepare(): BuildRecord {
            val before = services.database.builds().forProject(project.id).map { it.id }.toSet()
            click("Review build")
            return withTimeout(15_000) {
                var record: BuildRecord? = null
                while (record == null) {
                    record = services.database.builds().forProject(project.id).firstOrNull { it.id !in before && it.status == "AWAITING_APPROVAL" }
                    if (record == null) delay(50)
                }
                record
            }
        }
        suspend fun status(id: String, expected: String) = withTimeout(10_000) {
            while (services.database.builds().find(id)?.status != expected) delay(50)
            assertTrue(services.database.builds().find(id)!!.detail.contains(expected.lowercase()))
            assertEquals("NOT_FOUND", services.builds.client.status(id).getString("status"))
        }

        val cancel = prepare(); click("Cancel"); status(cancel.id, "CANCELLED")
        val back = prepare()
        withTimeout(10_000) { while (findText("Approve build command") == null) delay(50) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        status(back.id, "CANCELLED")
        val decline = prepare(); click("Decline"); status(decline.id, "DECLINED")
        assertEquals(3, services.database.builds().forProject(project.id).size)
    }
    private fun findText(text: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private suspend fun click(label: String) = withTimeout(15_000) {
        while (true) {
            var node = findText(label)
            repeat(6) {
                if (node?.isClickable == true && node?.isEnabled == true && node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return@withTimeout
                node = node?.parent
            }
            delay(100)
        }
    }
}
