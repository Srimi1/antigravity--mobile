package dev.srimi.antigravitymobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the app process running while the user signs in through the browser. Without it, Android may freeze
 * the backgrounded app, so the loopback callback page keeps loading until the user returns to the app.
 */
class SignInKeepAlive : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("sign-in", "Account sign-in", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE)
        startForeground(2, NotificationCompat.Builder(this, "sign-in").setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Waiting for ChatGPT sign-in").setContentText("Finish in the browser, then return to Antigravity Mobile.")
            .setOngoing(true).setContentIntent(open).build())
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf(startId) }

    companion object {
        fun start(context: Context) = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, SignInKeepAlive::class.java))
        }
        fun stop(context: Context) { context.stopService(Intent(context, SignInKeepAlive::class.java)) }
    }
}
