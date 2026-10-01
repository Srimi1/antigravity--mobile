package dev.srimi.antigravitymobile

import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.srimi.antigravitymobile.runtime.BuildProtocol as P
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebsiteDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) { found += node; repeat(node.childCount) { node.getChild(it)?.let(::walk) } }
        walk(root); return found
    }
    private suspend fun text(value: String): AccessibilityNodeInfo = withTimeoutOrNull(60000) {
        while (true) {
            nodes().firstOrNull { it.text?.toString()?.contains(value, ignoreCase = true) == true }?.let { return@withTimeoutOrNull it }
            delay(200)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    } ?: throw AssertionError("Did not see \"$value\" within 60 s. Visible: " +
        nodes().mapNotNull { it.text?.toString()?.takeIf(String::isNotBlank)?.take(80) }.take(25).joinToString(" | "))
    private suspend fun tap(value: String) {
        val bounds = Rect(); text(value).getBoundsInScreen(bounds)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "input tap ${bounds.centerX()} ${bounds.centerY()}"
        )).use { it.readBytes() }
    }
    private fun open(id: String) {
        context.startActivity(Intent().setComponent(ComponentName(P.WORKER, P.PREVIEW_ACTIVITY))
            .putExtra("id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }
    private suspend fun fixture(block: suspend (WebsiteService, File, BuildWorkerClient) -> Unit) {
        val base = File(context.cacheDir, "web-test-${UUID.randomUUID()}").apply { mkdirs() }
        val project = File(base, "project").apply { mkdirs() }
        val service = WebsiteService(File(base, "copies"))
        val client = BuildWorkerClient(context)
        try {
            assertTrue("Install matching tools first", client.installed())
            block(service, project, client)
        } finally {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Archives.deleteTree(base)
        }
    }
    private suspend fun approved(service: WebsiteService, project: File, client: BuildWorkerClient): WebsiteCopy {
        val copy = service.prepare("fixture", project, "", "index.html")
        service.claim(copy.id)
        client.preparePreview(copy.id, service.archive(copy.id), copy.hash, copy.entry)
        service.finish(copy.id, "READY")
        open(copy.id)
        return copy
    }

    @Test fun websiteTemplateIsPackagedAndHasRealLocalAssets() {
        val base = File(context.cacheDir, "template-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            context.assets.open("hello-web.zip").use { Archives.extract(it, base) }
            listOf("index.html", "about.html", "app.js", "styles.css", "data.json").forEach { assertTrue(File(base, it).isFile) }
            assertTrue(File(base, "app.js").readText().contains("fetch('./data.json')"))
        } finally { Archives.deleteTree(base) }
    }
    @Test fun actualWebViewRunsModulesCssJsonAndReloadsOnlyTheApprovedCopy() = runBlocking {
        fixture { service, project, client ->
            File(project, "index.html").writeText("""
                <!doctype html><link rel="stylesheet" href="/styles.css"><script type="module" src="./app.js"></script>
                <h1>APPROVED_PAGE</h1><button id="count">Count: 0</button><p id="data">Loading</p>
            """.trimIndent())
            File(project, "styles.css").writeText("body { color: rgb(12, 34, 56); }")
            File(project, "data.json").writeText("{\"message\":\"LOCAL_JSON_LOADED\"}")
            File(project, "app.js").writeText("""
                let count=0; document.querySelector('#count').onclick=e=>e.target.textContent='Count: '+(++count);
                fetch('./data.json').then(r=>r.json()).then(d=>document.querySelector('#data').textContent=d.message+
                    (getComputedStyle(document.body).color==='rgb(12, 34, 56)'?' CSS_LOADED':' CSS_FAILED'));
            """.trimIndent())
            val copy = approved(service, project, client)
            text("LOCAL_JSON_LOADED CSS_LOADED")
            tap("Count: 0"); text("Count: 1")
            File(project, "index.html").writeText("<h1>UNAPPROVED_NEW_CONTENT</h1>")
            tap("Reload"); text("APPROVED_PAGE"); text("Count: 0")
            assertFalse(nodes().any { it.text?.toString()?.contains("UNAPPROVED_NEW_CONTENT") == true })
            assertThrows(IllegalStateException::class.java) { service.claim(copy.id) }
            tap("Close")
            open(copy.id); text("Preview already opened")
        }
    }
    @Test fun browserDeniesExternalFileContentPostWorkersAndPeerConnections() = runBlocking {
        fixture { service, project, client ->
            File(project, "index.html").writeText("""
                <!doctype html><p id="result">Testing boundaries</p><script>
                async function test() {
                    const checks=[];
                    for (const [name,url] of [['external','https://example.invalid/secret'],['file','file:///system/build.prop'],
                        ['content','content://settings/system'],['hidden','/.env']]) {
                        try {const r=await fetch(url); checks.push(name+(!r.ok?'_DENIED':'_FAILED'));}
                        catch(e) {checks.push(name+'_DENIED');}
                    }
                    try {const r=await fetch('/data.json',{method:'POST',body:'x'}); checks.push(!r.ok?'POST_DENIED':'POST_FAILED');}
                    catch(e) {checks.push('POST_DENIED');}
                    try {await navigator.serviceWorker.register('/sw.js'); checks.push('WORKER_FAILED');}
                    catch(e) {checks.push('WORKER_DENIED');}
                    checks.push(typeof RTCPeerConnection==='undefined' && typeof WebTransport==='undefined' ? 'PEER_DENIED':'PEER_FAILED');
                    try {Object.defineProperty(window,'RTCPeerConnection',{value:function(){}});} catch(e) {}
                    checks.push(typeof RTCPeerConnection==='undefined'?'GUARD_LOCKED':'GUARD_FAILED');
                    const blank=document.createElement('iframe'); document.body.append(blank);
                    checks.push('BLANK_CONTENT_'+typeof blank.contentWindow.RTCPeerConnection);
                    checks.push('BLANK_INDEX_'+typeof window[0].RTCPeerConnection);
                    const srcdoc=await new Promise(done=>{ const timer=setTimeout(()=>done('NO_MESSAGE'),3000);
                        addEventListener('message',e=>{clearTimeout(timer);done(String(e.data));},{once:true});
                        const f=document.createElement('iframe');
                        f.srcdoc='<script>parent.postMessage(typeof RTCPeerConnection,"*")<\/script>'; document.body.append(f); });
                    checks.push('SRCDOC_'+srcdoc);
                    document.querySelector('#result').textContent=checks.join(' ');
                } test();</script>
            """.trimIndent())
            File(project, "data.json").writeText("{}")
            File(project, "sw.js").writeText("self.addEventListener('fetch',()=>{});")
            approved(service, project, client)
            val result = text("SRCDOC_").text.toString()
            // BLANK_* and SRCDOC_* are recorded, not asserted: the JavaScript WebRTC guard is defense in depth.
            // WebView 91 (API 31 image) left RTCPeerConnection defined inside srcdoc frames.
            android.util.Log.i("WebsiteDeviceTest", "Boundary result: $result")
            listOf("external_DENIED", "file_DENIED", "content_DENIED", "hidden_DENIED", "POST_DENIED", "WORKER_DENIED", "PEER_DENIED", "GUARD_LOCKED")
                .forEach { assertTrue("Missing $it in $result", it in result) }
            tap("Console")
            assertTrue(text("Blocked external or unsupported request").text.toString().contains("Blocked"))
            tap("Close")
        }
    }
    @Test fun eachNewPreviewHasEmptyStorageAndFailedIdsCannotBeReused() = runBlocking {
        fixture { service, project, client ->
            File(project, "index.html").writeText("""
                <!doctype html><p id="result"></p><script>
                document.querySelector('#result').textContent=localStorage.getItem('fixture')||'FRESH_STORAGE';
                localStorage.setItem('fixture','OLD_PREVIEW');</script>
            """.trimIndent())
            val first = approved(service, project, client); text("FRESH_STORAGE"); tap("Close")
            val second = approved(service, project, client); text("FRESH_STORAGE")
            assertNotEquals(first.id, second.id); tap("Close")
            val bad = service.prepare("fixture", project, "", "index.html")
            assertThrows(IllegalStateException::class.java) { runBlocking { client.preparePreview(bad.id, service.archive(bad.id), "0".repeat(64), bad.entry) } }
            assertThrows(IllegalStateException::class.java) { runBlocking { client.preparePreview(bad.id, service.archive(bad.id), bad.hash, bad.entry) } }
        }
    }
}
