package dev.srimi.antigravitymobile.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.srimi.antigravitymobile.MainActivity
import dev.srimi.antigravitymobile.container
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull

/** User-started coding task: provider turns, native approvals and worker observation. No screen owns its Job. */
class AgentTaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observation: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Agent tasks", NotificationManager.IMPORTANCE_LOW))
        foreground("Restoring recorded task", null)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("taskId")
        if (id != null) {
            if (runCatching { BuildProtocol.validateId(id) }.isFailure) { stopSelf(startId); return START_NOT_STICKY }
            if (intent.action == STOP) scope.launch { container.tasks.cancel(id) }
            else scope.launch { container.tasks.launchFromService(id, scope) }
        }
        observation?.cancel()
        observation = scope.launch {
            container.ready.await()
            val taskId = id ?: container.database.runtime().active()?.id
            if (taskId == null) { stopSelf(startId); return@launch }
            container.database.runtime().observeTask(taskId).filterNotNull().collect { task ->
                foreground(task.detail, taskId)
                if (task.status in setOf(TaskPhase.Paused.name, TaskPhase.Completed.name, TaskPhase.Failed.name) ||
                    (task.status == TaskPhase.Cancelled.name && task.activeSlot == null))
                    stopSelf(startId)
            }
        }
        // Restart restores observation only; a null Intent never replays execution.
        return START_STICKY
    }
    private fun foreground(detail: String, taskId: String?) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Antigravity Mobile agent").setContentText(detail.take(160)).setOngoing(true).setContentIntent(open)
        if (taskId != null) {
            val stop = PendingIntent.getService(this, taskId.hashCode(), Intent(this, AgentTaskService::class.java)
                .setAction(STOP).putExtra("taskId", taskId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            notification.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
        }
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION, notification.build())
    }
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { scope.cancel(); stopSelf(startId) }
    companion object {
        private const val CHANNEL = "agent-tasks"
        private const val NOTIFICATION = 3
        private const val STOP = "dev.srimi.antigravitymobile.STOP_TASK"
        fun execute(context: Context, taskId: String) {
            BuildProtocol.validateId(taskId)
            ContextCompat.startForegroundService(context, Intent(context, AgentTaskService::class.java).putExtra("taskId", taskId))
        }
    }
}
