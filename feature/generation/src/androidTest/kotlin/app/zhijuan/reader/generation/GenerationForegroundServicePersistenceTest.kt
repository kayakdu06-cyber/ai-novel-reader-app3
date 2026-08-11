package app.zhijuan.reader.generation

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import app.zhijuan.core.database.EncryptedZhijuanDatabaseFactory
import app.zhijuan.core.database.generation.GenerationJobSetup
import app.zhijuan.core.database.generation.GenerationJobSetupRepository
import app.zhijuan.core.database.generation.GenerationStageSetup
import app.zhijuan.core.database.generation.GenerationStateRepository
import app.zhijuan.core.database.generation.StoredGenerationJobState
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
import app.zhijuan.core.database.ZHIJUAN_DATABASE_NAME
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationForegroundServicePersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun notificationPausePersistsBeforeServiceStopsAndDoesNotCreateAnAttempt() = runBlocking {
        val fixture = seedReadyJob()
        try {
            assertTrue(GenerationForegroundService.requestStart(context, fixture.jobId))
            delay(300L)

            ContextCompat.startForegroundService(
                context,
                GenerationForegroundService.commandIntent(
                    context,
                    GenerationForegroundService.ACTION_PAUSE,
                    fixture.jobId,
                ),
            )

            val paused = awaitJobStatus(fixture, GenerationJobStatus.PAUSED)
            assertEquals("USER_PAUSE", paused.pauseOrStopReason)
            assertEquals(0, fixture.states.findStage(fixture.stageId)?.attemptCount)
        } finally {
            context.stopService(
                GenerationForegroundService.commandIntent(
                    context,
                    GenerationForegroundService.ACTION_STOP,
                    fixture.jobId,
                ),
            )
            fixture.handle.close()
        }
    }

    private suspend fun awaitJobStatus(
        fixture: Fixture,
        expected: GenerationJobStatus,
    ): StoredGenerationJobState {
        repeat(50) {
            fixture.states.findJob(fixture.jobId)?.let { job ->
                if (job.status == expected) return job
            }
            delay(100L)
        }
        error("Foreground generation job did not reach the expected state.")
    }

    private suspend fun seedReadyJob(): Fixture {
        val suffix = UUID.randomUUID().toString()
        val snapshotId = "fgs-snapshot-$suffix"
        val bookId = "fgs-book-$suffix"
        val jobId = "fgs-job-$suffix"
        val stageId = "fgs-stage-$suffix"
        val createdAt = System.currentTimeMillis().coerceAtLeast(1L)
        val handle = EncryptedZhijuanDatabaseFactory(context).open(ZHIJUAN_DATABASE_NAME)
        BookCreationRepository(handle.database).create(
            BookCreationSnapshotEntity(
                snapshotId = snapshotId,
                rawInputJson = "{}",
                normalizedInputJson = "{}",
                inferenceProvenanceJson = "{}",
                genrePayloadJson = "{}",
                presentationProfileJson = "{}",
                modelPreferenceJson = "{}",
                schemaVersion = 1,
                promptBundleVersion = "prompt-1",
                contentControlSchemaVersion = 1,
                contentHash = "a".repeat(64),
                createdAt = createdAt,
            ),
            BookEntity(
                bookId = bookId,
                creationSnapshotId = snapshotId,
                title = "前台服务测试书",
                titleSource = TitleSource.USER,
                status = BookStatus.DRAFT,
                lengthMode = BookLengthMode.SHORT,
                targetCharacters = null,
                targetChapters = 80,
                minimumChapters = 80,
                lengthPolicySchemaVersion = 1,
                createdAt = createdAt,
                updatedAt = createdAt,
            ),
        )
        GenerationJobSetupRepository(handle.database).create(
            GenerationJobSetup(
                jobId = jobId,
                bookId = bookId,
                jobType = GenerationJobType.CREATE_BOOK,
                userIntentJson = "{}",
                budgetSnapshotJson = "{\"schema\":1}",
                promptBundleVersion = "prompt-1",
                stages = listOf(
                    GenerationStageSetup(
                        stageId = stageId,
                        phase = GenerationPhase.DRAFT_CHAPTER,
                        targetType = GenerationTargetType.CHAPTER,
                        targetId = "fgs-target-$suffix",
                        inputVersionHash = "fgs-input-$suffix",
                        idempotencyKey = "fgs-idempotency-$suffix",
                        maxAttempts = 3,
                        inputSourcesJson = "[]",
                    ),
                ),
                createdAt = createdAt,
            ),
        )
        val states = GenerationStateRepository(handle.database)
        states.transitionJob(
            jobId = jobId,
            expectedStatus = GenerationJobStatus.CREATED,
            event = JobEvent.VALIDATION_PASSED,
            updatedAt = createdAt + 1L,
        )
        return Fixture(handle, states, jobId, stageId)
    }

    private data class Fixture(
        val handle: app.zhijuan.core.database.EncryptedZhijuanDatabaseHandle,
        val states: GenerationStateRepository,
        val jobId: String,
        val stageId: String,
    )
}
