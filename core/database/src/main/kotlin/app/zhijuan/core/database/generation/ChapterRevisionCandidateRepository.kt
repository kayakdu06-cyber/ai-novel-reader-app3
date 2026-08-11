package app.zhijuan.core.database.generation

import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.security.ProtectedArtifactType
import app.zhijuan.core.task.ChapterRevisionPolicyDecisionV1
import app.zhijuan.core.task.ChapterRevisionPolicyInputV1
import app.zhijuan.core.task.ChapterRevisionPolicyV1
import app.zhijuan.core.task.ChapterRevisionResultDecisionV1
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class ChapterRevisionCandidateDraftV1(
    val revisedCandidateChapterVersionId: String,
    val chapterId: String,
    val chapterIndex: Int,
    val revisedCandidateContentHash: String,
    val revisedBodyCodePointCount: Int,
    val candidateContentHashHistory: List<String>,
    val sourceBindingHash: String,
    val nextMemoryStageId: String,
    val nextMemoryMaximumAttempts: Int,
    val usage: FinalUsageCommit,
    val sealedAt: Long,
) {
    override fun toString(): String =
        "ChapterRevisionCandidateDraftV1(chapterIndex=$chapterIndex, revisionIndex=" +
            "${candidateContentHashHistory.size - 1}, content=redacted)"
}

data class ChapterRevisionCandidateResultV1(
    val seal: ChapterCandidateArtifactSealResultV1,
    val revisedCandidateChapterVersionId: String,
    val revisedCandidateContentHash: String,
    val completedAutomaticRevisions: Int,
    val candidateContentHashHistory: List<String>,
    val routeBindingHash: String,
)

/**
 * Converts one validated revision response into a new frozen candidate and the
 * first re-extraction Stage. The persisted revision route, request input, policy,
 * source candidate, result hash, and new candidate lineage must all agree.
 */
class ChapterRevisionCandidateRepositoryV1(
    private val database: ZhijuanDatabase,
    private val artifactStore: AndroidProtectedArtifactStore,
) {
    private val artifacts = ChapterCandidateArtifactSealRepositoryV1(database, artifactStore)

    suspend fun seal(
        permit: ValidatedOutputCommitPermit,
        draft: ChapterRevisionCandidateDraftV1,
        policyInput: ChapterRevisionPolicyInputV1,
    ): ChapterRevisionCandidateResultV1 {
        validateDraft(draft)
        val stage = requireNotNull(database.generationDao().findStage(permit.stageId)) {
            "Revision Stage no longer exists."
        }
        val job = requireNotNull(database.generationDao().findJob(stage.jobId)) {
            "Revision Job no longer exists."
        }
        if (stage.status != GenerationStageStatus.SUCCEEDED) {
            require(verifiedBodyCodePointCount(permit) == draft.revisedBodyCodePointCount) {
                "The revised body length no longer matches the validated encrypted artifact."
            }
        }
        val plan = ChapterRevisionPolicyV1.evaluate(policyInput) as? ChapterRevisionPolicyDecisionV1.ReviseAutomatically
            ?: throw IllegalArgumentException("The frozen consistency outcome does not authorize a revision.")
        val result = ChapterRevisionPolicyV1.evaluateRevisedCandidate(
            plan = plan,
            revisedCandidateContentHash = draft.revisedCandidateContentHash,
            revisedBodyCodePointCount = draft.revisedBodyCodePointCount,
        ) as? ChapterRevisionResultDecisionV1.ContinueWithCandidate
            ?: throw IllegalArgumentException("The revision result cannot form a new candidate.")
        require(result.completedAutomaticRevisions == plan.revisionIndex)
        require(
            draft.candidateContentHashHistory ==
                plan.priorCandidateContentHashes + result.revisedCandidateContentHash,
        ) { "The revised candidate history does not match the frozen finite-revision plan." }

        require(stage.phase == GenerationPhase.REVISE_CHAPTER)
        val source = ChapterCandidateStageBindingV1.parseAndVerify(stage)
        require(
            source.role == ChapterCandidateArtifactRoleV1.BODY &&
                source.candidateChapterVersionId != draft.revisedCandidateChapterVersionId &&
                source.candidateContentHash == policyInput.currentCandidateContentHash &&
                source.chapterId == draft.chapterId && source.chapterIndex == draft.chapterIndex &&
                source.revisionIndex + 1 == result.completedAutomaticRevisions &&
                source.routeBindingHash == ChapterRevisionPolicyV1.routingBindingHash(policyInput) &&
                source.requestSourceBindingHash == draft.sourceBindingHash,
        ) { "The revision result no longer matches its persisted route and source candidate." }
        val resultBindingHash = resultBindingHash(source, draft, result.completedAutomaticRevisions)

        val next = ChapterCandidateStageBindingV1.stageSetup(
            jobId = job.jobId,
            stageId = draft.nextMemoryStageId,
            phase = GenerationPhase.EXTRACT_MEMORY,
            source = ChapterCandidateStageSourceV1(
                role = ChapterCandidateArtifactRoleV1.MEMORY,
                candidateChapterVersionId = draft.revisedCandidateChapterVersionId,
                candidateContentHash = draft.revisedCandidateContentHash,
                chapterId = draft.chapterId,
                chapterIndex = draft.chapterIndex,
                revisionIndex = result.completedAutomaticRevisions,
                predecessorStageId = stage.stageId,
                routeBindingHash = resultBindingHash,
            ),
            maxAttempts = draft.nextMemoryMaximumAttempts,
        )
        val seal = artifacts.seal(
            permit,
            ChapterCandidateArtifactSealDraftV1(
                role = ChapterCandidateArtifactRoleV1.BODY,
                candidateChapterVersionId = draft.revisedCandidateChapterVersionId,
                chapterId = draft.chapterId,
                chapterIndex = draft.chapterIndex,
                candidateContentHash = draft.revisedCandidateContentHash,
                canonicalOutputHash = draft.revisedCandidateContentHash,
                sourceBindingHash = draft.sourceBindingHash,
                revisionIndex = result.completedAutomaticRevisions,
                usage = draft.usage,
                nextStage = next,
                sealedAt = draft.sealedAt,
                routeBindingHash = resultBindingHash,
            ),
        )
        return ChapterRevisionCandidateResultV1(
            seal = seal,
            revisedCandidateChapterVersionId = draft.revisedCandidateChapterVersionId,
            revisedCandidateContentHash = result.revisedCandidateContentHash,
            completedAutomaticRevisions = result.completedAutomaticRevisions,
            candidateContentHashHistory = draft.candidateContentHashHistory.toList(),
            routeBindingHash = resultBindingHash,
        )
    }

    private fun validateDraft(draft: ChapterRevisionCandidateDraftV1) {
        require(
            listOf(
                draft.revisedCandidateChapterVersionId,
                draft.chapterId,
                draft.nextMemoryStageId,
            ).all(IDENTIFIER::matches),
        )
        require(draft.chapterIndex in 1..10_000)
        require(draft.revisedBodyCodePointCount >= 0)
        require(HASH.matches(draft.revisedCandidateContentHash) && HASH.matches(draft.sourceBindingHash))
        require(
            draft.candidateContentHashHistory.size in 2..3 &&
                draft.candidateContentHashHistory.all(HASH::matches) &&
                draft.candidateContentHashHistory.distinct().size == draft.candidateContentHashHistory.size &&
                draft.candidateContentHashHistory.last() == draft.revisedCandidateContentHash,
        )
        require(draft.nextMemoryMaximumAttempts in 1..16)
        require(draft.sealedAt >= 0L)
    }

    private fun verifiedBodyCodePointCount(permit: ValidatedOutputCommitPermit): Int = artifactStore.readBytes(
        artifactRefId = permit.artifactRefId,
        expectedType = ProtectedArtifactType.STREAM_DRAFT,
        maximumBytes = MAX_CHAPTER_BYTES,
    ).use { lease ->
        require(lease.descriptor.revision == permit.artifactRevision)
        lease.withBytes { bytes ->
            val body = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
            body.codePointCount(0, body.length)
        }
    }

    private fun resultBindingHash(
        source: ChapterCandidateStageSourceV1,
        draft: ChapterRevisionCandidateDraftV1,
        completedAutomaticRevisions: Int,
    ): String = MessageDigest.getInstance("SHA-256").digest(
        listOf(
            REVISION_RESULT_BINDING_VERSION,
            requireNotNull(source.routeBindingHash),
            requireNotNull(source.requestSourceBindingHash),
            source.candidateChapterVersionId,
            source.candidateContentHash,
            draft.revisedCandidateChapterVersionId,
            draft.revisedCandidateContentHash,
            draft.revisedBodyCodePointCount.toString(),
            completedAutomaticRevisions.toString(),
            draft.candidateContentHashHistory.joinToString(","),
        ).joinToString("\u0000").toByteArray(Charsets.UTF_8),
    ).joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val REVISION_RESULT_BINDING_VERSION = "zhijuan.chapter-revision-result-binding.v1"
        const val MAX_CHAPTER_BYTES = 4 * 1_024 * 1_024
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        val HASH = Regex("[0-9a-f]{64}")
    }
}
