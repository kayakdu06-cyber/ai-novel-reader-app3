package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterCandidateArtifactRoleV1
import app.zhijuan.core.database.generation.ChapterCandidateArtifactSealDraftV1
import app.zhijuan.core.database.generation.ChapterCandidateArtifactSealRepositoryV1
import app.zhijuan.core.database.generation.ChapterCandidateArtifactSealResultV1
import app.zhijuan.core.database.generation.ChapterCandidateStageBindingV1
import app.zhijuan.core.database.generation.ChapterCandidateStageSourceV1
import app.zhijuan.core.model.GenerationPhase

data class ChapterCandidatePipelineIdentityV1(
    val chapterVersionId: String,
    val chapterId: String,
    val chapterIndex: Int,
    val contentHash: String,
    val revisionIndex: Int,
    val routeBindingHash: String?,
) {
    init {
        require(listOf(chapterVersionId, chapterId).all(IDENTIFIER::matches))
        require(HASH.matches(contentHash))
        require(chapterIndex in 1..10_000 && revisionIndex in 0..2)
        require(routeBindingHash == null || HASH.matches(routeBindingHash))
        require(revisionIndex == 0 || routeBindingHash != null) {
            "A revised candidate must retain its revision-result route binding."
        }
    }
}

data class ChapterCandidateDerivedStageAdvanceSpecV1(
    val jobId: String,
    val candidate: ChapterCandidatePipelineIdentityV1,
    val nextStageId: String,
    val nextStageMaximumAttempts: Int,
    val sealedAt: Long,
) {
    init {
        require(listOf(jobId, nextStageId).all(IDENTIFIER::matches))
        require(nextStageMaximumAttempts in 1..16)
        require(sealedAt >= 0L)
    }
}

data class ChapterCandidateMemoryAdvanceResultV1(
    val memory: ChapterMemoryV1,
    val seal: ChapterCandidateArtifactSealResultV1,
)

data class ChapterCandidateTrackingAdvanceResultV1(
    val tracking: ChapterStoryTrackingV1,
    val seal: ChapterCandidateArtifactSealResultV1,
)

/**
 * Production bridge from validated MEMORY/TRACKING coordinator results to the
 * durable candidate Stage chain. It preserves one route binding across every
 * re-extraction step and derives Usage from the audited execution instead of
 * accepting a caller-supplied settlement.
 */
class ChapterCandidateDerivedStagePersistenceCoordinatorV1(
    private val artifacts: ChapterCandidateArtifactSealRepositoryV1,
) {
    suspend fun sealMemory(
        accepted: ChapterMemoryExtractionResult.Accepted,
        boundRequest: BoundChapterMemoryExtractionRequest,
        spec: ChapterCandidateDerivedStageAdvanceSpecV1,
    ): ChapterCandidateMemoryAdvanceResultV1 {
        requireMemoryMatches(accepted.memory, boundRequest, spec.candidate)
        val draft = ChapterCandidateDerivedStagePlannerV1.memory(
            memory = accepted.memory,
            currentStageId = boundRequest.request.stageId,
            sourceBindingHash = boundRequest.sourceBindingHash,
            usage = accepted.execution.latestUsage.toFinalUsageCommit(),
            spec = spec,
        )
        return ChapterCandidateMemoryAdvanceResultV1(
            memory = accepted.memory,
            seal = artifacts.seal(accepted.commitPermit, draft),
        )
    }

    suspend fun sealTracking(
        accepted: ChapterTrackingProjectionResult.Accepted,
        boundRequest: BoundChapterTrackingProjectionRequest,
        spec: ChapterCandidateDerivedStageAdvanceSpecV1,
    ): ChapterCandidateTrackingAdvanceResultV1 {
        requireTrackingMatches(accepted.tracking, boundRequest, spec.candidate)
        val draft = ChapterCandidateDerivedStagePlannerV1.tracking(
            tracking = accepted.tracking,
            currentStageId = boundRequest.request.stageId,
            sourceBindingHash = boundRequest.sourceBindingHash,
            usage = accepted.execution.latestUsage.toFinalUsageCommit(),
            spec = spec,
        )
        return ChapterCandidateTrackingAdvanceResultV1(
            tracking = accepted.tracking,
            seal = artifacts.seal(accepted.commitPermit, draft),
        )
    }

    private fun requireMemoryMatches(
        memory: ChapterMemoryV1,
        bound: BoundChapterMemoryExtractionRequest,
        candidate: ChapterCandidatePipelineIdentityV1,
    ) {
        val expectation = bound.expectation
        require(
            memory.sourceChapterVersionId == expectation.sourceChapterVersionId &&
                memory.sourceChapterContentHash == expectation.sourceChapterContentHash &&
                memory.chapterId == expectation.chapterId && memory.chapterIndex == expectation.chapterIndex,
        ) { "Validated memory no longer matches its frozen request expectation." }
        requireCandidateMatches(
            candidate,
            memory.sourceChapterVersionId,
            memory.sourceChapterContentHash,
            memory.chapterId,
            memory.chapterIndex,
        )
    }

    private fun requireTrackingMatches(
        tracking: ChapterStoryTrackingV1,
        bound: BoundChapterTrackingProjectionRequest,
        candidate: ChapterCandidatePipelineIdentityV1,
    ) {
        val expectation = bound.expectation
        require(
            tracking.sourceChapterVersionId == expectation.sourceChapterVersionId &&
                tracking.sourceChapterContentHash == expectation.sourceChapterContentHash &&
                tracking.chapterId == expectation.chapterId && tracking.chapterIndex == expectation.chapterIndex,
        ) { "Validated tracking no longer matches its frozen request expectation." }
        requireCandidateMatches(
            candidate,
            tracking.sourceChapterVersionId,
            tracking.sourceChapterContentHash,
            tracking.chapterId,
            tracking.chapterIndex,
        )
    }
}

internal object ChapterCandidateDerivedStagePlannerV1 {
    fun memory(
        memory: ChapterMemoryV1,
        currentStageId: String,
        sourceBindingHash: String,
        usage: app.zhijuan.core.database.generation.FinalUsageCommit,
        spec: ChapterCandidateDerivedStageAdvanceSpecV1,
    ): ChapterCandidateArtifactSealDraftV1 {
        requireCandidateMatches(
            spec.candidate,
            memory.sourceChapterVersionId,
            memory.sourceChapterContentHash,
            memory.chapterId,
            memory.chapterIndex,
        )
        return draft(
            role = ChapterCandidateArtifactRoleV1.MEMORY,
            nextRole = ChapterCandidateArtifactRoleV1.TRACKING,
            nextPhase = GenerationPhase.EXTRACT_MEMORY,
            canonicalOutputHash = memory.contentHash,
            currentStageId = currentStageId,
            sourceBindingHash = sourceBindingHash,
            usage = usage,
            spec = spec,
        )
    }

    fun tracking(
        tracking: ChapterStoryTrackingV1,
        currentStageId: String,
        sourceBindingHash: String,
        usage: app.zhijuan.core.database.generation.FinalUsageCommit,
        spec: ChapterCandidateDerivedStageAdvanceSpecV1,
    ): ChapterCandidateArtifactSealDraftV1 {
        requireCandidateMatches(
            spec.candidate,
            tracking.sourceChapterVersionId,
            tracking.sourceChapterContentHash,
            tracking.chapterId,
            tracking.chapterIndex,
        )
        return draft(
            role = ChapterCandidateArtifactRoleV1.TRACKING,
            nextRole = ChapterCandidateArtifactRoleV1.CONSISTENCY,
            nextPhase = GenerationPhase.CHECK_CONSISTENCY,
            canonicalOutputHash = tracking.contentHash,
            currentStageId = currentStageId,
            sourceBindingHash = sourceBindingHash,
            usage = usage,
            spec = spec,
        )
    }

    private fun draft(
        role: ChapterCandidateArtifactRoleV1,
        nextRole: ChapterCandidateArtifactRoleV1,
        nextPhase: GenerationPhase,
        canonicalOutputHash: String,
        currentStageId: String,
        sourceBindingHash: String,
        usage: app.zhijuan.core.database.generation.FinalUsageCommit,
        spec: ChapterCandidateDerivedStageAdvanceSpecV1,
    ): ChapterCandidateArtifactSealDraftV1 {
        require(IDENTIFIER.matches(currentStageId))
        require(HASH.matches(sourceBindingHash) && HASH.matches(canonicalOutputHash))
        val candidate = spec.candidate
        val next = ChapterCandidateStageBindingV1.stageSetup(
            jobId = spec.jobId,
            stageId = spec.nextStageId,
            phase = nextPhase,
            source = ChapterCandidateStageSourceV1(
                role = nextRole,
                candidateChapterVersionId = candidate.chapterVersionId,
                candidateContentHash = candidate.contentHash,
                chapterId = candidate.chapterId,
                chapterIndex = candidate.chapterIndex,
                revisionIndex = candidate.revisionIndex,
                predecessorStageId = currentStageId,
                routeBindingHash = candidate.routeBindingHash,
            ),
            maxAttempts = spec.nextStageMaximumAttempts,
        )
        return ChapterCandidateArtifactSealDraftV1(
            role = role,
            candidateChapterVersionId = candidate.chapterVersionId,
            chapterId = candidate.chapterId,
            chapterIndex = candidate.chapterIndex,
            candidateContentHash = candidate.contentHash,
            canonicalOutputHash = canonicalOutputHash,
            sourceBindingHash = sourceBindingHash,
            revisionIndex = candidate.revisionIndex,
            usage = usage,
            nextStage = next,
            sealedAt = spec.sealedAt,
            routeBindingHash = candidate.routeBindingHash,
        )
    }
}

private fun requireCandidateMatches(
    candidate: ChapterCandidatePipelineIdentityV1,
    chapterVersionId: String,
    contentHash: String,
    chapterId: String,
    chapterIndex: Int,
) {
    require(
        candidate.chapterVersionId == chapterVersionId && candidate.contentHash == contentHash &&
            candidate.chapterId == chapterId && candidate.chapterIndex == chapterIndex,
    ) { "Derived output does not belong to the current frozen candidate." }
}

private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
private val HASH = Regex("[0-9a-f]{64}")
