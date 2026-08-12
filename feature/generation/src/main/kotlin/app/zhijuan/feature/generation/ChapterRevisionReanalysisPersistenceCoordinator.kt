package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterCandidateArtifactRoleV1
import app.zhijuan.core.database.generation.ChapterCandidateArtifactSealDraftV1
import app.zhijuan.core.database.generation.ChapterCandidateArtifactSealRepositoryV1
import app.zhijuan.core.database.generation.ChapterCandidateArtifactSealResultV1
import app.zhijuan.core.database.generation.ChapterCandidateStageBindingV1
import app.zhijuan.core.database.generation.ChapterCandidateStageSourceV1
import app.zhijuan.core.model.GenerationPhase

data class ChapterRevisionReanalysisAdvanceSpecV1(
    val jobId: String,
    val revisedCandidateChapterVersionId: String,
    val chapterId: String,
    val chapterIndex: Int,
    val routeBindingHash: String,
    val postAnalysisStageId: String,
    val postAnalysisMaximumAttempts: Int,
    val sealedAt: Long,
)

/** Seals a revised BODY only toward a fresh POST_ANALYSIS stage. */
class ChapterRevisionReanalysisPersistenceCoordinatorV1(
    private val artifacts: ChapterCandidateArtifactSealRepositoryV1,
) {
    suspend fun advance(
        result: ChapterRevisionStreamingResultV1.ReadyForReExtraction,
        boundRequest: BoundChapterRevisionRequestV1,
        spec: ChapterRevisionReanalysisAdvanceSpecV1,
    ): ChapterCandidateArtifactSealResultV1 {
        require(listOf(
            spec.jobId, spec.revisedCandidateChapterVersionId, spec.chapterId, spec.postAnalysisStageId,
        ).all(IDENTIFIER::matches))
        require(HASH.matches(spec.routeBindingHash))
        require(spec.chapterIndex in 1..10_000 && spec.postAnalysisMaximumAttempts in 1..16)
        require(spec.sealedAt >= 0L)
        require(result.completedAutomaticRevisions == boundRequest.plan.revisionIndex)
        require(result.candidateContentHashHistory ==
            boundRequest.plan.priorCandidateContentHashes + result.revisedCandidateContentHash)
        require(boundRequest.request.stageId != spec.postAnalysisStageId)
        val next = ChapterCandidateStageBindingV1.stageSetup(
            jobId = spec.jobId,
            stageId = spec.postAnalysisStageId,
            phase = GenerationPhase.EXTRACT_MEMORY,
            source = ChapterCandidateStageSourceV1(
                role = ChapterCandidateArtifactRoleV1.POST_ANALYSIS,
                candidateChapterVersionId = spec.revisedCandidateChapterVersionId,
                candidateContentHash = result.revisedCandidateContentHash,
                chapterId = spec.chapterId,
                chapterIndex = spec.chapterIndex,
                revisionIndex = result.completedAutomaticRevisions,
                predecessorStageId = boundRequest.request.stageId,
                routeBindingHash = spec.routeBindingHash,
            ),
            maxAttempts = spec.postAnalysisMaximumAttempts,
        )
        return artifacts.seal(
            result.commitPermit,
            ChapterCandidateArtifactSealDraftV1(
                role = ChapterCandidateArtifactRoleV1.BODY,
                candidateChapterVersionId = spec.revisedCandidateChapterVersionId,
                chapterId = spec.chapterId,
                chapterIndex = spec.chapterIndex,
                candidateContentHash = result.revisedCandidateContentHash,
                canonicalOutputHash = result.revisedCandidateContentHash,
                sourceBindingHash = boundRequest.sourceBindingHash,
                revisionIndex = result.completedAutomaticRevisions,
                usage = result.execution.latestUsage.toFinalUsageCommit(),
                nextStage = next,
                sealedAt = spec.sealedAt,
                routeBindingHash = spec.routeBindingHash,
            ),
        )
    }

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        val HASH = Regex("[0-9a-f]{64}")
    }
}
