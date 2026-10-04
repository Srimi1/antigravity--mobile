package dev.srimi.antigravitymobile.worker

import android.app.*
import android.content.Intent
import android.os.*
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import dev.srimi.antigravitymobile.runtime.BuildCache
import dev.srimi.antigravitymobile.runtime.BuildProtocol as P
import dev.srimi.antigravityruntime.RuntimeHost
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** A different Android UID owns all compiler files/processes. No account data or
 * provider credentials are accepted by this protocol. Commands arrive only from
 * the exact signed main-app UID, with a read-only approved source descriptor. */
class BuildWorkerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val records = ConcurrentHashMap<String, JSONObject>()
    private var active: Job? = null
    private var activeId: String? = null
    private lateinit var messenger: Messenger
    private val jobsDir by lazy { File(filesDir, "jobs").apply { mkdirs() } }
    private val gate = Any()

    override fun onCreate() {
        super.onCreate()
        // A new service process never resumes old commands. A live foreground
        // service is queried without running this recovery a second time.
        stopOwnedChildren()
        jobsDir.listFiles().orEmpty().filter { it.extension == "json" }.forEach { file ->
            runCatching {
                val record = JSONObject(file.readText())
                val id = record.getString("id"); P.validateId(id)
                if (record.optString("status") !in P.terminal) {
                    record.put("status", "INTERRUPTED").put("detail", "Worker stopped; command was not replayed.")
                        .put("finishedAt", System.currentTimeMillis())
                    save(record)
                } else records[id] = record
            }
        }
        messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
            receive(message); true
        })
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("builds", "Project builds", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foreground("Waiting for an approved build")
        Handler(Looper.getMainLooper()).postDelayed({ if (active == null) stopSelf(startId) }, 15000)
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        active?.cancel(); scope.cancel(); stopOwnedChildren()
        super.onDestroy()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        active?.cancel(); stopOwnedChildren(); stopSelf(startId)
    }

    private fun allowed(uid: Int): Boolean = runCatching {
        val main = packageManager.getApplicationInfo(P.MAIN, 0)
        uid == main.uid && packageManager.checkSignatures(P.MAIN, packageName) == android.content.pm.PackageManager.SIGNATURE_MATCH
    }.getOrDefault(false)

    private fun receive(message: Message) {
        val data = message.data
        val reply = message.replyTo ?: return
        val request = data.getString("requestId").orEmpty()
        if (!allowed(message.sendingUid)) {
            data.getParcelable<ParcelFileDescriptor>("source")?.close()
            send(reply, request, JSONObject().put("error", "Unauthorized caller")); return
        }
        try {
            when (message.what) {
                P.HELLO -> send(reply, request, JSONObject().put("ready", true).put("uid", android.os.Process.myUid())
                    .put("profile", "Gradle 8.13 · Java 17 · Android SDK 36"))
                P.START -> start(data, reply, request)
                P.PREVIEW -> {
                    val id = data.getString("id").orEmpty(); P.validateId(id)
                    val hash = data.getString("sha256").orEmpty()
                    val entry = data.getString("entry").orEmpty()
                    val source = data.getParcelable<ParcelFileDescriptor>("source") ?: error("Missing website copy")
                    scope.launch {
                        try {
                            WebPreviewStore(this@BuildWorkerService).prepare(id, hash, entry, source)
                            send(reply, request, JSONObject().put("id", id).put("ready", true))
                        } catch (error: Exception) {
                            send(reply, request, JSONObject().put("error", error.message?.take(500) ?: "Website copy failed"))
                        } finally { source.close() }
                    }
                }
                P.QUERY -> {
                    val id = data.getString("id").orEmpty(); P.validateId(id)
                    val record = records[id]?.let { JSONObject(it.toString()) } ?: JSONObject().put("status", "NOT_FOUND").put("id", id)
                    val log = File(filesDir, "evidence/run-$id.log")
                    if (log.isFile) record.put("output", tail(log, 12000))
                    send(reply, request, record)
                }
                P.CANCEL -> {
                    val id = data.getString("id").orEmpty(); P.validateId(id)
                    if (activeId == id) active?.cancel()
                    send(reply, request, JSONObject().put("id", id).put("requested", true))
                }
                P.ARTIFACT -> {
                    val id = data.getString("id").orEmpty(); P.validateId(id)
                    val record = records[id] ?: error("No build record")
                    check(record.getString("status") == "COMPLETED") { "No completed artifact" }
                    val relative = record.optJSONArray("apks")?.optString(data.getInt("index")) ?: error("No APK")
                    val project = File(filesDir, "projects/$id").canonicalFile
                    val apk = File(project, relative).canonicalFile
                    check(apk.path.startsWith(project.path + File.separator) && apk.isFile && apk.extension == "apk")
                    check(apk.length() <= 512L * 1024 * 1024)
                    val descriptor = ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY)
                    try { send(reply, request, JSONObject().put("name", apk.name), descriptor) } finally { descriptor.close() }
                }
                else -> error("Unknown operation")
            }
        } catch (error: Exception) {
            data.getParcelable<ParcelFileDescriptor>("source")?.close()
            send(reply, request, JSONObject().put("error", error.message?.take(500) ?: "Worker request failed"))
        }
    }

    private fun start(data: Bundle, reply: Messenger, request: String) {
        val id = data.getString("id").orEmpty(); P.validateId(id)
        val tasks = data.getStringArrayList("tasks")?.toList().orEmpty(); P.validateTasks(tasks)
        val expectedHash = data.getString("sha256").orEmpty()
        require(expectedHash.matches(Regex("[a-f0-9]{64}")))
        val cacheKey = data.getString("cacheKey")?.also { BuildCache.validateKey(it) }
        val descriptor = data.getParcelable<ParcelFileDescriptor>("source") ?: error("Missing source snapshot")
        synchronized(gate) {
            check(active == null) { "Another build is running" }
            check(records[id] == null) { "A build ID is never replayed; create a new approved task" }
            val record = JSONObject().put("id", id).put("status", "RUNNING").put("tasks", JSONArray(tasks))
                .put("sha256", expectedHash).put("startedAt", System.currentTimeMillis()).put("detail", "Preparing build tools")
            save(record); activeId = id
            foreground("Preparing build tools")
            active = scope.launch(start = CoroutineStart.LAZY) {
                var cache: BuildCache.Slot? = null
                var gradleRan = false
                try {
                    val host = RuntimeHost(this@BuildWorkerService)
                    val project = host.project(id)
                    val source = File(jobsDir, "$id-source.zip")
                    val digest = MessageDigest.getInstance("SHA-256")
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        source.outputStream().use { output ->
                            val buffer = ByteArray(65536); var total = 0L
                            while (true) {
                                ensureActive(); val n = input.read(buffer); if (n < 0) break
                                total += n; check(total <= 512L * 1024 * 1024) { "Snapshot exceeds 512 MB" }
                                digest.update(buffer, 0, n); output.write(buffer, 0, n)
                            }
                        }
                    }
                    check(digest.digest().joinToString("") { "%02x".format(it) } == expectedHash) { "Snapshot digest changed" }
                    source.inputStream().use { RuntimeHost.extract(it, project) }
                    RuntimeHost.writeText(File(project, "local.properties"), "sdk.dir=${host.sdk.path}\n")
                    val slot = BuildCache.acquire(File(host.root, "build-caches"), cacheKey, id)
                    cache = slot
                    record.put("cache", if (slot.reused) "reused" else "fresh")
                        .put("detail", "Running ${tasks.joinToString(" ")}" + if (slot.reused) " (dependency cache reused)" else ""); save(record)
                    foreground("Building project")
                    // Keep the tested tool profile; project scripts execute only
                    // in this UID. Original workspace files are never mounted.
                    val args = listOf("--gradle-user-home", slot.directory.path,
                        "-Dorg.gradle.jvmargs=-Xmx1200m -Dfile.encoding=UTF-8",
                        "-Pandroid.aapt2FromMavenOverride=${File(host.sdk,"build-tools/36.0.0/aapt2").path}") + tasks
                    val result = runInterruptible { host.run("run-$id", project, host.gradle(*args.toTypedArray()), 1200) }
                    gradleRan = true
                    record.put("exit", result.exit).put("durationMs", result.elapsedMs)
                    if (result.exit == 0) {
                        val apks = project.walkTopDown().filter { it.isFile && it.extension == "apk" }
                            .filter { it.canonicalPath.startsWith(project.canonicalPath + File.separator) }
                            .take(20).map { it.relativeTo(project).invariantSeparatorsPath }.toList()
                        record.put("apks", JSONArray(apks)).put("status", "COMPLETED").put("detail", "Gradle completed (dependency cache ${record.optString("cache")})")
                    } else record.put("status", "FAILED").put("detail", "Gradle exited with ${result.exit}; inspect output (dependency cache ${record.optString("cache")})")
                } catch (_: CancellationException) {
                    record.put("status", "CANCELLED").put("detail", "Build stopped; command was not replayed")
                } catch (error: Exception) {
                    record.put("status", "FAILED").put("detail", error.message?.take(500) ?: "Build failed")
                } finally {
                    withContext(NonCancellable) {
                        descriptor.close(); stopOwnedChildren()
                        runCatching { cache?.let { BuildCache.release(it, gradleRan) } }
                        record.put("finishedAt", System.currentTimeMillis()); save(record)
                        synchronized(gate) { active = null; activeId = null }
                        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                    }
                }
            }
            active!!.start()
            send(reply, request, record)
        }
    }

    private fun save(record: JSONObject) {
        val copy = JSONObject(record.toString())
        val id = copy.getString("id"); P.validateId(id)
        val atomic = AtomicFile(File(jobsDir, "$id.json"))
        val out = atomic.startWrite()
        try { out.write(copy.toString().toByteArray()); atomic.finishWrite(out) }
        catch (error: Exception) { atomic.failWrite(out); throw error }
        records[id] = copy
    }
    private fun send(reply: Messenger, request: String, json: JSONObject, artifact: ParcelFileDescriptor? = null) {
        runCatching {
            reply.send(Message.obtain(null, P.REPLY).apply { data = Bundle().apply {
                putString("requestId", request); putString("json", json.toString())
                if (artifact != null) putParcelable("artifact", artifact)
            } })
        }
    }
    private fun foreground(text: String) {
        val open = packageManager.getLaunchIntentForPackage(P.MAIN)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(this, "builds").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Antigravity build").setContentText(text).setOngoing(true).setContentIntent(open).build()
        startForeground(1, notification)
    }
    private fun tail(file: File, limit: Int): String = java.io.RandomAccessFile(file, "r").use {
        val length = it.length(); it.seek((length-limit).coerceAtLeast(0))
        val bytes = ByteArray(minOf(length, limit.toLong()).toInt()); it.readFully(bytes)
        bytes.toString(Charsets.UTF_8)
    }
    private fun stopOwnedChildren() {
        val self = android.os.Process.myPid(); val uid = android.os.Process.myUid().toString()
        File("/proc").listFiles().orEmpty().filter { it.name.toIntOrNull() != null && it.name.toInt() != self }.forEach { proc ->
            runCatching {
                val owner = File(proc,"status").readLines().firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(1)
                if (owner == uid) Os.kill(proc.name.toInt(), OsConstants.SIGKILL)
            }
        }
    }
}
