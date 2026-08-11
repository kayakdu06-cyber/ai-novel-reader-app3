package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterFinalCandidateCommitResultV1
import app.zhijuan.core.database.generation.GenerationLeaseToken
import app.zhijuan.core.database.generation.StaleGenerationStateException
import app.zhijuan.core.database.generation.StoredGenerationStageState
import app.zhijuan.core.model.GenerationStageStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterFinalCandidateCommitStageExecutorTest {
    @Test
    fun readyStageAcquiresExactLeaseThenCommitsOnce() = runBlocking {
        val trace = mutableListOf<String>()
        val acquiredToken = GenerationLeaseToken(OWNER_ID, REQUESTED_AT)
        val executor = ChapterFinalCandidateCommitStageExecutorV1(
            ChapterFinalCandidateCommitStageExecutorDependenciesV1(
                findStage = {
                    trace += "find"
                    stage(GenerationStageStatus.READY, leaseToken = null, heartbeatAt = null)
                },
                acquireStageLease = { stageId, ownerId, at ->
                    trace += "acquire"
                    assertEquals(FINAL_STAGE_ID, stageId)
                    assertEquals(OWNER_ID, ownerId)
                    assertEquals(REQUESTED_AT, at)
                    stage(
                        status = GenerationStageStatus.PREPARING,
                        leaseToken = acquiredToken,
                        heartbeatAt = at,
                        updatedAt = at,
                    )
                },
                commitFinalCandidate = { stageId, token, at ->
                    trace += "commit"
                    assertEquals(FINAL_STAGE_ID, stageId)
                    assertEquals(acquiredToken, token)
                    assertEquals(REQUESTED_AT, at)
                    COMMIT_RESULT
                },
            ),
        )

        val result = executor.execute(FINAL_STAGE_ID, OWNER_ID, REQUESTED_AT)

        assertEquals(listOf("find", "acquire", "commit"), trace)
        assertEquals(COMMIT_RESULT, (result as ChapterFinalCandidateCommitStageExecutionResultV1.Committed).result)
        assertFalse(result.toString().contains(SENSITIVE_MARKER))
        assertTrue(result.toString().contains("replayed=false"))
    }

    @Test
    fun preparingAndCommittingResumeExactPersistedTokenWithoutAcquire() = runBlocking {
        listOf(GenerationStageStatus.PREPARING, GenerationStageStatus.COMMITTING).forEach { status ->
            val trace = mutableListOf<String>()
            val persistedToken = GenerationLeaseToken(OWNER_ID, 40L)
            val executor = ChapterFinalCandidateCommitStageExecutorV1(
                ChapterFinalCandidateCommitStageExecutorDependenciesV1(
                    findStage = {
                        trace += "find"
                        stage(status, persistedToken, heartbeatAt = 70L, updatedAt = 80L)
                    },
                    acquireStageLease = { _, _, _ ->
                        throw AssertionError("A resumed stage must not acquire another lease.")
                    },
                    commitFinalCandidate = { _, token, at ->
                        trace += "commit"
                        assertSame(persistedToken, token)
                        assertEquals(REQUESTED_AT, at)
                        COMMIT_RESULT
                    },
                ),
            )

            val result = executor.execute(FINAL_STAGE_ID, OWNER_ID, REQUESTED_AT)

            assertTrue(result is ChapterFinalCandidateCommitStageExecutionResultV1.Committed)
            assertEquals(listOf("find", "commit"), trace)
        }
    }

    @Test
    fun boundPreparingAndCommittingUseTheCallerExactTokenWithoutAcquire() = runBlocking {
        listOf(GenerationStageStatus.PREPARING, GenerationStageStatus.COMMITTING).forEach { status ->
            val trace = mutableListOf<String>()
            val exactToken = GenerationLeaseToken(OWNER_ID, 40L)
            val executor = ChapterFinalCandidateCommitStageExecutorV1(
                ChapterFinalCandidateCommitStageExecutorDependenciesV1(
                    findStage = {
                        trace += "find"
                        stage(status, exactToken, heartbeatAt = 70L, updatedAt = 80L)
                    },
                    acquireStageLease = { _, _, _ ->
                        throw AssertionError("A bound Stage must never acquire another lease.")
                    },
                    commitFinalCandidate = { stageId, token, at ->
                        trace += "commit"
                        assertEquals(FINAL_STAGE_ID, stageId)
                        assertSame(exactToken, token)
                        assertEquals(REQUESTED_AT, at)
                        COMMIT_RESULT
                    },
                ),
            )

            val result = executor.executeBound(FINAL_STAGE_ID, exactToken, REQUESTED_AT)

            assertTrue(result is ChapterFinalCandidateCommitStageExecutionResultV1.Committed)
            assertEquals(listOf("find", "commit"), trace)
        }
    }

    @Test
    fun boundExecutionRejectsANewerTokenFromTheSameOwner() = runBlocking {
        val trace = mutableListOf<String>()
        val boundToken = GenerationLeaseToken(OWNER_ID, 40L)
        val newerSameOwnerToken = GenerationLeaseToken(OWNER_ID, 41L)
        val executor = ChapterFinalCandidateCommitStageExecutorV1(
            ChapterFinalCandidateCommitStageExecutorDependenciesV1(
                findStage = {
                    trace += "find"
                    stage(
                        GenerationStageStatus.PREPARING,
                        newerSameOwnerToken,
                        heartbeatAt = 70L,
                        updatedAt = 80L,
                    )
                },
                acquireStageLease = { _, _, _ ->
                    throw AssertionError("A bound Stage must never acquire another lease.")
                },
                commitFinalCandidate = { _, _, _ ->
                    trace += "commit"
                    COMMIT_RESULT
                },
            ),
        )

        val failure = expectFailure {
            executor.executeBound(FINAL_STAGE_ID, boundToken, REQUESTED_AT)
        }

        assertTrue(failure is StaleGenerationStateException)
        assertEquals(listOf("find"), trace)
    }

    @Test
    fun boundExecutionRejectsExpiryBoundaryAndNonLeaseOwnedStatus() = runBlocking {
        val exactToken = GenerationLeaseToken(OWNER_ID, 40L)
        val expiryTrace = mutableListOf<String>()
        val expiredExecutor = ChapterFinalCandidateCommitStageExecutorV1(
            ChapterFinalCandidateCommitStageExecutorDependenciesV1(
                findStage = {
                    expiryTrace += "find"
                    stage(
                        GenerationStageStatus.PREPARING,
                        exactToken,
                        heartbeatAt = 70L,
                        updatedAt = 80L,
                    )
                },
                acquireStageLease = { _, _, _ ->
                    throw AssertionError("A bound Stage must never acquire another lease.")
                },
                commitFinalCandidate = { _, _, _ ->
                    expiryTrace += "commit"
                    COMMIT_RESULT
                },
            ),
        )
        val expired = expectFailure {
            expiredExecutor.executeBound(FINAL_STAGE_ID, exactToken, requestedAt = 60_070L)
        }
        assertTrue(expired is StaleGenerationStateException)
        assertEquals(listOf("find"), expiryTrace)

        val readyExecutor = ChapterFinalCandidateCommitStageExecutorV1(
            ChapterFinalCandidateCommitStageExecutorDependenciesV1(
                findStage = { stage(GenerationStageStatus.READY, leaseToken = null, heartbeatAt = null) },
                acquireStageLease = { _, _, _ ->
                    throw AssertionError("A bound Stage must never acquire another lease.")
                },
                commitFinalCandidate = { _, _, _ -> COMMIT_RESULT },
            ),
        )
        val ready = expectFailure {
            readyExecutor.executeBound(FINAL_STAGE_ID, exactToken, REQUESTED_AT)
        }
        assertTrue(ready is StaleGenerationStateException)
    }

    @Test
    fun boundExecutionObservesAlreadySucceededWithoutLeaseOrCommit() = runBlocking {
        val trace = mutableListOf<String>()
        val executor = ChapterFinalCandidateCommitStageExecutorV1(
            ChapterFinalCandidateCommitStageExecutorDependenciesV1(
                findStage = {
                    trace += "find"
                    stage(GenerationStageStatus.SUCCEEDED, leaseToken = null, heartbeatAt = null)
                },
                acquireStageLease = { _, _, _ ->
                    trace += "acquire"
                    stage(GenerationStageStatus.PREPARING)
                },
                commitFinalCandidate = { _, _, _ ->
                    trace += "commit"
                    COMMIT_RESULT
                },
            ),
        )

        val result = executor.executeBound(
            FINAL_STAGE_ID,
            GenerationLeaseToken(OWNER_ID, 40L),
            REQUESTED_AT,
        )

        assertSame(ChapterFinalCandidateCommitStageExecutionResultV1.AlreadySucceeded, result)
        assertEquals(listOf("find"), trace)
    }

    @Test
    fun succeededStageReturnsObservationWithoutLeaseOrCommit() = runBlocking {
        val trace = mutableListOf<String>()
        val executor = ChapterFinalCandidateCommitStageExecutorV1(
            ChapterFinalCandidateCommitStageExecutorDependenciesV1(
                findStage = {
                    trace += "find"
                    stage(GenerationStageStatus.SUCCEEDED, leaseToken = null, heartbeatAt = null)
                },
                acquireStageLease = { _, _, _ ->
                    trace += "acquire"
                    stage(GenerationStageStatus.PREPARING)
                },
                commitFinalCandidate = { _, _, _ ->
                    trace += "commit"
                    COMMIT_RESULT
                },
            ),
        )

        val result = executor.execute(FINAL_STAGE_ID, OWNER_ID, REQUESTED_AT)

        assertSame(ChapterFinalCandidateCommitStageExecutionResultV1.AlreadySucceeded, result)
        assertEquals(listOf("find"), trace)
    }

    @Test
    fun anotherLeaseOwnerCannotResumeOrCommit() = runBlocking {
        val trace = mutableListOf<String>()
        val executor = ChapterFinalCandidateCommitStageExecutorV1(
            ChapterFinalCandidateCommitStageExecutorDependenciesV1(
                findStage = {
                    trace += "find"
                    stage(
                        GenerationStageStatus.COMMITTING,
                        GenerationLeaseToken("worker.other", 40L),
                        heartbeatAt = 70L,
                        updatedAt = 80L,
                    )
                },
                acquireStageLease = { _, _, _ ->
                    trace += "acquire"
                    stage(GenerationStageStatus.PREPARING)
                },
                commitFinalCandidate = { _, _, _ ->
                    trace += "commit"
                    COMMIT_RESULT
                },
            ),
        )

        val failure = expectFailure { executor.execute(FINAL_STAGE_ID, OWNER_ID, REQUESTED_AT) }

        assertTrue(failure is IllegalArgumentException)
        assertEquals(listOf("find"), trace)
    }

    @Test
    fun resumeRejectsBackwardTimeBeforeCommit() = runBlocking {
        val trace = mutableListOf<String>()
        val executor = ChapterFinalCandidateCommitStageExecutorV1(
            ChapterFinalCandidateCommitStageExecutorDependenciesV1(
                findStage = {
                    trace += "find"
                    stage(
                        GenerationStageStatus.PREPARING,
                        GenerationLeaseToken(OWNER_ID, 40L),
                        heartbeatAt = 120L,
                        updatedAt = 110L,
                    )
                },
                acquireStageLease = { _, _, _ ->
                    trace += "acquire"
                    stage(GenerationStageStatus.PREPARING)
                },
                commitFinalCandidate = { _, _, _ ->
                    trace += "commit"
                    COMMIT_RESULT
                },
            ),
        )

        val failure = expectFailure { executor.execute(FINAL_STAGE_ID, OWNER_ID, REQUESTED_AT) }

        assertTrue(failure is IllegalArgumentException)
        assertEquals(listOf("find"), trace)
    }

    @Test
    fun staleReadyAcquisitionEvidenceNeverCommits() = runBlocking {
        val trace = mutableListOf<String>()
        val executor = ChapterFinalCandidateCommitStageExecutorV1(
            ChapterFinalCandidateCommitStageExecutorDependenciesV1(
                findStage = {
                    trace += "find"
                    stage(GenerationStageStatus.READY, leaseToken = null, heartbeatAt = null)
                },
                acquireStageLease = { _, _, at ->
                    trace += "acquire"
                    stage(
                        GenerationStageStatus.PREPARING,
                        GenerationLeaseToken(OWNER_ID, at),
                        heartbeatAt = at - 1L,
                        updatedAt = at,
                    )
                },
                commitFinalCandidate = { _, _, _ ->
                    trace += "commit"
                    COMMIT_RESULT
                },
            ),
        )

        val failure = expectFailure { executor.execute(FINAL_STAGE_ID, OWNER_ID, REQUESTED_AT) }

        assertTrue(failure is IllegalArgumentException)
        assertEquals(listOf("find", "acquire"), trace)
    }

    @Test
    fun allOtherStatusesFailWithoutLeaseOrCommit() = runBlocking {
        val ineligible = GenerationStageStatus.entries - setOf(
            GenerationStageStatus.READY,
            GenerationStageStatus.PREPARING,
            GenerationStageStatus.COMMITTING,
            GenerationStageStatus.SUCCEEDED,
        )
        ineligible.forEach { status ->
            val trace = mutableListOf<String>()
            val executor = ChapterFinalCandidateCommitStageExecutorV1(
                ChapterFinalCandidateCommitStageExecutorDependenciesV1(
                    findStage = {
                        trace += "find"
                        stage(status, leaseToken = null, heartbeatAt = null)
                    },
                    acquireStageLease = { _, _, _ ->
                        trace += "acquire"
                        stage(GenerationStageStatus.PREPARING)
                    },
                    commitFinalCandidate = { _, _, _ ->
                        trace += "commit"
                        COMMIT_RESULT
                    },
                ),
            )

            val failure = expectFailure { executor.execute(FINAL_STAGE_ID, OWNER_ID, REQUESTED_AT) }

            assertTrue(failure is IllegalStateException)
            assertEquals(listOf("find"), trace)
        }
    }

    @Test
    fun invalidInputsDoNotReadStageState() = runBlocking {
        val trace = mutableListOf<String>()
        val executor = ChapterFinalCandidateCommitStageExecutorV1(
            ChapterFinalCandidateCommitStageExecutorDependenciesV1(
                findStage = {
                    trace += "find"
                    stage(GenerationStageStatus.READY)
                },
                acquireStageLease = { _, _, _ ->
                    trace += "acquire"
                    stage(GenerationStageStatus.PREPARING)
                },
                commitFinalCandidate = { _, _, _ ->
                    trace += "commit"
                    COMMIT_RESULT
                },
            ),
        )

        val invalidStage = expectFailure { executor.execute("stage id with spaces", OWNER_ID, REQUESTED_AT) }
        val invalidOwner = expectFailure { executor.execute(FINAL_STAGE_ID, "owner with spaces", REQUESTED_AT) }
        val invalidTime = expectFailure { executor.execute(FINAL_STAGE_ID, OWNER_ID, -1L) }

        assertTrue(listOf(invalidStage, invalidOwner, invalidTime).all { it is IllegalArgumentException })
        assertEquals(emptyList<String>(), trace)
    }

    private fun stage(
        status: GenerationStageStatus,
        leaseToken: GenerationLeaseToken? = GenerationLeaseToken(OWNER_ID, 40L),
        heartbeatAt: Long? = 70L,
        updatedAt: Long = 80L,
        stageId: String = FINAL_STAGE_ID,
    ) = StoredGenerationStageState(
        stageId = stageId,
        jobId = "job.final.1",
        status = status,
        attemptCount = 0,
        maxAttempts = 1,
        standardErrorCode = null,
        nextRetryAt = null,
        leaseToken = leaseToken,
        leaseHeartbeatAt = heartbeatAt,
        updatedAt = updatedAt,
    )

    private suspend fun expectFailure(block: suspend () -> Unit): Throwable = try {
        block()
        throw AssertionError("Expected failure")
    } catch (error: Throwable) {
        if (error is AssertionError && error.message == "Expected failure") throw error
        error
    }

    private companion object {
        const val SENSITIVE_MARKER = "sensitive-final"
        const val FINAL_STAGE_ID = "stage.$SENSITIVE_MARKER"
        const val OWNER_ID = "worker.$SENSITIVE_MARKER"
        const val REQUESTED_AT = 100L
        val COMMIT_RESULT = ChapterFinalCandidateCommitResultV1(
            chapterVersionId = "version.$SENSITIVE_MARKER",
            chapterId = "chapter.$SENSITIVE_MARKER",
            stageId = FINAL_STAGE_ID,
            revisionIndex = 1,
            replayed = false,
            isCurrentVersion = true,
            staleCascade = null,
        )
    }
}
