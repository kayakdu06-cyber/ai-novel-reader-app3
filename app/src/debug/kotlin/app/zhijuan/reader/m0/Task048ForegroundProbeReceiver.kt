package app.zhijuan.reader.m0

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.zhijuan.core.database.EncryptedZhijuanDatabaseFactory
import app.zhijuan.core.database.generation.GenerationJobSetup
import app.zhijuan.core.database.generation.GenerationJobSetupRepository
import app.zhijuan.core.database.generation.GenerationStageSetup
import app.zhijuan.core.database.generation.GenerationStateRepository
import app.zhijuan.core.database.library.BookCreationRepository
import app.zhijuan.core.database.library.BookCreationSnapshotEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.TitleSource
import app.zhijuan.core.task.JobEvent
import app.zhijuan.reader.storage.ZHIJUAN_DATABASE_NAME
import app.zhijuan.reader.generation.GenerationForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Debug-only fixed fixture for the API 35 production-service timeout probe. */
class Task048ForegroundProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_SEED -> seed(context.applicationContext)
                    ACTION_QUERY -> query(context.applicationContext)
                    ACTION_START -> start(context.applicationContext)
                    else -> mark(context, "INVALID_ACTION", null)
                }
            } catch (error: Exception) {
                mark(context, "ERROR_${error.javaClass.simpleName}", null)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun seed(context: Context) {
        EncryptedZhijuanDatabaseFactory(context).open(ZHIJUAN_DATABASE_NAME).use { handle ->
            val now = System.currentTimeMillis().coerceAtLeast(1L)
            BookCreationRepository(handle.database).create(
                BookCreationSnapshotEntity(
                    snapshotId = SNAPSHOT_ID,
                    rawInputJson = "{}",
                    normalizedInputJson = "{}",
                    inferenceProvenanceJson = "{}",
                    genrePayloadJson = "{}",
                    presentationProfileJson = "{}",
                    modelPreferenceJson = "{}",
                    schemaVersion = 1,
                    promptBundleVersion = "prompt-1",
                    contentControlSchemaVersion = 1,
                    contentHash = "b".repeat(64),
                    createdAt = now,
                ),
                BookEntity(
                    bookId = BOOK_ID,
                    creationSnapshotId = SNAPSHOT_ID,
                    title = "前台超时探针",
                    titleSource = TitleSource.USER,
                    status = BookStatus.DRAFT,
                    lengthMode = BookLengthMode.SHORT,
                    targetCharacters = null,
                    targetChapters = 80,
                    minimumChapters = 80,
                    lengthPolicySchemaVersion = 1,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            GenerationJobSetupRepository(handle.database).create(
                GenerationJobSetup(
                    jobId = JOB_ID,
                    bookId = BOOK_ID,
                    jobType = GenerationJobType.CREATE_BOOK,
                    userIntentJson = "{}",
                    budgetSnapshotJson = "{\"schema\":1}",
                    promptBundleVersion = "prompt-1",
                    stages = listOf(
                        GenerationStageSetup(
                            stageId = STAGE_ID,
                            phase = GenerationPhase.DRAFT_CHAPTER,
                            targetType = GenerationTargetType.CHAPTER,
                            targetId = TARGET_ID,
                            inputVersionHash = "task048-probe-input",
                            idempotencyKey = "task048-probe-idempotency",
                            maxAttempts = 3,
                            inputSourcesJson = "[]",
                        ),
                    ),
                    createdAt = now,
                ),
            )
            GenerationStateRepository(handle.database).transitionJob(
                jobId = JOB_ID,
                expectedStatus = GenerationJobStatus.CREATED,
                event = JobEvent.VALIDATION_PASSED,
                updatedAt = now + 1L,
            )
        }
        mark(context, GenerationJobStatus.READY.name, null)
    }

    private suspend fun query(context: Context) {
        EncryptedZhijuanDatabaseFactory(context).open(ZHIJUAN_DATABASE_NAME).use { handle ->
            val job = GenerationStateRepository(handle.database).findJob(JOB_ID)
            mark(context, job?.status?.name ?: "MISSING", job?.pauseOrStopReason)
        }
    }

    private fun start(context: Context) {
        val requested = GenerationForegroundService.requestStart(context, JOB_ID)
        mark(context, if (requested) "START_REQUESTED" else "START_REJECTED", null)
    }

    private fun mark(
        context: Context,
        status: String,
        reason: String?,
    ) {
        val editor = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("status", status)
            .putLong("updated_at", System.currentTimeMillis())
        if (reason == null) editor.remove("reason") else editor.putString("reason", reason)
        check(editor.commit()) { "Unable to persist the foreground-service probe result." }
    }

    companion object {
        const val ACTION_SEED = "app.zhijuan.reader.debug.task048.SEED"
        const val ACTION_QUERY = "app.zhijuan.reader.debug.task048.QUERY"
        const val ACTION_START = "app.zhijuan.reader.debug.task048.START"
        const val PREFERENCES_NAME = "task048-foreground-probe"
        const val JOB_ID = "task048-probe-job"
        private const val BOOK_ID = "task048-probe-book"
        private const val SNAPSHOT_ID = "task048-probe-snapshot"
        private const val STAGE_ID = "task048-probe-stage"
        private const val TARGET_ID = "task048-probe-target"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
