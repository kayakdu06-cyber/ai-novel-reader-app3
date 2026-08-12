package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterCandidateArtifactRoleV1
import app.zhijuan.core.database.generation.ChapterFinalCandidateArtifactEvidenceV1
import app.zhijuan.core.database.generation.ChapterFinalCandidateCommitDraftV1
import java.security.MessageDigest

/**
 * Deterministic assembly of an accepted candidate, its artifact evidence and the
 * already derived database-row drafts into the final commit draft.
 *
 * This mapper performs no IO, no artifact decryption and no Provider parsing. It
 * only proves source consistency before the final repository publishes, and never
 * rewrites the timestamps carried by the derived rows.
 */
data class ChapterFinalCandidateCommitMappingSpecV1(
    val candidate: ChapterCandidatePipelineIdentityV1,
    val expectedCurrentVersionId: String?,
    val candidateContent: String,
    val maximumAutomaticRevisions: Int,
    val candidateContentHashHistory: List<String>,
    val artifacts: List<ChapterFinalCandidateArtifactEvidenceV1>,
    val memory: ChapterMemoryDerivedDraft,
    val tracking: ChapterTrackingProjectionDerivedDraft,
    val consistency: ChapterConsistencyDerivedDraftV1,
    val committedAt: Long,
) {
    override fun toString(): String =
        "ChapterFinalCandidateCommitMappingSpecV1(revisionIndex=${candidate.revisionIndex}, " +
            "artifactCount=${artifacts.size}, content=redacted)"
}

object ChapterFinalCandidateCommitDraftMapperV1 {
    fun map(spec: ChapterFinalCandidateCommitMappingSpecV1): ChapterFinalCandidateCommitDraftV1 {
        val candidate = spec.candidate
        val memory = spec.memory
        val tracking = spec.tracking
        val consistency = spec.consistency

        require(spec.candidateContent.isNotBlank()) {
            "Final candidate content must not be blank."
        }
        require(sha256(spec.candidateContent) == candidate.contentHash) {
            "Final candidate content does not hash to its frozen identity."
        }
        require(spec.maximumAutomaticRevisions in 1..2) {
            "Automatic revision budget must be one or two."
        }
        require(candidate.revisionIndex <= spec.maximumAutomaticRevisions) {
            "Current revision exceeds the automatic revision budget."
        }
        val history = spec.candidateContentHashHistory
        require(history.size == candidate.revisionIndex + 1) {
            "Candidate history must contain exactly one hash per revision."
        }
        require(history.all { HASH.matches(it) }) {
            "Candidate history must contain lowercase SHA-256 hashes only."
        }
        require(history.distinct().size == history.size) {
            "Candidate history must not repeat a hash."
        }
        require(history.last() == candidate.contentHash) {
            "Candidate history must end at the current candidate hash."
        }
        require(spec.expectedCurrentVersionId == null || IDENTIFIER.matches(spec.expectedCurrentVersionId)) {
            "Expected current version identifier is malformed."
        }
        require(spec.committedAt >= 0L) {
            "Final commit time must not be negative."
        }

        val artifactsByRole = spec.artifacts.groupBy { it.role }
        val roles = artifactsByRole.keys
        require(roles in setOf(LEGACY_ROLES, MERGED_ROLES) && roles.all { artifactsByRole[it]?.size == 1 }) {
            "Candidate evidence must contain one supported artifact chain."
        }
        val bodyEvidence = artifactsByRole.getValue(ChapterCandidateArtifactRoleV1.BODY).single()
        val postEvidence = artifactsByRole[ChapterCandidateArtifactRoleV1.POST_ANALYSIS]?.single()
        val memoryEvidence = postEvidence ?: artifactsByRole.getValue(ChapterCandidateArtifactRoleV1.MEMORY).single()
        val trackingEvidence = postEvidence ?: artifactsByRole.getValue(ChapterCandidateArtifactRoleV1.TRACKING).single()
        val consistencyEvidence = postEvidence ?: artifactsByRole.getValue(ChapterCandidateArtifactRoleV1.CONSISTENCY).single()
        require(bodyEvidence.canonicalOutputHash == candidate.contentHash) {
            "Body evidence does not match the current candidate hash."
        }
        require(memoryEvidence.canonicalOutputHash == memory.extractionContentHash) {
            "Memory evidence does not match the derived memory hash."
        }
        require(trackingEvidence.canonicalOutputHash == tracking.trackingContentHash) {
            "Tracking evidence does not match the derived tracking hash."
        }
        require(consistency.gate.decision == ChapterConsistencyGateDecisionV1.ACCEPT_CANDIDATE) {
            "Only an accepted consistency gate may publish a candidate."
        }
        require(
            memory.summary.chapterVersionId == candidate.chapterVersionId &&
                memory.summary.chapterIndex == candidate.chapterIndex,
        ) {
            "Memory summary does not belong to the current candidate."
        }
        require(
            tracking.projection.chapterVersionId == candidate.chapterVersionId &&
                tracking.projection.chapterIndex == candidate.chapterIndex &&
                tracking.projection.sourceChapterContentHash == candidate.contentHash,
        ) {
            "Tracking projection does not belong to the current candidate."
        }
        require(
            consistency.report.targetChapterVersionId == candidate.chapterVersionId &&
                consistency.report.targetChapterIndex == candidate.chapterIndex,
        ) {
            "Consistency report does not target the current candidate."
        }
        require(tracking.projection.generationStageId == trackingEvidence.stageId) {
            "Tracking projection stage does not match its evidence."
        }
        require(consistency.report.generationStageId == consistencyEvidence.stageId) {
            "Consistency report stage does not match its evidence."
        }
        val commonBookId = memory.summary.bookId
        require(
            tracking.projection.bookId == commonBookId && consistency.report.bookId == commonBookId,
        ) {
            "Final derived data does not belong to one book."
        }
        require(
            memory.entityEvents.all {
                it.bookId == commonBookId && it.sourceChapterVersionId == candidate.chapterVersionId
            } && memory.canonFacts.all {
                it.bookId == commonBookId && it.sourceChapterVersionId == candidate.chapterVersionId
            },
        ) {
            "Memory rows do not belong to the current candidate."
        }
        require(
            tracking.timelineEvents.all {
                it.bookId == commonBookId && it.sourceChapterVersionId == candidate.chapterVersionId
            } && tracking.newForeshadows.all {
                it.bookId == commonBookId && it.sourceChapterVersionId == candidate.chapterVersionId
            } && tracking.foreshadowTransitions.all {
                it.bookId == commonBookId && it.sourceChapterVersionId == candidate.chapterVersionId
            },
        ) {
            "Tracking rows do not belong to the current candidate."
        }
        require(tracking.projection.outputContentHash == tracking.trackingContentHash) {
            "Tracking projection output hash does not match the derived tracking result."
        }
        require(sha256(consistency.report.issuesJson) == consistency.reportContentHash) {
            "Consistency report hash does not match the derived report."
        }

        val committedAt = spec.committedAt
        require(memory.summary.createdAt == committedAt && memory.summary.updatedAt == committedAt) {
            "Memory summary time does not match the final commit time."
        }
        require(memory.entityEvents.all { it.createdAt == committedAt }) {
            "Entity event time does not match the final commit time."
        }
        require(memory.canonFacts.all { it.createdAt == committedAt }) {
            "Canon fact time does not match the final commit time."
        }
        require(
            tracking.projection.createdAt == committedAt && tracking.projection.updatedAt == committedAt,
        ) {
            "Tracking projection time does not match the final commit time."
        }
        require(tracking.timelineEvents.all { it.createdAt == committedAt }) {
            "Timeline event time does not match the final commit time."
        }
        require(tracking.newForeshadows.all { it.createdAt == committedAt && it.updatedAt == committedAt }) {
            "Foreshadow time does not match the final commit time."
        }
        require(tracking.foreshadowTransitions.all { it.createdAt == committedAt }) {
            "Foreshadow transition time does not match the final commit time."
        }
        require(
            consistency.report.createdAt == committedAt && consistency.report.updatedAt == committedAt,
        ) {
            "Consistency report time does not match the final commit time."
        }

        return ChapterFinalCandidateCommitDraftV1(
            chapterVersionId = candidate.chapterVersionId,
            chapterId = candidate.chapterId,
            expectedCurrentVersionId = spec.expectedCurrentVersionId,
            content = spec.candidateContent,
            revisionIndex = candidate.revisionIndex,
            maximumAutomaticRevisions = spec.maximumAutomaticRevisions,
            candidateContentHashHistory = history,
            artifacts = spec.artifacts.sortedBy { it.role.ordinal },
            summary = memory.summary,
            entityEvents = memory.entityEvents,
            canonFacts = memory.canonFacts,
            memoryOutputContentHash = memory.extractionContentHash,
            trackingProjection = tracking.projection,
            timelineEvents = tracking.timelineEvents,
            newForeshadows = tracking.newForeshadows,
            existingForeshadowUpdates = tracking.existingForeshadowUpdates,
            foreshadowTransitions = tracking.foreshadowTransitions,
            trackingOutputContentHash = tracking.trackingContentHash,
            consistencyReport = consistency.report,
            consistencyReportContentHash = consistency.reportContentHash,
            consistencyOutputContentHash = consistencyEvidence.canonicalOutputHash,
            committedAt = committedAt,
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
    private val HASH = Regex("[0-9a-f]{64}")
    private val LEGACY_ROLES = setOf(
        ChapterCandidateArtifactRoleV1.BODY,
        ChapterCandidateArtifactRoleV1.MEMORY,
        ChapterCandidateArtifactRoleV1.TRACKING,
        ChapterCandidateArtifactRoleV1.CONSISTENCY,
    )
    private val MERGED_ROLES = setOf(
        ChapterCandidateArtifactRoleV1.BODY,
        ChapterCandidateArtifactRoleV1.POST_ANALYSIS,
    )
}
