package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterFinalCandidateCommitResultV1
import app.zhijuan.core.database.generation.GenerationLeasePolicy
import app.zhijuan.core.database.generation.GenerationLeaseToken
import app.zhijuan.core.database.generation.GenerationStateRepository
import app.zhijuan.core.database.generation.StaleGenerationStateException
import app.zhijuan.core.database.generation.StoredGenerationStageState
import app.zhijuan.core.model.GenerationStageStatus

private val STAGE_EXECUTOR_INPUT_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")

/**
 * In-memory dependencies of [ChapterFinalCandidateCommitStageExecutorV1], kept
 * internal so JVM tests can substitute fakes without touching Room, artifact
 * stores or persistence mappers.
 */
internal data class ChapterFinalCandidateCommitStageExecutorDependenciesV1(
    val findStage: suspend (String) -> StoredGenerationStageState?,
    val acquireStageLease: suspend (String, String, Long) -> StoredGenerationStageState,
    val commitFinalCandidate: suspend (String, GenerationLeaseToken, Long) ->
        ChapterFinalCandidateCommitResultV1,
)

/**
 * Outcome of [ChapterFinalCandidateCommitStageExecutorV1.execute].
 *
 * [Committed.toString] only summarizes non-identifying fields and never echoes
 * stage ids, owner ids, hashes, content, JSON or model snapshots.
 */
sealed interface ChapterFinalCandidateCommitStageExecutionResultV1 {
    /** The final candidate was committed, either freshly or as a deterministic resume. */
    data class Committed(
        val result: ChapterFinalCandidateCommitResultV1,
    ) : ChapterFinalCandidateCommitStageExecutionResultV1 {
        override fun toString(): String = buildString {
            append("Committed(replayed=")
            append(result.replayed)
            append(", revisionIndex=")
            append(result.revisionIndex)
            append(", isCurrentVersion=")
            append(result.isCurrentVersion)
            append(", hasStaleCascade=")
            append(result.staleCascade != null)
            append(')')
        }
    }

    /** The final stage was already committed; no work was performed. */
    data object AlreadySucceeded : ChapterFinalCandidateCommitStageExecutionResultV1
}

/**
 * The COMMIT_CHAPTER stage executor entry point for a future total runner.
 *
 * It observes the persisted stage state, safely acquires the stage lease under
 * the caller-supplied owner when READY, and when PREPARING/COMMITTING resumes
 * only the same persisted lease owner. Every resulting token is handed to the
 * sole [ChapterFinalCandidateCommitCoordinatorV1]; no recovery, mapping,
 * strategy or transaction logic is duplicated here.
 *
 * This executor never reclaims, heartbeats, replaces or steals an existing
 * lease, never guesses progress from other statuses, never checks the
 * generation phase (the final coordinator verifies COMMIT_CHAPTER from
 * database evidence), and never echoes identifiers in failures.
 */
class ChapterFinalCandidateCommitStageExecutorV1 internal constructor(
    private val dependencies: ChapterFinalCandidateCommitStageExecutorDependenciesV1,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    constructor(
        generationStateRepository: GenerationStateRepository,
        finalCommitCoordinator: ChapterFinalCandidateCommitCoordinatorV1,
        leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
    ) : this(
        ChapterFinalCandidateCommitStageExecutorDependenciesV1(
            findStage = generationStateRepository::findStage,
            acquireStageLease = generationStateRepository::acquireStageLease,
            commitFinalCandidate = finalCommitCoordinator::commit,
        ),
        leasePolicy,
    )

    /**
     * Executes the final COMMIT_CHAPTER stage for [finalStageId] on behalf of
     * [leaseOwnerId] at [requestedAt].
     *
     * READY acquires the stage lease exactly once; PREPARING/COMMITTING resumes
     * the persisted lease of the same owner without acquiring; SUCCEEDED
     * immediately reports the already-committed observation without invoking
     * the coordinator or reading artifacts; every other status fails closed.
     */
    suspend fun execute(
        finalStageId: String,
        leaseOwnerId: String,
        requestedAt: Long,
    ): ChapterFinalCandidateCommitStageExecutionResultV1 {
        require(STAGE_EXECUTOR_INPUT_PATTERN.matches(finalStageId)) {
            "Final stage id is not a valid identifier."
        }
        require(STAGE_EXECUTOR_INPUT_PATTERN.matches(leaseOwnerId)) {
            "Lease owner id is not a valid identifier."
        }
        require(requestedAt >= 0L) { "Requested commit time must not be negative." }

        val stage = dependencies.findStage(finalStageId)
            ?: throw IllegalStateException("Final commit stage does not exist.")
        require(stage.stageId == finalStageId) {
            "Final commit stage observation is stale."
        }

        return when (stage.status) {
            GenerationStageStatus.READY ->
                executeFromReady(finalStageId, leaseOwnerId, requestedAt)
            GenerationStageStatus.PREPARING,
            GenerationStageStatus.COMMITTING,
            -> executeFromResume(stage, finalStageId, leaseOwnerId, requestedAt)
            GenerationStageStatus.SUCCEEDED ->
                ChapterFinalCandidateCommitStageExecutionResultV1.AlreadySucceeded
            else -> throw IllegalStateException("Final commit stage is not executable.")
        }
    }

    /**
     * Executes a final Stage that the total runner has already bound to one exact persisted lease.
     *
     * Unlike [execute], this entry never acquires a READY Stage and never replaces the caller's
     * token with a newly observed token that merely has the same owner. PREPARING/COMMITTING must
     * still carry [stageLeaseToken] byte-for-byte, and the lease must remain unexpired at
     * [requestedAt]. SUCCEEDED is a safe read-only replay observation.
     */
    suspend fun executeBound(
        finalStageId: String,
        stageLeaseToken: GenerationLeaseToken,
        requestedAt: Long,
    ): ChapterFinalCandidateCommitStageExecutionResultV1 {
        require(STAGE_EXECUTOR_INPUT_PATTERN.matches(finalStageId)) {
            "Final stage id is not a valid identifier."
        }
        require(STAGE_EXECUTOR_INPUT_PATTERN.matches(stageLeaseToken.ownerId)) {
            "Lease owner id is not a valid identifier."
        }
        require(requestedAt >= 0L) { "Requested commit time must not be negative." }

        val stage = dependencies.findStage(finalStageId)
            ?: throw IllegalStateException("Final commit stage does not exist.")
        require(stage.stageId == finalStageId) {
            "Final commit stage observation is stale."
        }
        if (stage.status == GenerationStageStatus.SUCCEEDED) {
            return ChapterFinalCandidateCommitStageExecutionResultV1.AlreadySucceeded
        }
        if (
            stage.status !in setOf(
                GenerationStageStatus.PREPARING,
                GenerationStageStatus.COMMITTING,
            )
        ) {
            throw StaleGenerationStateException("Bound final commit Stage is no longer executable.")
        }
        val persistedToken = requireNotNull(stage.leaseToken) {
            "Bound final commit Stage is missing its persisted lease token."
        }
        if (persistedToken != stageLeaseToken) {
            throw StaleGenerationStateException("Bound final commit Stage lease identity changed.")
        }
        val heartbeatAt = requireNotNull(stage.leaseHeartbeatAt) {
            "Bound final commit Stage is missing its persisted lease heartbeat."
        }
        require(requestedAt >= stage.updatedAt && requestedAt >= heartbeatAt) {
            "Final commit time cannot move backwards."
        }
        if (leasePolicy.isExpired(heartbeatAt, requestedAt)) {
            throw StaleGenerationStateException("Bound final commit Stage lease expired.")
        }
        return ChapterFinalCandidateCommitStageExecutionResultV1.Committed(
            dependencies.commitFinalCandidate(finalStageId, stageLeaseToken, requestedAt),
        )
    }

    private suspend fun executeFromReady(
        finalStageId: String,
        leaseOwnerId: String,
        requestedAt: Long,
    ): ChapterFinalCandidateCommitStageExecutionResultV1 {
        val acquired = dependencies.acquireStageLease(finalStageId, leaseOwnerId, requestedAt)
        val acquiredToken = requireNotNull(acquired.leaseToken) {
            "Final stage lease acquisition returned no lease token."
        }
        require(
            acquired.stageId == finalStageId &&
                acquired.status == GenerationStageStatus.PREPARING &&
                acquiredToken.ownerId == leaseOwnerId &&
                acquiredToken.acquiredAt == requestedAt &&
                acquired.leaseHeartbeatAt == requestedAt &&
                acquired.updatedAt == requestedAt,
        ) {
            "Final stage lease acquisition evidence is stale."
        }
        val result = dependencies.commitFinalCandidate(finalStageId, acquiredToken, requestedAt)
        return ChapterFinalCandidateCommitStageExecutionResultV1.Committed(result)
    }

    private suspend fun executeFromResume(
        stage: StoredGenerationStageState,
        finalStageId: String,
        leaseOwnerId: String,
        requestedAt: Long,
    ): ChapterFinalCandidateCommitStageExecutionResultV1 {
        val persistedToken = requireNotNull(stage.leaseToken) {
            "Final stage is missing its persisted lease token."
        }
        require(persistedToken.ownerId == leaseOwnerId) {
            "Final stage lease is not owned by the caller."
        }
        val persistedHeartbeatAt = requireNotNull(stage.leaseHeartbeatAt) {
            "Final stage is missing its persisted lease heartbeat."
        }
        require(requestedAt >= stage.updatedAt && requestedAt >= persistedHeartbeatAt) {
            "Final commit time cannot move backwards."
        }
        val result = dependencies.commitFinalCandidate(finalStageId, persistedToken, requestedAt)
        return ChapterFinalCandidateCommitStageExecutionResultV1.Committed(result)
    }
}
