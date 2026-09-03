package com.joe6355.astrophoto

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ProcessingForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        startInForeground(buildNotification("Подготовка обработки…", null))
        serviceScope.launch {
            SessionProcessingCoordinator.states.collectLatest { states ->
                val active = states.values.filter { it.running }
                if (active.isNotEmpty()) {
                    val state = active.first()
                    val progress = if (state.total > 0) state.current to state.total else null
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(
                            this@ProcessingForegroundService,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationManager.notify(
                            NOTIFICATION_ID,
                            buildNotification(state.status, progress)
                        )
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) {
            SessionProcessingCoordinator.cancelAll()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        SessionProcessingCoordinator.cancelAll()
        stopSelf(startId)
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(status: String, progress: Pair<Int, Int>?): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelPendingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ProcessingForegroundService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("AstroPhoto: обработка сессии")
            .setContentText(status)
            .setContentIntent(openPendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, "Остановить", cancelPendingIntent)
            .apply {
                if (progress != null) {
                    setProgress(progress.second.coerceAtLeast(1), progress.first, false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Обработка фотографий",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ход длительной обработки сессий"
            }
        )
    }

    companion object {
        const val ACTION_START = "com.joe6355.astrophoto.action.START_PROCESSING"
        const val ACTION_CANCEL_ALL = "com.joe6355.astrophoto.action.CANCEL_PROCESSING"
        private const val CHANNEL_ID = "session_processing"
        private const val NOTIFICATION_ID = 2101
    }
}
