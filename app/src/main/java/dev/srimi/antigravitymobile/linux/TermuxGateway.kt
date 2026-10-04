package dev.srimi.antigravitymobile.linux

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Termux's documented RUN_COMMAND contract (https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent),
 * constants copied from TermuxConstants.java. Pure parsing so it can be tested on the JVM.
 */
// Paths below belong to the Termux app (another package), not to this app's own storage.
@android.annotation.SuppressLint("SdCardPath")
object TermuxProtocol {
    const val PACKAGE = "com.termux"
    const val ACTIVITY = "com.termux.app.TermuxActivity"
    const val X11_PACKAGE = "com.termux.x11"
    const val X11_ACTIVITY = "com.termux.x11.MainActivity"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val SERVICE = "com.termux.app.RunCommandService"
    const val ACTION = "com.termux.RUN_COMMAND"
    const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    const val RESULT_BUNDLE = "result"
    const val PREFIX = "/data/data/com.termux/files/usr"
    const val HOME = "/data/data/com.termux/files/home"
    const val BASH = "$PREFIX/bin/bash"
    /** The one-time step the user runs in Termux so other apps may send commands. */
    const val ALLOW_EXTERNAL_APPS_COMMAND =
        "mkdir -p ~/.termux && printf '\\nallow-external-apps=true\\n' >> ~/.termux/termux.properties && termux-reload-settings && echo 'Done. Return to Antigravity Mobile.'"

    /** Maps Termux's result bundle values; throws when Termux refused to run the command at all. */
    fun result(stdout: String?, stderr: String?, exitCode: Int?, err: Int?, errmsg: String?, durationMs: Long): TermuxResult {
        val message = errmsg?.take(2_000)
        if (err != null && err != -1 && message != null) {
            if (message.contains("allow-external-apps", ignoreCase = true)) throw TermuxUnavailable.ExternalAppsDisabled()
            throw TermuxUnavailable.Failed("Termux could not run the command: ${message.lineSequence().first().take(200)}")
        }
        return TermuxResult(exitCode, stdout.orEmpty(), stderr.orEmpty(), err, message, durationMs)
    }

    /** Parses the helper's `key=value` lines. Repeated keys keep every value in order. */
    fun keyValues(text: String): List<Pair<String, String>> = text.lineSequence()
        .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it).trim() to line.substring(it + 1).trim() } }
        .filter { it.first.matches(Regex("[a-z_][a-z0-9_-]*")) }.toList()
}

/** RUN_COMMAND through Termux's service; the result comes back on a one-use, package-bound PendingIntent. */
class AndroidTermuxGateway(context: Context) : TermuxGateway {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("termux", Context.MODE_PRIVATE)
    private val requestCodes = AtomicInteger((System.nanoTime() and 0xFFFF).toInt())

    private fun version(pkg: String): String? = try {
        app.packageManager.getPackageInfo(pkg, 0).versionName ?: "unknown"
    } catch (_: PackageManager.NameNotFoundException) { null }

    override fun status(): TermuxStatus {
        val termux = version(TermuxProtocol.PACKAGE)
        return TermuxStatus(termux != null, termux,
            app.checkSelfPermission(TermuxProtocol.PERMISSION) == PackageManager.PERMISSION_GRANTED,
            if (prefs.contains("externalApps")) prefs.getBoolean("externalApps", false) else null,
            version(TermuxProtocol.X11_PACKAGE) != null)
    }

    /** Starting a terminal service alone does not bring Termux to the foreground on Android 10+. */
    fun openApp() {
        if (!status().installed) throw TermuxUnavailable.NotInstalled()
        val intent = app.packageManager.getLaunchIntentForPackage(TermuxProtocol.PACKAGE)
            ?: Intent(Intent.ACTION_MAIN).setClassName(TermuxProtocol.PACKAGE, TermuxProtocol.ACTIVITY)
                .addCategory(Intent.CATEGORY_LAUNCHER)
        try { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { throw TermuxUnavailable.Failed("Android could not open Termux. Open Termux from your phone's app list and finish its first-time setup, then retry.") }
    }

    override suspend fun run(command: TermuxCommand): TermuxResult {
        val status = status()
        if (!status.installed) throw TermuxUnavailable.NotInstalled()
        if (!status.runCommandPermission) throw TermuxUnavailable.PermissionDenied()
        val action = "${app.packageName}.TERMUX_RESULT.${UUID.randomUUID()}"
        val reply = CompletableDeferred<Bundle>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { reply.complete(intent.getBundleExtra(TermuxProtocol.RESULT_BUNDLE) ?: Bundle()) }
        }
        if (command.background) {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(receiver, IntentFilter(action))
        }
        // Interactive CLI output and login codes stay in Termux, never in this app's result receiver.
        val callback = if (command.background) PendingIntent.getBroadcast(app, requestCodes.incrementAndGet(), Intent(action).setPackage(app.packageName),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE) else null
        val started = System.currentTimeMillis()
        try {
            val intent = Intent(TermuxProtocol.ACTION).setClassName(TermuxProtocol.PACKAGE, TermuxProtocol.SERVICE)
                .putExtra(TermuxProtocol.EXTRA_PATH, command.executable)
                .putExtra(TermuxProtocol.EXTRA_ARGUMENTS, command.arguments.toTypedArray())
                .putExtra(TermuxProtocol.EXTRA_BACKGROUND, command.background)
                .apply {
                    callback?.let { putExtra(TermuxProtocol.EXTRA_PENDING_INTENT, it) }
                    command.stdin?.let { putExtra(TermuxProtocol.EXTRA_STDIN, it) }
                    command.workingDirectory?.let { putExtra(TermuxProtocol.EXTRA_WORKDIR, it) }
                    command.label?.let { putExtra(TermuxProtocol.EXTRA_LABEL, it) }
                }
            try {
                // Termux's documented call: RunCommandService promotes itself to a foreground service, which also
                // works when Termux is not running (plain startService is refused then).
                app.startForegroundService(intent) ?: throw TermuxUnavailable.NotInstalled()
            } catch (denied: SecurityException) { throw TermuxUnavailable.PermissionDenied() }
            catch (background: IllegalStateException) { throw TermuxUnavailable.Failed("Android blocked starting Termux from the background; open Antigravity Mobile and retry") }
            // Android can defer foreground terminal commands until Termux's Activity is opened.
            if (!command.background) {
                openApp()
                return TermuxResult(null, "", "", null, null, System.currentTimeMillis() - started)
            }
            val bundle = withTimeoutOrNull(command.timeoutMs) { reply.await() } ?: throw TermuxUnavailable.TimedOut(command.timeoutMs)
            val result = try {
                TermuxProtocol.result(bundle.getString("stdout"), bundle.getString("stderr"),
                    if (bundle.containsKey("exitCode")) bundle.getInt("exitCode") else null,
                    if (bundle.containsKey("err")) bundle.getInt("err") else null, bundle.getString("errmsg"), System.currentTimeMillis() - started)
            } catch (disabled: TermuxUnavailable.ExternalAppsDisabled) { prefs.edit().putBoolean("externalApps", false).apply(); throw disabled }
            prefs.edit().putBoolean("externalApps", true).apply()
            return result
        } finally {
            if (command.background) runCatching { app.unregisterReceiver(receiver) }
            callback?.cancel()
        }
    }
}
