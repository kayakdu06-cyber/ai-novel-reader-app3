package app.zhijuan.core.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GenerationTimingTest {
    private val factory = GenerationTimingEventFactory()

    @Test
    fun rawIdentitiesAndSensitiveCanariesNeverSurviveInTheEvent() {
        val event = event(
            GenerationTimingMilestone.BODY_STREAM_ENDED,
            elapsed = 100L,
            outcome = GenerationTimingOutcome.SUCCEEDED,
            characterCount = 3_000L,
            runId = NOVEL_CANARY,
            bookId = CHARACTER_CANARY,
            connectionId = ENDPOINT_CANARY,
            modelId = SECRET_CANARY,
        )

        val rendered = event.toString()
        listOf(NOVEL_CANARY, CHARACTER_CANARY, ENDPOINT_CANARY, SECRET_CANARY).forEach { canary ->
            assertFalse(rendered.contains(canary))
            assertFalse(event.allPersistableValues().any { value -> value.contains(canary) })
        }
        assertTrue(event.correlations.runFingerprint.matches(Regex("[0-9a-f]{24}")))
        assertTrue(requireNotNull(event.connectionFingerprint).matches(Regex("[0-9a-f]{24}")))
        assertNotEquals(event.correlations.runFingerprint, event.correlations.bookFingerprint)
    }

    @Test
    fun logicalEventIdentityIsDeterministicButConflictingEvidenceRemainsVisible() {
        val first = event(
            GenerationTimingMilestone.FIRST_BYTE,
            elapsed = 20L,
        )
        val replay = event(
            GenerationTimingMilestone.FIRST_BYTE,
            elapsed = 20L,
        )
        val conflictingTime = event(
            GenerationTimingMilestone.FIRST_BYTE,
            elapsed = 21L,
        )

        assertEquals(first, replay)
        assertEquals(first.eventId, conflictingTime.eventId)
        assertNotEquals(first, conflictingTime)
    }

    @Test
    fun completeReportSeparatesQueuePreparationProviderDerivedCommitAndTotal() {
        val events = listOf(
            event(GenerationTimingMilestone.CHAPTER_REQUESTED, 0L, attempt = false),
            event(GenerationTimingMilestone.STAGE_QUEUED, 5L, attempt = false),
            event(GenerationTimingMilestone.STAGE_STARTED, 15L, attempt = false),
            event(GenerationTimingMilestone.LOCAL_CONTEXT_READY, 25L, attempt = false),
            event(GenerationTimingMilestone.PROVIDER_OPENED, 30L),
            event(GenerationTimingMilestone.FIRST_BYTE, 40L),
            event(GenerationTimingMilestone.FIRST_FULL_PARAGRAPH, 60L),
            event(
                GenerationTimingMilestone.BODY_STREAM_ENDED,
                130L,
                GenerationTimingOutcome.SUCCEEDED,
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

        val report = GenerationTimingReporter().report(events)

        assertAvailable(10L, report.queue)
        assertAvailable(10L, report.localPreparation)
        assertAvailable(10L, report.providerToFirstByte)
        assertAvailable(30L, report.providerToFirstParagraph)
        assertAvailable(100L, report.bodyStream)
        assertAvailable(20L, report.memory)
        assertAvailable(30L, report.tracking)
        assertAvailable(40L, report.consistency)
        assertEquals(GenerationTimingDuration.NotApplicable, report.revision)
        assertAvailable(90L, report.derivedTotal)
        assertAvailable(10L, report.commit)
        assertAvailable(250L, report.total)
        assertAvailable(10L, report.nextChapterDelay)
    }

    @Test
    fun stageMilestonesFromAnotherPhaseCannotContaminateContextDurations() {
        val contextQueued = event(GenerationTimingMilestone.STAGE_QUEUED, 5L, attempt = false)
        val bodyQueued = event(
            GenerationTimingMilestone.STAGE_QUEUED,
            100L,
            attempt = false,
            phase = GenerationTimingPhase.BODY,
        )
        assertNotEquals(contextQueued.eventId, bodyQueued.eventId)

        val report = GenerationTimingReporter().report(
            listOf(
                contextQueued,
                event(GenerationTimingMilestone.STAGE_STARTED, 15L, attempt = false),
                event(GenerationTimingMilestone.LOCAL_CONTEXT_READY, 25L, attempt = false),
                bodyQueued,
                event(
                    GenerationTimingMilestone.STAGE_STARTED,
                    140L,
                    attempt = false,
                    phase = GenerationTimingPhase.BODY,
                ),
            ),
        )

        assertAvailable(10L, report.queue)
        assertAvailable(10L, report.localPreparation)
    }

    @Test
    fun missingAndCrossBootEvidenceNeverProduceGuessedOrNegativeDurations() {
        val missing = GenerationTimingReporter().report(
            listOf(event(GenerationTimingMilestone.CHAPTER_REQUESTED, 10L, attempt = false)),
        )
        assertEquals(
            GenerationTimingDuration.Unavailable(GenerationTimingUnavailableReason.MISSING_EVENT),
            missing.total,
        )

        val crossBoot = GenerationTimingReporter().report(
            listOf(
                event(GenerationTimingMilestone.CHAPTER_REQUESTED, 10L, attempt = false),
                event(
                    milestone = GenerationTimingMilestone.FORMAL_COMMIT,
                    elapsed = 1L,
                    outcome = GenerationTimingOutcome.SUCCEEDED,
                    attempt = false,
                    boot = OTHER_BOOT,
                ),
            ),
        )
        assertEquals(
            GenerationTimingDuration.Unavailable(GenerationTimingUnavailableReason.DIFFERENT_BOOT_SESSION),
            crossBoot.total,
        )

        val failedBody = GenerationTimingReporter().report(
            listOf(
                event(GenerationTimingMilestone.PROVIDER_OPENED, 20L),
                event(
                    GenerationTimingMilestone.BODY_STREAM_ENDED,
                    30L,
                    GenerationTimingOutcome.FAILED_CLOSED,
                ),
            ),
        )
        assertEquals(
            GenerationTimingDuration.Unavailable(
                GenerationTimingUnavailableReason.TERMINAL_OUTCOME_NOT_SUCCESSFUL,
            ),
            failedBody.bodyStream,
        )
    }

    @Test
    fun paragraphTrackerKeepsOnlyCountsAndObservesBoundaryOrCleanBodyEnd() {
        val tracker = CompleteParagraphTimingTracker()
        assertFalse(tracker.observeDecoded("第一段仍在继续"))
        assertTrue(tracker.observeDecoded("。\n第二段"))
        assertFalse(tracker.observeDecoded(NOVEL_CANARY))
        assertTrue(tracker.firstCompleteParagraphObserved)
        assertEquals("第一段仍在继续。\n第二段".codePointCount(0, "第一段仍在继续。\n第二段".length).toLong() + NOVEL_CANARY.length, tracker.decodedCharacterCount)
        assertFalse(tracker.toString().contains(NOVEL_CANARY))

        val singleParagraph = CompleteParagraphTimingTracker()
        assertFalse(singleParagraph.observeDecoded("无换行但完整结束"))
        assertTrue(singleParagraph.completeBody())
        assertFalse(singleParagraph.completeBody())
    }

    private fun event(
        milestone: GenerationTimingMilestone,
        elapsed: Long,
        outcome: GenerationTimingOutcome? = null,
        characterCount: Long? = null,
        runId: String = RUN_ID,
        bookId: String = BOOK_ID,
        connectionId: String? = null,
        modelId: String? = null,
        attempt: Boolean = milestone in ATTEMPT_EVENTS,
        boot: String = BOOT,
        phase: GenerationTimingPhase = phaseFor(milestone),
    ): GenerationTimingEvent = factory.create(
        phase = phase,
        milestone = milestone,
        mark = GenerationTimingMark(
            epochMillis = 1_000L + elapsed,
            elapsedRealtimeMillis = elapsed,
            bootFingerprint = boot,
        ),
        runId = runId,
        bookId = bookId,
        jobId = if (milestone == GenerationTimingMilestone.CHAPTER_REQUESTED ||
            milestone == GenerationTimingMilestone.NEXT_CHAPTER_STARTED
        ) null else JOB_ID,
        stageId = if (milestone == GenerationTimingMilestone.CHAPTER_REQUESTED ||
            milestone == GenerationTimingMilestone.NEXT_CHAPTER_STARTED
        ) null else STAGE_ID,
        attemptId = if (attempt) ATTEMPT_ID else null,
        attemptNo = if (attempt) 1 else null,
        outcome = outcome,
        characterCount = characterCount,
        connectionId = connectionId,
        modelId = modelId,
    )

    private fun GenerationTimingEvent.allPersistableValues(): List<String> = listOfNotNull(
        eventId,
        phase.name,
        milestone.name,
        outcome?.name,
        mark.epochMillis.toString(),
        mark.elapsedRealtimeMillis.toString(),
        mark.bootFingerprint,
        correlations.runFingerprint,
        correlations.bookFingerprint,
        correlations.jobFingerprint,
        correlations.stageFingerprint,
        correlations.attemptFingerprint,
        connectionFingerprint,
        modelFingerprint,
    )

    private fun assertAvailable(expected: Long, actual: GenerationTimingDuration) {
        assertEquals(GenerationTimingDuration.Available(expected), actual)
    }

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
        const val RUN_ID = "run-timing"
        const val BOOK_ID = "book-timing"
        const val JOB_ID = "job-timing"
        const val STAGE_ID = "stage-timing"
        const val ATTEMPT_ID = "attempt-timing"
        const val BOOT = "111111111111111111111111"
        const val OTHER_BOOT = "222222222222222222222222"
        const val NOVEL_CANARY = "正文敏感片段_TIMING_CANARY"
        const val CHARACTER_CANARY = "人物姓名_TIMING_CANARY"
        const val ENDPOINT_CANARY = "https://timing-canary.invalid/private"
        val SECRET_CANARY = String(charArrayOf('s', 'k', '-')) + "timing-secret-canary"
        val ATTEMPT_EVENTS = setOf(
            GenerationTimingMilestone.PROVIDER_OPENED,
            GenerationTimingMilestone.FIRST_BYTE,
            GenerationTimingMilestone.FIRST_FULL_PARAGRAPH,
            GenerationTimingMilestone.BODY_STREAM_ENDED,
        )
    }
}
