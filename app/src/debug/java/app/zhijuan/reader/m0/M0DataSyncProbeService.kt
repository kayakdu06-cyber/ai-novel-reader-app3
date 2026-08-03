package app.zhijuan.reader.m0

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Debug-only probe. It performs no network request and is not present in release builds. */
class M0DataSyncProbeService : Service() {
    override fun onCreate() {
        super.onCreate()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "织卷后台实验",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForeground(
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        M0RecoveryProbeState.mark(
            context = this,
            state = M0RecoveryProbeState.STATE_RUNNING,
            event = "FOREGROUND_SERVICE_STARTED",
            foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        return START_NOT_STICKY
    }

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        M0RecoveryProbeState.mark(
            context = this,
            state = M0RecoveryProbeState.STATE_PAUSED_BY_SYSTEM_TIMEOUT,
            event = "SERVICE_ON_TIMEOUT",
            foregroundServiceType = fgsType,
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("织卷后台实验")
        .setContentText("正在验证安全停止与恢复")
        .setOngoing(true)
        .build()

    private companion object {
        const val CHANNEL_ID = "m0_background_probe"
        const val NOTIFICATION_ID = 10_006
    }
}
