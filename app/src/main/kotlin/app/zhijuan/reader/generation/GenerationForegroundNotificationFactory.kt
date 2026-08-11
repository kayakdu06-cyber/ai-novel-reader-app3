package app.zhijuan.reader.generation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.reader.MainActivity
import app.zhijuan.reader.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class GenerationForegroundNotificationFactory @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val context = context.applicationContext

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.generation_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.generation_notification_channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun create(
        jobId: String?,
        status: GenerationJobStatus?,
    ): Notification {
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_zhijuan_notification)
            .setContentTitle(context.getString(R.string.generation_notification_title))
            .setContentText(context.getString(status.notificationText()))
            .setContentIntent(openAppIntent())
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setProgress(0, 0, true)

        if (jobId != null) {
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.generation_notification_pause),
                    serviceActionIntent(
                        requestCode = PAUSE_REQUEST_CODE,
                        action = GenerationForegroundService.ACTION_PAUSE,
                        jobId = jobId,
                    ),
                ).build(),
            )
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.generation_notification_stop),
                    serviceActionIntent(
                        requestCode = STOP_REQUEST_CODE,
                        action = GenerationForegroundService.ACTION_STOP,
                        jobId = jobId,
                    ),
                ).build(),
            )
        }
        return builder.build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        OPEN_APP_REQUEST_CODE,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun serviceActionIntent(
        requestCode: Int,
        action: String,
        jobId: String,
    ): PendingIntent = PendingIntent.getForegroundService(
        context,
        requestCode,
        GenerationForegroundService.commandIntent(context, action, jobId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun GenerationJobStatus?.notificationText(): Int = when (this) {
        GenerationJobStatus.PAUSING -> R.string.generation_notification_pausing
        GenerationJobStatus.STOPPING -> R.string.generation_notification_stopping
        GenerationJobStatus.RUNNING -> R.string.generation_notification_running
        else -> R.string.generation_notification_preparing
    }

    companion object {
        const val CHANNEL_ID = "generation_progress"
        const val NOTIFICATION_ID = 48_001
        private const val OPEN_APP_REQUEST_CODE = 48_010
        private const val PAUSE_REQUEST_CODE = 48_011
        private const val STOP_REQUEST_CODE = 48_012
    }
}
