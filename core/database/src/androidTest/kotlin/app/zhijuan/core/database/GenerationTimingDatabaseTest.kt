package app.zhijuan.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.generation.GenerationTimingRepository
import app.zhijuan.core.database.generation.GenerationTimingWriteDisposition
import app.zhijuan.core.diagnostics.GenerationTimingDuration
import app.zhijuan.core.diagnostics.GenerationTimingEvent
import app.zhijuan.core.diagnostics.GenerationTimingEventFactory
import app.zhijuan.core.diagnostics.GenerationTimingMark
import app.zhijuan.core.diagnostics.GenerationTimingMilestone
import app.zhijuan.core.diagnostics.GenerationTimingOutcome
import app.zhijuan.core.diagnostics.GenerationTimingPhase
import app.zhijuan.core.diagnostics.GenerationTimingReporter
import app.zhijuan.core.diagnostics.GenerationTimingUnavailableReason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerationTimingDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val factory = GenerationTimingEventFactory()
    private lateinit var database: ZhijuanDatabase
    private lateinit var repository: GenerationTimingRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(LibraryDatabaseGuards.callback)
            .build()
            .also { it.openHelper.writableDatabase }
        repository = GenerationTimingRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun persistedTimelineReplaysExactlyReportsEveryDurationAndContainsNoRawCanary() = runBlocking {
        val timeline = fullTimeline()
        timeline.forEach { event ->
            assertEquals(GenerationTimingWriteDisposition.INSERTED, repository.record(event).disposition)
        }
        assertEquals(
            GenerationTimingWriteDisposition.REPLAYED,
            repository.record(timeline.first()).disposition,
        )
        assertEquals(timeline.size.toLong(), repository.countForRun(RUN_CANARY))
        val restored = repository.eventsForRun(RUN_CANARY)
        assertEquals(timeline.toSet(), restored.toSet())

        val report = GenerationTimingReporter().report(restored)
        assertEquals(GenerationTimingDuration.Available(10L), report.queue)
        assertEquals(GenerationTimingDuration.Available(10L), report.localPreparation)
        assertEquals(GenerationTimingDuration.Available(10L), report.providerToFirstByte)
        assertEquals(GenerationTimingDuration.Available(30L), report.providerToFirstParagraph)
        assertEquals(GenerationTimingDuration.Available(100L), report.bodyStream)
        assertEquals(GenerationTimingDuration.Available(90L), report.derivedTotal)
        assertEquals(GenerationTimingDuration.Available(10L), report.commit)
        assertEquals(GenerationTimingDuration.Available(250L), report.total)

        val canaries = listOf(
            RUN_CANARY,
            BOOK_CANARY,
            JOB_CANARY,
            STAGE_CANARY,
            ATTEMPT_CANARY,
            CONNECTION_CANARY,
            MODEL_CANARY,
            BODY_CANARY,
            CHARACTER_CANARY,
            PROMPT_CANARY,
            ENDPOINT_CANARY,
            SECRET_CANARY,
            RAW_HASH_CANARY,
        )
        database.openHelper.readableDatabase.query("SELECT * FROM generation_timing_event").use { cursor ->
            while (cursor.moveToNext()) {
                repeat(cursor.columnCount) { column ->
                    if (cursor.getType(column) == android.database.Cursor.FIELD_TYPE_STRING) {
                        val value = cursor.getString(column)
                        canaries.forEach { canary -> assertFalse(value.contains(canary)) }
                    }
                }
            }
        }
    }

    @Test
    fun conflictingReplayPredecessorGapAndClockRegressionFailClosed() = runBlocking {
        val requested = event(GenerationTimingMilestone.CHAPTER_REQUESTED, 10L, attempt = false)
        repository.record(requested)

        val conflicting = event(GenerationTimingMilestone.CHAPTER_REQUESTED, 11L, attempt = false)
        assertNotNull(runCatching { repository.record(conflicting) }.exceptionOrNull())
        assertEquals(1L, repository.countForRun(RUN_CANARY))

        val stageStarted = event(GenerationTimingMilestone.STAGE_STARTED, 20L, attempt = false)
        assertNotNull(runCatching { repository.record(stageStarted) }.exceptionOrNull())
        assertEquals(1L, repository.countForRun(RUN_CANARY))

        repository.record(event(GenerationTimingMilestone.STAGE_QUEUED, 30L, attempt = false))
        val backwardsStart = event(GenerationTimingMilestone.STAGE_STARTED, 29L, attempt = false)
        assertNotNull(runCatching { repository.record(backwardsStart) }.exceptionOrNull())
        assertEquals(2L, repository.countForRun(RUN_CANARY))
    }

    @Test
    fun databaseRejectsAValidLookingEventWithTheWrongMilestonePhase() = runBlocking {
        val requested = event(GenerationTimingMilestone.CHAPTER_REQUESTED, 10L, attempt = false)
        repository.record(requested)

        val failure = runCatching {
            database.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO generation_timing_event (
                    event_id, phase, milestone, outcome,
                    occurred_epoch_millis, occurred_elapsed_realtime_millis,
                    boot_fingerprint, run_fingerprint, book_fingerprint,
                    job_fingerprint, stage_fingerprint, attempt_fingerprint, attempt_no,
                    character_count, input_token_count, output_token_count, total_token_count,
                    connection_fingerprint, model_fingerprint
                ) VALUES (?, 'CONTEXT', 'CHAPTER_REQUESTED', NULL, 1011, 11, ?, ?, ?,
                    NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)
                """.trimIndent(),
                arrayOf(
                    INVALID_EVENT_ID,
                    BOOT,
                    requested.correlations.runFingerprint,
                    requested.correlations.bookFingerprint,
                ),
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(1L, repository.countForRun(RUN_CANARY))
    }

    @Test
    fun immutableGuardsRejectUpdateDeleteAndCrossBootDurationIsUnavailable() = runBlocking {
        val requested = event(GenerationTimingMilestone.CHAPTER_REQUESTED, 10L, attempt = false)
        repository.record(requested)
        val databaseHandle = database.openHelper.writableDatabase
        assertNotNull(
            runCatching {
                databaseHandle.execSQL(
                    "UPDATE generation_timing_event SET occurred_epoch_millis = 999 WHERE event_id = ?",
                    arrayOf(requested.eventId),
                )
            }.exceptionOrNull(),
        )
        assertNotNull(
            runCatching {
                databaseHandle.execSQL(
                    "DELETE FROM generation_timing_event WHERE event_id = ?",
                    arrayOf(requested.eventId),
                )
            }.exceptionOrNull(),
        )

        repository.record(event(GenerationTimingMilestone.STAGE_QUEUED, 20L, attempt = false))
        repository.record(event(GenerationTimingMilestone.STAGE_STARTED, 1L, attempt = false, boot = OTHER_BOOT))
        repository.record(event(GenerationTimingMilestone.LOCAL_CONTEXT_READY, 2L, attempt = false, boot = OTHER_BOOT))
        repository.record(event(GenerationTimingMilestone.COMMIT_STARTED, 3L, attempt = false, boot = OTHER_BOOT))
        repository.record(
            event(
                GenerationTimingMilestone.FORMAL_COMMIT,
                4L,
                GenerationTimingOutcome.SUCCEEDED,
                attempt = false,
                boot = OTHER_BOOT,
            ),
        )
        val report = GenerationTimingReporter().report(repository.eventsForRun(RUN_CANARY))
        assertEquals(
            GenerationTimingDuration.Unavailable(GenerationTimingUnavailableReason.DIFFERENT_BOOT_SESSION),
            report.total,
        )
        assertEquals(6L, database.generationTimingDao().countForRun(requested.correlations.runFingerprint))
    }

    private fun fullTimeline(): List<GenerationTimingEvent> = listOf(
        event(GenerationTimingMilestone.CHAPTER_REQUESTED, 0L, attempt = false),
        event(GenerationTimingMilestone.STAGE_QUEUED, 5L, attempt = false),
        event(GenerationTimingMilestone.STAGE_STARTED, 15L, attempt = false),
        event(GenerationTimingMilestone.LOCAL_CONTEXT_READY, 25L, attempt = false),
        event(GenerationTimingMilestone.PROVIDER_OPENED, 30L),
        event(GenerationTimingMilestone.FIRST_BYTE, 40L),
        event(GenerationTimingMilestone.FIRST_FULL_PARAGRAPH, 60L, characterCount = 120L),
        event(
            GenerationTimingMilestone.BODY_STREAM_ENDED,
            130L,
            GenerationTimingOutcome.SUCCEEDED,
            characterCount = 3_000L,
        ),
        event(GenerationTimingMilestone.MEMORY_STARTED, 135L, attempt = false),
        event(GenerationTimingMilestone.MEMORY_ENDED, 155L, GenerationTimingOutcome.SUCCEEDED, attempt = false),
        event(GenerationTimingMilestone.TRACKING_STARTED, 160L, attempt = false),
        event(GenerationTimingMilestone.TRACKING_ENDED, 190L, GenerationTimingOutcome.SUCCEEDED, attempt = false),
        event(GenerationTimingMilestone.CONSISTENCY_STARTED, 195L, attempt = false),
        event(GenerationTimingMilestone.CONSISTENCY_ENDED, 235L, GenerationTimingOutcome.SUCCEEDED, attempt = false),
        event(GenerationTimingMilestone.COMMIT_STARTED, 240L, attempt = false),
        event(GenerationTimingMilestone.FORMAL_COMMIT, 250L, GenerationTimingOutcome.SUCCEEDED, attempt = false),
        event(GenerationTimingMilestone.NEXT_CHAPTER_STARTED, 260L, attempt = false),
    )

    private fun event(
        milestone: GenerationTimingMilestone,
        elapsed: Long,
        outcome: GenerationTimingOutcome? = null,
        attempt: Boolean = milestone in ATTEMPT_EVENTS,
        boot: String = BOOT,
        characterCount: Long? = null,
    ): GenerationTimingEvent = factory.create(
        phase = phaseFor(milestone),
        milestone = milestone,
        mark = GenerationTimingMark(
            epochMillis = 1_000L + elapsed,
            elapsedRealtimeMillis = elapsed,
            bootFingerprint = boot,
        ),
        runId = RUN_CANARY,
        bookId = BOOK_CANARY,
        jobId = if (milestone in RUN_ONLY_EVENTS) null else JOB_CANARY,
        stageId = if (milestone in RUN_ONLY_EVENTS) null else STAGE_CANARY,
        attemptId = if (attempt) ATTEMPT_CANARY else null,
        attemptNo = if (attempt) 1 else null,
        outcome = outcome,
        characterCount = characterCount,
        inputTokenCount = if (milestone == GenerationTimingMilestone.BODY_STREAM_ENDED) 100L else null,
        outputTokenCount = if (milestone == GenerationTimingMilestone.BODY_STREAM_ENDED) 200L else null,
        totalTokenCount = if (milestone == GenerationTimingMilestone.BODY_STREAM_ENDED) 300L else null,
        connectionId = if (attempt) CONNECTION_CANARY else null,
        modelId = if (attempt) MODEL_CANARY else null,
    )

    private fun phaseFor(milestone: GenerationTimingMilestone): GenerationTimingPhase = when (milestone) {
        GenerationTimingMilestone.CHAPTER_REQUESTED,
        GenerationTimingMilestone.NEXT_CHAPTER_STARTED,
        -> GenerationTimingPhase.CHAPTER

        GenerationTimingMilestone.STAGE_QUEUED,
        GenerationTimingMilestone.STAGE_STARTED,
        GenerationTimingMilestone.LOCAL_CONTEXT_READY,
        -> GenerationTimingPhase.CONTEXT

        GenerationTimingMilestone.PROVIDER_OPENED,
        GenerationTimingMilestone.FIRST_BYTE,
        GenerationTimingMilestone.FIRST_FULL_PARAGRAPH,
        GenerationTimingMilestone.BODY_STREAM_ENDED,
        -> GenerationTimingPhase.BODY

        GenerationTimingMilestone.MEMORY_STARTED,
        GenerationTimingMilestone.MEMORY_ENDED,
        -> GenerationTimingPhase.MEMORY

        GenerationTimingMilestone.TRACKING_STARTED,
        GenerationTimingMilestone.TRACKING_ENDED,
        -> GenerationTimingPhase.TRACKING

        GenerationTimingMilestone.CONSISTENCY_STARTED,
        GenerationTimingMilestone.CONSISTENCY_ENDED,
        -> GenerationTimingPhase.CONSISTENCY

        GenerationTimingMilestone.REVISION_STARTED,
        GenerationTimingMilestone.REVISION_ENDED,
        -> GenerationTimingPhase.REVISION

        GenerationTimingMilestone.COMMIT_STARTED,
        GenerationTimingMilestone.FORMAL_COMMIT,
        -> GenerationTimingPhase.COMMIT
    }

    private companion object {
        const val BOOT = "111111111111111111111111"
        const val OTHER_BOOT = "222222222222222222222222"
        const val RUN_CANARY = "run-raw-id-timing-canary"
        const val BOOK_CANARY = "book-raw-id-timing-canary"
        const val JOB_CANARY = "job-raw-id-timing-canary"
        const val STAGE_CANARY = "stage-raw-id-timing-canary"
        const val ATTEMPT_CANARY = "attempt-raw-id-timing-canary"
        const val CONNECTION_CANARY = "connection-raw-id-timing-canary"
        const val MODEL_CANARY = "model-raw-id-timing-canary"
        const val BODY_CANARY = "正文_TIMING_DATABASE_CANARY"
        const val CHARACTER_CANARY = "人物_TIMING_DATABASE_CANARY"
        const val PROMPT_CANARY = "提示词_TIMING_DATABASE_CANARY"
        const val ENDPOINT_CANARY = "https://timing-database.invalid/private"
        val SECRET_CANARY = String(charArrayOf('s', 'k', '-')) + "timing-database-secret-canary"
        const val RAW_HASH_CANARY = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val INVALID_EVENT_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val ATTEMPT_EVENTS = setOf(
            GenerationTimingMilestone.PROVIDER_OPENED,
            GenerationTimingMilestone.FIRST_BYTE,
            GenerationTimingMilestone.FIRST_FULL_PARAGRAPH,
            GenerationTimingMilestone.BODY_STREAM_ENDED,
        )
        val RUN_ONLY_EVENTS = setOf(
            GenerationTimingMilestone.CHAPTER_REQUESTED,
            GenerationTimingMilestone.NEXT_CHAPTER_STARTED,
        )
    }
}
