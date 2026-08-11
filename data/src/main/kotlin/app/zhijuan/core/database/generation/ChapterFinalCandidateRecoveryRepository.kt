package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.UsageLedgerStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Read-only, redacted snapshot of a recoverable final candidate chain. */
data class ChapterFinalCandidateRecoveryV1(
    val finalStageId: String,
    val jobId: String,
    val bookId: String,
    val finalStageStatus: GenerationStageStatus,
    val finalStageUpdatedAt: Long,
    val source: ChapterFinalCommitStageSourceV1,
    val candidateRouteBindingHash: String?,
    val artifacts: List<ChapterFinalCandidateArtifactEvidenceV1>,
    val memoryModelSnapshotJson: String,
    val trackingModelSnapshotJson: String,
    val consistencyModelSnapshotJson: String,
) {
    override fun toString(): String =
        "ChapterFinalCandidateRecoveryV1(finalStageStatus=$finalStageStatus, " +
            "chapterIndex=${source.chapterIndex}, revisionIndex=${source.revisionIndex}, " +
            "artifactCount=${artifacts.size}, evidence=redacted)"
}

/**
 * Recovers the current BODY -> MEMORY -> TRACKING -> CONSISTENCY chain in one
 * read transaction. This snapshot is not a publication permit; the final
 * commit repository independently revalidates the same durable evidence.
 */
class ChapterFinalCandidateRecoveryRepository(
    private val database: ZhijuanDatabase,
) {
    suspend fun load(finalStageId: String): ChapterFinalCandidateRecoveryV1 = database.withTransaction {
        require(IDENTIFIER.matches(finalStageId)) {
            "Final candidate recovery failed: final stage identifier is invalid."
        }
        val dao = database.generationDao()
        val finalStage = dao.findStage(finalStageId)
            ?: stale("Final candidate recovery failed: final stage does not exist.")
        require(
            finalStage.phase == GenerationPhase.COMMIT_CHAPTER &&
                finalStage.targetType == GenerationTargetType.CHAPTER &&
                finalStage.maxAttempts == 1,
        ) { "Final candidate recovery failed: final stage contract is invalid." }
        val source = ChapterFinalCommitStageBindingV1.parseAndVerify(finalStage)
        val job = dao.findJob(finalStage.jobId)
            ?: stale("Final candidate recovery failed: job does not exist.")
        require(job.currentStageId == finalStage.stageId) {
            "Final candidate recovery failed: job does not point to the final stage."
        }
        requireFinalState(finalStage.status, job.status)
        val chapter = database.libraryDao().findChapter(source.chapterId)
            ?: stale("Final candidate recovery failed: chapter does not exist.")
        require(chapter.bookId == job.bookId && chapter.chapterIndex == source.chapterIndex) {
            "Final candidate recovery failed: chapter does not belong to the frozen job source."
        }

        val consistency = loadSealedStage(
            dao = dao,
            stageId = source.predecessorStageId,
            finalStage = finalStage,
            source = source,
            expectedRole = ChapterCandidateArtifactRoleV1.CONSISTENCY,
        )
        val consistencyInput = requireNotNull(consistency.inputSource) {
            "Final candidate recovery failed: consistency input source is missing."
        }
        val tracking = loadSealedStage(
            dao = dao,
            stageId = consistencyInput.predecessorStageId,
            finalStage = finalStage,
            source = source,
            expectedRole = ChapterCandidateArtifactRoleV1.TRACKING,
        )
        val trackingInput = requireNotNull(tracking.inputSource) {
            "Final candidate recovery failed: tracking input source is missing."
        }
        val memory = loadSealedStage(
            dao = dao,
            stageId = trackingInput.predecessorStageId,
            finalStage = finalStage,
            source = source,
            expectedRole = ChapterCandidateArtifactRoleV1.MEMORY,
        )
        val memoryInput = requireNotNull(memory.inputSource) {
            "Final candidate recovery failed: memory input source is missing."
        }
        val body = loadSealedStage(
            dao = dao,
            stageId = memoryInput.predecessorStageId,
            finalStage = finalStage,
            source = source,
            expectedRole = ChapterCandidateArtifactRoleV1.BODY,
        )

        requireDerivedSource(memory, ChapterCandidateArtifactRoleV1.MEMORY, body, source)
        requireDerivedSource(tracking, ChapterCandidateArtifactRoleV1.TRACKING, memory, source)
        requireDerivedSource(consistency, ChapterCandidateArtifactRoleV1.CONSISTENCY, tracking, source)
        requireContinuousChain(body, memory, tracking, consistency, finalStage.stageId)
        require(consistency.evidence.routeBindingHash == source.routeBindingHash) {
            "Final candidate recovery failed: final route binding is stale."
        }
        require(consistency.evidence.sourceBindingHash == source.consistencyRequestSourceBindingHash) {
            "Final candidate recovery failed: consistency request binding is stale."
        }
        requireBodySource(body, source)

        val bodyAttempt = verifyAttempt(dao, body, finalStage.jobId)
        val memoryAttempt = verifyAttempt(dao, memory, finalStage.jobId)
        val trackingAttempt = verifyAttempt(dao, tracking, finalStage.jobId)
        val consistencyAttempt = verifyAttempt(dao, consistency, finalStage.jobId)
        verifyUsage(dao, bodyAttempt, job.bookId)
        verifyUsage(dao, memoryAttempt, job.bookId)
        verifyUsage(dao, trackingAttempt, job.bookId)
        verifyUsage(dao, consistencyAttempt, job.bookId)

        ChapterFinalCandidateRecoveryV1(
            finalStageId = finalStage.stageId,
            jobId = job.jobId,
            bookId = job.bookId,
            finalStageStatus = finalStage.status,
            finalStageUpdatedAt = finalStage.updatedAt,
            source = source,
            candidateRouteBindingHash = consistencyInput.routeBindingHash,
            artifacts = listOf(body, memory, tracking, consistency)
                .map { it.evidence.toArtifactEvidence() },
            memoryModelSnapshotJson = requireModelSnapshot(memoryAttempt.modelSnapshotJson),
            trackingModelSnapshotJson = requireModelSnapshot(trackingAttempt.modelSnapshotJson),
            consistencyModelSnapshotJson = requireModelSnapshot(consistencyAttempt.modelSnapshotJson),
        )
    }

    private data class RecoveredSealedStage(
        val stage: GenerationStageEntity,
        val evidence: ChapterCandidateSealedStageEvidenceV1,
        val inputSource: ChapterCandidateStageSourceV1?,
    )

    private fun requireFinalState(
        stageStatus: GenerationStageStatus,
        jobStatus: GenerationJobStatus,
    ) {
        when (stageStatus) {
            GenerationStageStatus.READY,
            GenerationStageStatus.PREPARING,
            GenerationStageStatus.COMMITTING,
            -> require(jobStatus in setOf(GenerationJobStatus.RUNNING, GenerationJobStatus.PAUSING)) {
                "Final candidate recovery failed: unfinished final stage and job states disagree."
            }

            GenerationStageStatus.SUCCEEDED -> require(jobStatus == GenerationJobStatus.COMPLETED) {
                "Final candidate recovery failed: completed final stage and job states disagree."
            }

            else -> stale("Final candidate recovery failed: final stage is not recoverable.")
        }
    }

    private suspend fun loadSealedStage(
        dao: GenerationDao,
        stageId: String,
        finalStage: GenerationStageEntity,
        source: ChapterFinalCommitStageSourceV1,
        expectedRole: ChapterCandidateArtifactRoleV1,
    ): RecoveredSealedStage {
        val stage = dao.findStage(stageId)
            ?: stale("Final candidate recovery failed: predecessor stage does not exist.")
        require(
            stage.jobId == finalStage.jobId && stage.targetId == source.chapterId &&
                stage.status == GenerationStageStatus.SUCCEEDED,
        ) { "Final candidate recovery failed: predecessor stage ownership or status is stale." }
        val evidence = ChapterCandidateSealedStageEvidenceParserV1.parseAndVerify(stage)
        require(
            evidence.role == expectedRole &&
                evidence.candidateChapterVersionId == source.candidateChapterVersionId &&
                evidence.candidateContentHash == source.candidateContentHash &&
                evidence.chapterId == source.chapterId && evidence.chapterIndex == source.chapterIndex &&
                evidence.revisionIndex == source.revisionIndex,
        ) { "Final candidate recovery failed: sealed candidate evidence is stale." }
        val inputSource = if (
            expectedRole == ChapterCandidateArtifactRoleV1.BODY &&
            stage.phase == GenerationPhase.DRAFT_CHAPTER
        ) {
            null
        } else {
            ChapterCandidateStageBindingV1.parseAndVerify(stage)
        }
        return RecoveredSealedStage(stage, evidence, inputSource)
    }

    private fun requireDerivedSource(
        loaded: RecoveredSealedStage,
        expectedRole: ChapterCandidateArtifactRoleV1,
        directPredecessor: RecoveredSealedStage,
        source: ChapterFinalCommitStageSourceV1,
    ) {
        val input = requireNotNull(loaded.inputSource) {
            "Final candidate recovery failed: derived input source is missing."
        }
        require(
            input.role == expectedRole &&
                input.predecessorStageId == directPredecessor.stage.stageId &&
                input.candidateChapterVersionId == source.candidateChapterVersionId &&
                input.candidateContentHash == source.candidateContentHash &&
                input.chapterId == source.chapterId && input.chapterIndex == source.chapterIndex &&
                input.revisionIndex == source.revisionIndex &&
                input.routeBindingHash == directPredecessor.evidence.routeBindingHash,
        ) { "Final candidate recovery failed: derived input source is stale." }
    }

    private fun requireContinuousChain(
        body: RecoveredSealedStage,
        memory: RecoveredSealedStage,
        tracking: RecoveredSealedStage,
        consistency: RecoveredSealedStage,
        finalStageId: String,
    ) {
        require(
            body.evidence.nextStageId == memory.stage.stageId &&
                memory.evidence.nextStageId == tracking.stage.stageId &&
                tracking.evidence.nextStageId == consistency.stage.stageId &&
                consistency.evidence.nextStageId == finalStageId,
        ) { "Final candidate recovery failed: sealed candidate chain is not contiguous." }
        require(
            setOf(
                body.stage.stageId,
                memory.stage.stageId,
                tracking.stage.stageId,
                consistency.stage.stageId,
            ).size == ChapterCandidateArtifactRoleV1.entries.size,
        ) { "Final candidate recovery failed: sealed candidate chain contains a cycle." }
    }

    private fun requireBodySource(
        body: RecoveredSealedStage,
        source: ChapterFinalCommitStageSourceV1,
    ) {
        if (source.revisionIndex == 0) {
            require(body.stage.phase == GenerationPhase.DRAFT_CHAPTER && body.inputSource == null) {
                "Final candidate recovery failed: initial body source is invalid."
            }
            return
        }
        val input = requireNotNull(body.inputSource) {
            "Final candidate recovery failed: revised body source is missing."
        }
        require(
            body.stage.phase == GenerationPhase.REVISE_CHAPTER &&
                input.role == ChapterCandidateArtifactRoleV1.BODY &&
                input.revisionIndex == source.revisionIndex - 1 &&
                input.chapterId == source.chapterId && input.chapterIndex == source.chapterIndex &&
                input.candidateContentHash == source.candidateContentHashHistory[source.revisionIndex - 1] &&
                input.candidateChapterVersionId != source.candidateChapterVersionId &&
                input.candidateContentHash != source.candidateContentHash &&
                input.routeBindingHash != null && input.requestSourceBindingHash != null,
        ) { "Final candidate recovery failed: revised body source is stale." }
    }

    private suspend fun verifyAttempt(
        dao: GenerationDao,
        loaded: RecoveredSealedStage,
        jobId: String,
    ): RequestAttemptEntity {
        val attempts = dao.attemptsForStage(loaded.stage.stageId)
        val attempt = attempts.lastOrNull()
            ?: stale("Final candidate recovery failed: candidate attempt is missing.")
        require(
            attempt.jobId == jobId && attempt.stageId == loaded.stage.stageId &&
                attempt.status == RequestAttemptStatus.SUCCEEDED && attempt.standardErrorCode == null &&
                attempt.attemptId == loaded.evidence.attemptId &&
                attempt.inputHash == loaded.evidence.sourceBindingHash &&
                attempt.outputHash == loaded.evidence.rawOutputHash &&
                attempt.streamDraftRef == loaded.evidence.artifactRefId,
        ) { "Final candidate recovery failed: candidate attempt evidence is stale." }
        return attempt
    }

    private suspend fun verifyUsage(
        dao: GenerationDao,
        attempt: RequestAttemptEntity,
        bookId: String,
    ) {
        val usage = dao.findUsageForAttempt(attempt.attemptId)
            ?: stale("Final candidate recovery failed: candidate usage is missing.")
        require(usage.bookId == bookId && usage.status == UsageLedgerStatus.FINAL) {
            "Final candidate recovery failed: candidate usage is not final or belongs to another book."
        }
    }

    private fun requireModelSnapshot(value: String): String {
        require(value.isNotEmpty() && value.length <= MODEL_SNAPSHOT_MAX_LENGTH) {
            "Final candidate recovery failed: model snapshot size is invalid."
        }
        val parsed = runCatching { STRICT_JSON.parseToJsonElement(value) }
            .getOrElse {
                throw IllegalArgumentException("Final candidate recovery failed: model snapshot is invalid JSON.")
            }
        require(parsed is JsonObject) {
            "Final candidate recovery failed: model snapshot is not an object."
        }
        return value
    }

    private fun stale(message: String): Nothing = throw StaleGenerationStateException(message)

    private companion object {
        const val MODEL_SNAPSHOT_MAX_LENGTH = 65_536
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        val STRICT_JSON = Json { isLenient = false; ignoreUnknownKeys = false }
    }
}
