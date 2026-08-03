package app.zhijuan.reader.generation

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.zhijuan.core.model.GenerationJobStatus
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@AndroidEntryPoint
class GenerationForegroundService : Service() {
    @Inject
    internal lateinit var gateway: ForegroundGenerationGateway

    @Inject
    internal lateinit var notificationFactory: GenerationForegroundNotificationFactory

    @Inject
    internal lateinit var timeoutCoordinator: ForegroundGenerationTimeoutCoordinator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var processor: ForegroundGenerationCommandProcessor? = null
    private var monitorJob: Job? = null
    private var activeJobId: String? = null
    private var latestStartId: Int = 0
    private var controlDeadlineElapsed: Long? = null
    private var foregroundStarted = false
    private val timeoutStopStarted = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        notificationFactory.createChannel()
        processor = ForegroundGenerationCommandProcessor(gateway)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        latestStartId = maxOf(latestStartId, startId)
        val requestedJobId = intent?.getStringExtra(EXTRA_JOB_ID)
        val promotedJobId = activeJobId ?: requestedJobId?.takeIf(JOB_ID::matches)
        promote(promotedJobId, status = null)

        val command = intent?.action.toCommandOrNull()
        if (command == null || requestedJobId == null || !JOB_ID.matches(requestedJobId)) {
            stopService(startId, force = false)
            return START_NOT_STICKY
        }
        val currentJobId = activeJobId
        if (currentJobId != null && currentJobId != requestedJobId) {
            return START_NOT_STICKY
        }
        activeJobId = requestedJobId
        serviceScope.launch {
            commandMutex.withLock {
                handleCommand(requestedJobId, command)
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        val jobId = activeJobId
        if (jobId == null || !timeoutStopStarted.compareAndSet(false, true)) {
            stopService(startId, force = true)
            return
        }
        promote(jobId, GenerationJobStatus.PAUSING)
        val hardStop = Runnable { stopService(startId, force = true) }
        mainHandler.postDelayed(hardStop, SYSTEM_TIMEOUT_STOP_DEADLINE_MILLIS)
        timeoutCoordinator.persistTimeoutPause(
            jobId = jobId,
            requestedAt = System.currentTimeMillis().coerceAtLeast(0L),
        ) {
            mainHandler.post {
                mainHandler.removeCallbacks(hardStop)
                stopService(startId, force = true)
            }
        }
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun handleCommand(
        jobId: String,
        command: ForegroundGenerationCommand,
    ) {
        val currentProcessor = requireNotNull(processor)
        val result = runCatching {
            currentProcessor.handle(
                jobId = jobId,
                command = command,
                requestedAt = System.currentTimeMillis().coerceAtLeast(0L),
            )
        }.getOrElse {
            stopService(latestStartId, force = false)
            return
        }
        promote(jobId, result.status)
        if (command == ForegroundGenerationCommand.PAUSE ||
            command == ForegroundGenerationCommand.STOP
        ) {
            controlDeadlineElapsed = SystemClock.elapsedRealtime() + CONTROL_SAFE_POINT_GRACE_MILLIS
        }
        if (result.directive == ForegroundGenerationDirective.STOP_SERVICE) {
            stopService(latestStartId, force = false)
        } else {
            startMonitor(jobId)
        }
    }

    private fun startMonitor(jobId: String) {
        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            val currentProcessor = requireNotNull(processor)
            while (isActive && activeJobId == jobId) {
                delay(MONITOR_INTERVAL_MILLIS)
                val result = runCatching {
                    currentProcessor.handle(
                        jobId = jobId,
                        command = ForegroundGenerationCommand.RECHECK,
                        requestedAt = System.currentTimeMillis().coerceAtLeast(0L),
                    )
                }.getOrElse {
                    stopService(latestStartId, force = false)
                    return@launch
                }
                promote(jobId, result.status)
                val graceExpired = controlDeadlineElapsed?.let { deadline ->
                    SystemClock.elapsedRealtime() >= deadline
                } ?: false
                if (result.directive == ForegroundGenerationDirective.STOP_SERVICE || graceExpired) {
                    stopService(latestStartId, force = false)
                    return@launch
                }
            }
        }
    }

    private fun promote(
        jobId: String?,
        status: GenerationJobStatus?,
    ) {
        val notification = notificationFactory.create(jobId, status)
        if (!foregroundStarted) {
            ServiceCompat.startForeground(
                this,
                GenerationForegroundNotificationFactory.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            foregroundStarted = true
        } else {
            getSystemService(android.app.NotificationManager::class.java).notify(
                GenerationForegroundNotificationFactory.NOTIFICATION_ID,
                notification,
            )
        }
    }

    private fun stopService(
        startId: Int,
        force: Boolean,
    ) {
        monitorJob?.cancel()
        monitorJob = null
        activeJobId = null
        controlDeadlineElapsed = null
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        if (force) stopSelf() else stopSelf(startId)
    }

    private fun String?.toCommandOrNull(): ForegroundGenerationCommand? = when (this) {
        ACTION_START -> ForegroundGenerationCommand.START
        ACTION_PAUSE -> ForegroundGenerationCommand.PAUSE
        ACTION_STOP -> ForegroundGenerationCommand.STOP
        else -> null
    }

    companion object {
        internal const val ACTION_START = "app.zhijuan.reader.generation.action.START"
        internal const val ACTION_PAUSE = "app.zhijuan.reader.generation.action.PAUSE"
        internal const val ACTION_STOP = "app.zhijuan.reader.generation.action.STOP"
        internal const val EXTRA_JOB_ID = "app.zhijuan.reader.generation.extra.JOB_ID"
        private val JOB_ID = Regex("[A-Za-z0-9._:-]{1,128}")
        private const val MONITOR_INTERVAL_MILLIS = 1_000L
        private const val CONTROL_SAFE_POINT_GRACE_MILLIS = 5_000L
        private const val SYSTEM_TIMEOUT_STOP_DEADLINE_MILLIS = 1_500L

        fun requestStart(context: Context, jobId: String): Boolean {
            if (!JOB_ID.matches(jobId)) return false
            return runCatching {
                ContextCompat.startForegroundService(
                    context,
                    commandIntent(context, ACTION_START, jobId),
                )
                true
            }.getOrDefault(false)
        }

        internal fun commandIntent(
            context: Context,
            action: String,
            jobId: String,
        ): Intent = Intent(context, GenerationForegroundService::class.java).apply {
            this.action = action
            putExtra(EXTRA_JOB_ID, jobId)
        }
    }
}
