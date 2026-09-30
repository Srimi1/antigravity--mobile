package dev.srimi.antigravitymobile.worker

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.srimi.antigravitymobile.runtime.WebFiles
import dev.srimi.antigravitymobile.runtime.WebGuard
import java.io.ByteArrayInputStream
import java.io.File

/** No file/content access or JavaScript bridge; project code lives in the worker UID. */
class WebPreviewActivity : Activity() {
    private var web: WebView? = null
    private lateinit var console: TextView
    private val lines = ArrayDeque<String>()
    private var previewId: String? = null
    private val policy = "default-src 'self' data: blob:; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; connect-src 'self'; worker-src 'none'; object-src 'none'; frame-src 'none'; base-uri 'self'; form-action 'none'"
    private fun response(status: Int, text: String) = WebResourceResponse("text/plain", "UTF-8", status,
        if (status == 403) "Forbidden" else "Not Found", mapOf("Content-Security-Policy" to policy, "Cache-Control" to "no-store"), ByteArrayInputStream(text.toByteArray()))
    private fun note(text: String) {
        runOnUiThread { lines.addLast(text.take(500)); while (lines.size > 40) lines.removeFirst(); console.text = lines.joinToString("\n") }
    }
    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        setContentView(layout)
        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val header = LinearLayout(this)
        header.addView(Button(this).apply { text = "Close"; setOnClickListener { finish() } })
        header.addView(Button(this).apply { text = "Reload"; setOnClickListener { web?.reload() } })
        val consoleButton = Button(this).apply { text = "Console" }
        header.addView(consoleButton)
        layout.addView(header)
        layout.addView(TextView(this).apply { text = "Website preview · HTTP network blocked\nReturn to Projects to preview saved changes."; setPadding(16, 4, 16, 8); setTextColor(Color.DKGRAY) })
        console = TextView(this).apply { text = "No console messages"; setTextColor(Color.DKGRAY); textSize = 12f; setPadding(16, 8, 16, 8) }
        val scroll = ScrollView(this).apply { addView(console); visibility = View.GONE }
        layout.addView(scroll, LinearLayout.LayoutParams(-1, (180 * resources.displayMetrics.density).toInt()))
        consoleButton.setOnClickListener { scroll.visibility = if (scroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        if (savedInstanceState != null) { note("Preview interrupted. Close and approve a new copy in Projects."); scroll.visibility = View.VISIBLE; return }
        try {
            val id = intent.getStringExtra("id").orEmpty()
            val (root, entry) = WebPreviewStore(this).load(id)
            previewId = id
            val domain = "$id.preview.antigravity.invalid"
            val loader = WebViewAssetLoader.Builder().setDomain(domain).addPathHandler("/") { path ->
                try {
                    val relative = if (path.isEmpty()) entry else if (path.endsWith('/')) path + "index.html" else path
                    val file = WebFiles.resolve(root, relative)
                    if (!file.isFile) response(404, "Website file not found") else {
                        val mime = when (file.extension.lowercase()) {
                            "html", "htm" -> "text/html"; "js", "mjs" -> "application/javascript"; "css" -> "text/css"
                            "json" -> "application/json"; "svg" -> "image/svg+xml"; "wasm" -> "application/wasm"
                            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
                        }
                        val body = if (mime == "text/html") ByteArrayInputStream(WebGuard.inject(file.readBytes())) else file.inputStream()
                        WebResourceResponse(mime, if (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json")) "UTF-8" else null,
                            200, "OK", mapOf("Content-Security-Policy" to policy, "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), body)
                    }
                } catch (_: Exception) { response(403, "Website path blocked") }
            }.build()
            // This package has no accounts. Every preview starts with empty site storage.
            CookieManager.getInstance().setAcceptCookie(false)
            WebStorage.getInstance().deleteAllData()
            val browser = WebView(this); web = browser
            // Best-effort API removal, not the network boundary (request interception is). Older WebView
            // versions lack document-start scripts, so served HTML also starts with the same guard.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(browser, WebGuard.SCRIPT, setOf("*"))
            } else note("Older Android System WebView: using the page-level WebRTC guard")
            browser.clearCache(true)
            browser.settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                allowFileAccess = false; allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                blockNetworkLoads = true; mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setGeolocationEnabled(false); mediaPlaybackRequiresUserGesture = true; setSupportMultipleWindows(false)
                safeBrowsingEnabled = true; cacheMode = WebSettings.LOAD_NO_CACHE
            }
            ServiceWorkerController.getInstance().apply {
                serviceWorkerWebSettings.apply { blockNetworkLoads = true; allowFileAccess = false; allowContentAccess = false }
                setServiceWorkerClient(object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest) = response(403, "Service workers are unavailable in preview")
                })
            }
            browser.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    if (request.method != "GET" || request.url.scheme != "https" || request.url.host != domain || request.url.port !in setOf(-1, 443)) {
                        note("Blocked external or unsupported request"); return response(403, "Network requests blocked in preview")
                    }
                    return loader.shouldInterceptRequest(request.url) ?: response(403, "Website request blocked")
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val blocked = request.url.scheme != "https" || request.url.host != domain || request.url.port !in setOf(-1, 443)
                    if (blocked) note("Blocked external navigation")
                    return blocked
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { note("Load error ${error.errorCode}: ${error.description}") }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    (view.parent as? android.view.ViewGroup)?.removeView(view); view.destroy(); web = null
                    note("Preview renderer stopped. Close and approve a new copy."); scroll.visibility = View.VISIBLE; return true
                }
            }
            browser.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    note("${message.messageLevel()}: ${message.message()} (line ${message.lineNumber()})"); return true
                }
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
            }
            browser.setDownloadListener { _, _, _, _, _ -> note("Downloads are unavailable. Export the website from Projects.") }
            layout.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
            browser.loadUrl(Uri.Builder().scheme("https").authority(domain).path("/$entry").build().toString())
        } catch (error: Exception) { note("Preview unavailable: ${error.message?.take(200)}"); scroll.visibility = View.VISIBLE }
    }
    override fun onDestroy() {
        web?.apply { stopLoading(); (parent as? android.view.ViewGroup)?.removeView(this); destroy() }; web = null
        previewId?.let { WebPreviewStore(this).close(it) }
        super.onDestroy()
    }
}
