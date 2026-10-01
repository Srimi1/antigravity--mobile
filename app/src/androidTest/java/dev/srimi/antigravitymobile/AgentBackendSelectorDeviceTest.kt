package dev.srimi.antigravitymobile

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.srimi.antigravitymobile.runtime.AgentBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Agent tab: choosing an unverified CLI backend shows the runtime limit, blocks Send and links to setup. */
@RunWith(AndroidJUnit4::class)
class AgentBackendSelectorDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun unverifiedCliBackendIsSelectableButCannotSend() = runBlocking {
        val context = instrumentation.targetContext
        val services = context.container
        services.ready.await()
        context.getSharedPreferences("cli-capability", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val previous = services.agentBackend
        services.agentBackend = AgentBackend.Native
        val project = services.projects.create("Backend selector fixture") { it.resolve("README.md").writeText("fixture") }
        try {
            services.selectProject(project.id)
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            click("Agent")
            click("Native providers")
            click("Codex CLI")
            assertNotNull(waitFor { it.startsWith("Codex CLI is not verified on this phone") })
            assertEquals(AgentBackend.Codex, services.agentBackend)
            assertFalse("Send must stay disabled for an unverified CLI", node("Send")!!.let(::enabledAncestor))
            click("Open Linux setup")
            assertNotNull(scrollUntil { it == "Verify Codex CLI" })
            assertNotNull(waitFor { it.startsWith("Antigravity CLI is not verified on this phone") })
            assertNull("no task may start", services.database.runtime().active())
        } finally {
            services.agentBackend = previous
            services.projects.delete(project)
        }
    }
    private fun enabledAncestor(start: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = start
        repeat(4) { if (node?.isClickable == true) return node!!.isEnabled; node = node?.parent }
        return start.isEnabled
    }
    private fun find(match: (String) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString()?.let(match) == true || node.contentDescription?.toString()?.let(match) == true) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return instrumentation.uiAutomation.windows.firstNotNullOfOrNull { visit(it.root) } ?: visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private suspend fun scrollUntil(match: (String) -> Boolean): AccessibilityNodeInfo? {
        fun scrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable) return node
            for (index in 0 until node.childCount) scrollable(node.getChild(index))?.let { return it }
            return null
        }
        repeat(20) {
            find(match)?.let { return it }
            scrollable(instrumentation.uiAutomation.rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(300)
        }
        throw AssertionError("Not found after scrolling; visible: ${visible()}")
    }
    private fun node(label: String) = find { it == label }
    private suspend fun waitFor(match: (String) -> Boolean) = try {
        withTimeout(15_000) {
            var found: AccessibilityNodeInfo? = null
            while (found == null) { found = find(match); if (found == null) delay(100) }
            found
        }
    } catch (error: Exception) { throw AssertionError("Text not found; visible: ${visible()}", error) }
    private fun visible(): List<String> {
        val texts = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            (node.text ?: node.contentDescription)?.let { texts += it.toString() }
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        instrumentation.uiAutomation.windows.forEach { visit(it.root) }; visit(instrumentation.uiAutomation.rootInActiveWindow)
        return texts
    }
    private suspend fun click(label: String) = try { clickOnce(label) } catch (error: Exception) {
        throw AssertionError("Could not click '$label'; visible: ${visible()}", error)
    }
    private suspend fun clickOnce(label: String) = withTimeout(15_000) {
        while (true) {
            var node = node(label)
            repeat(6) {
                if (node?.isClickable == true && node?.isEnabled == true && node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return@withTimeout
                node = node?.parent
            }
            delay(100)
        }
    }
}
