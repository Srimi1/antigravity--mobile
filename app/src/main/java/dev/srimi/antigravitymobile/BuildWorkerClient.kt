package dev.srimi.antigravitymobile

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import androidx.core.content.ContextCompat
import dev.srimi.antigravitymobile.runtime.BuildProtocol as P
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Signature + exact UID checks in both directions; the worker receives an
 * approved read-only archive descriptor, never main-app paths or credentials. */
class BuildWorkerClient(private val context: Context) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Bundle>>()
    private var remote: Messenger? = null
    private var connected = CompletableDeferred<Unit>()
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { remote = Messenger(binder); connected.complete(Unit) }
        override fun onServiceDisconnected(name: ComponentName) {
            if (bound) runCatching { context.unbindService(this) }
            remote = null; bound = false; connected = CompletableDeferred()
        }
        override fun onBindingDied(name: ComponentName) {
            if (bound) runCatching { context.unbindService(this) }
            remote = null; bound = false; connected = CompletableDeferred()
        }
    }
    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        val expected = runCatching { context.packageManager.getApplicationInfo(P.WORKER, 0).uid }.getOrNull()
        if (expected != null && message.sendingUid == expected) {
            val waiting = pending.remove(message.data.getString("requestId"))
            if (waiting == null || !waiting.complete(Bundle(message.data))) message.data.getParcelable<ParcelFileDescriptor>("artifact")?.close()
        } else message.data.getParcelable<ParcelFileDescriptor>("artifact")?.close()
        true
    })
    /** Older tools from this app's signer: Android's installer can update them in place. */
    fun outdated(): Boolean = runCatching {
        context.packageManager.getPackageInfo(P.WORKER, 0).longVersionCode < P.MIN_WORKER_VERSION &&
            context.packageManager.checkSignatures(context.packageName, P.WORKER) == PackageManager.SIGNATURE_MATCH
    }.getOrDefault(false)
    fun installed(): Boolean = runCatching {
        context.packageManager.getPackageInfo(P.WORKER, 0).longVersionCode >= P.MIN_WORKER_VERSION &&
            context.packageManager.checkSignatures(context.packageName, P.WORKER) == PackageManager.SIGNATURE_MATCH
    }.getOrDefault(false)
    private suspend fun connect() = withContext(Dispatchers.Main.immediate) {
        check(installed()) { "Install the matching build tools first" }
        if (!bound) {
            connected = CompletableDeferred()
            bound = context.bindService(Intent().setComponent(ComponentName(P.WORKER, P.SERVICE)), connection, Context.BIND_AUTO_CREATE)
            check(bound) { "Could not connect to build tools" }
        }
        if (remote == null) withTimeout(10000) { connected.await() }
    }
    suspend fun request(operation: Int, fill: Bundle.() -> Unit = {}): Bundle {
        connect()
        val id = UUID.randomUUID().toString()
        val wait = CompletableDeferred<Bundle>(); pending[id] = wait
        try {
            remote!!.send(Message.obtain(null, operation).apply {
                replyTo = replies; data = Bundle().apply { putString("requestId", id); fill() }
            })
            val reply = withTimeout(15000) { wait.await() }
            val json = JSONObject(reply.getString("json") ?: "{}")
            if (json.has("error")) {
                reply.getParcelable<ParcelFileDescriptor>("artifact")?.close()
                error(json.optString("error"))
            }
            return reply
        } finally { pending.remove(id) }
    }
    suspend fun status(id: String): JSONObject = JSONObject(request(P.QUERY) { putString("id", id) }.getString("json")!!)
    suspend fun start(id: String, tasks: List<String>, archive: File, hash: String) {
        P.validateId(id); P.validateTasks(tasks)
        withContext(Dispatchers.Main.immediate) {
            ContextCompat.startForegroundService(context, Intent().setComponent(ComponentName(P.WORKER, P.SERVICE)))
        }
        ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
            request(P.START) {
                putString("id", id); putStringArrayList("tasks", ArrayList(tasks)); putString("sha256", hash)
                putParcelable("source", source)
            }
        }
    }
    suspend fun cancel(id: String) { request(P.CANCEL) { putString("id", id) } }
    suspend fun preparePreview(id: String, archive: File, hash: String, entry: String) {
        P.validateId(id)
        ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
            request(P.PREVIEW) {
                putString("id", id); putString("sha256", hash); putString("entry", entry)
                putParcelable("source", source)
            }
        }
    }
    suspend fun artifact(id: String, index: Int, destination: File) {
        val message = request(P.ARTIFACT) { putString("id", id); putInt("index", index) }
        val fd = message.getParcelable<ParcelFileDescriptor>("artifact") ?: error("Worker returned no artifact")
        destination.parentFile!!.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.partial")
        try {
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        total += n; check(total <= 512L * 1024 * 1024) { "APK exceeds 512 MB" }
                        output.write(buffer,0,n)
                    }
                    output.fd.sync()
                }
            }
            checkNotNull(context.packageManager.getPackageArchiveInfo(temporary.path, 0)) { "Worker artifact is not an APK" }
            check(temporary.renameTo(destination)) { "Could not save verified APK" }
        } catch (error: Exception) { temporary.delete(); throw error }
    }
}
