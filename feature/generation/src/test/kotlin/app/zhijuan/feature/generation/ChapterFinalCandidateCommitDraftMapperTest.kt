package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterCandidateArtifactRoleV1
import app.zhijuan.core.database.generation.ChapterFinalCandidateArtifactEvidenceV1
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.ChapterTrackingProjectionEntity
import app.zhijuan.core.database.memory.ConsistencyReportEntity
import app.zhijuan.core.model.DerivedDataStatus
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ChapterFinalCandidateCommitDraftMapperTest {
    @Test
    fun validUnrevisedCandidateMapsEveryFieldWithFixedArtifactOrder() {
        val body = "chapter body"
        val candidate = candidate(body)
        val memoryHash = sha256("memory-output")
        val trackingHash = sha256("tracking-output")
        val reportJson = "{}"
        val reportHash = sha256(reportJson)
        val consistencyHash = sha256("consistency-output")
        val memory = memoryDraft(candidate, contentHash = memoryHash)
        val tracking = trackingDraft(
            candidate,
            trackingStageId = "stage.tracking.1",
            contentHash = trackingHash,
        )
        val consistency = consistencyDraft(
            candidate,
            consistencyStageId = "stage.consistency.1",
            issuesJson = reportJson,
            reportHash = reportHash,
        )
        val artifacts = listOf(
            evidence(ChapterCandidateArtifactRoleV1.CONSISTENCY, consistencyHash),
            evidence(ChapterCandidateArtifactRoleV1.BODY, candidate.contentHash),
            evidence(ChapterCandidateArtifactRoleV1.MEMORY, memoryHash),
            evidence(ChapterCandidateArtifactRoleV1.TRACKING, trackingHash),
        )

        val draft = ChapterFinalCandidateCommitDraftMapperV1.map(
            spec(candidate, body, artifacts, memory, tracking, consistency),
        )

        assertEquals(candidate.chapterVersionId, draft.chapterVersionId)
        assertEquals(candidate.chapterId, draft.chapterId)
        assertEquals(body, draft.content)
        assertEquals(0, draft.revisionIndex)
        assertEquals(2, draft.maximumAutomaticRevisions)
        assertEquals(listOf(candidate.contentHash), draft.candidateContentHashHistory)
        assertEquals(
            listOf(
                ChapterCandidateArtifactRoleV1.BODY,
                ChapterCandidateArtifactRoleV1.MEMORY,
                ChapterCandidateArtifactRoleV1.TRACKING,
                ChapterCandidateArtifactRoleV1.CONSISTENCY,
            ),
            draft.artifacts.map { it.role },
        )
        assertSame(memory.summary, draft.summary)
        assertSame(memory.entityEvents, draft.entityEvents)
        assertSame(memory.canonFacts, draft.canonFacts)
        assertEquals(memoryHash, draft.memoryOutputContentHash)
        assertSame(tracking.projection, draft.trackingProjection)
        assertSame(tracking.timelineEvents, draft.timelineEvents)
        assertSame(tracking.newForeshadows, draft.newForeshadows)
        assertSame(tracking.existingForeshadowUpdates, draft.existingForeshadowUpdates)
        assertSame(tracking.foreshadowTransitions, draft.foreshadowTransitions)
        assertEquals(trackingHash, draft.trackingOutputContentHash)
        assertSame(consistency.report, draft.consistencyReport)
        assertEquals(reportHash, draft.consistencyReportContentHash)
        assertEquals(consistencyHash, draft.consistencyOutputContentHash)
        assertEquals(COMMIT_AT, draft.committedAt)
    }

    @Test
    fun reviseCandidateGateIsRejected() {
        val body = "chapter body"
        val candidate = candidate(body)
        val memoryHash = sha256("memory-output")
        val trackingHash = sha256("tracking-output")
        val consistency = consistencyDraft(
            candidate,
            consistencyStageId = "stage.consistency.1",
            decision = ChapterConsistencyGateDecisionV1.REVISE_CANDIDATE,
        )

        assertThrows(IllegalArgumentException::class.java) {
            ChapterFinalCandidateCommitDraftMapperV1.map(
                spec(
                    candidate,
                    body,
                    artifacts(candidate, memoryHash, trackingHash, sha256("consistency-output")),
                    memoryDraft(candidate, contentHash = memoryHash),
                    trackingDraft(candidate, trackingStageId = "stage.tracking.1", contentHash = trackingHash),
                    consistency,
                ),
            )
        }
    }

    @Test
    fun bodyHashMismatchWithCurrentCandidateIsRejected() {
        val candidate = candidate("chapter body")
        val memoryHash = sha256("memory-output")
        val trackingHash = sha256("tracking-output")
        val consistencyHash = sha256("consistency-output")

        assertThrows(IllegalArgumentException::class.java) {
            ChapterFinalCandidateCommitDraftMapperV1.map(
                spec(
                    candidate,
                    "a different chapter body",
                    artifacts(candidate, memoryHash, trackingHash, consistencyHash),
                    memoryDraft(candidate, contentHash = memoryHash),
                    trackingDraft(candidate, trackingStageId = "stage.tracking.1", contentHash = trackingHash),
                    consistencyDraft(candidate, consistencyStageId = "stage.consistency.1"),
                ),
            )
        }
    }

    @Test
    fun mismatchedMissingOrDuplicatedEvidenceIsRejected() {
        val body = "chapter body"
        val candidate = candidate(body)
        val memoryHash = sha256("memory-output")
        val trackingHash = sha256("tracking-output")
        val consistencyHash = sha256("consistency-output")
        val memory = memoryDraft(candidate, contentHash = memoryHash)
        val tracking = trackingDraft(
            candidate,
            trackingStageId = "stage.tracking.1",
            contentHash = trackingHash,
        )
        val consistency = consistencyDraft(candidate, consistencyStageId = "stage.consistency.1")
        val valid = artifacts(candidate, memoryHash, trackingHash, consistencyHash)

        val mismatched = valid.map { evidence ->
            if (evidence.role == ChapterCandidateArtifactRoleV1.MEMORY) {
                evidence.copy(canonicalOutputHash = sha256("other-memory-output"))
            } else {
                evidence
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterFinalCandidateCommitDraftMapperV1.map(spec(candidate, body, mismatched, memory, tracking, consistency))
        }

        val missing = valid.filterNot { it.role == ChapterCandidateArtifactRoleV1.TRACKING }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterFinalCandidateCommitDraftMapperV1.map(spec(candidate, body, missing, memory, tracking, consistency))
        }

        val duplicated = valid + evidence(ChapterCandidateArtifactRoleV1.CONSISTENCY, consistencyHash)
        assertThrows(IllegalArgumentException::class.java) {
            ChapterFinalCandidateCommitDraftMapperV1.map(spec(candidate, body, duplicated, memory, tracking, consistency))
        }
    }

    @Test
    fun consistencyReportFromAnotherBookIsRejected() {
        val body = "chapter body"
        val candidate = candidate(body)
        val memoryHash = sha256("memory-output")
        val trackingHash = sha256("tracking-output")
        val consistency = consistencyDraft(
            candidate,
            consistencyStageId = "stage.consistency.1",
            bookId = "book.other",
        )

        assertThrows(IllegalArgumentException::class.java) {
            ChapterFinalCandidateCommitDraftMapperV1.map(
                spec(
                    candidate,
                    body,
                    artifacts(candidate, memoryHash, trackingHash, sha256("consistency-output")),
                    memoryDraft(candidate, contentHash = memoryHash),
                    trackingDraft(candidate, trackingStageId = "stage.tracking.1", contentHash = trackingHash),
                    consistency,
                ),
            )
        }
    }

    @Test
    fun trackingProjectionOutputHashMismatchIsRejected() {
        val body = "chapter body"
        val candidate = candidate(body)
        val memoryHash = sha256("memory-output")
        val trackingHash = sha256("tracking-output")

        assertThrows(IllegalArgumentException::class.java) {
            ChapterFinalCandidateCommitDraftMapperV1.map(
                spec(
                    candidate,
                    body,
                    artifacts(candidate, memoryHash, trackingHash, sha256("consistency-output")),
                    memoryDraft(candidate, contentHash = memoryHash),
                    trackingDraft(
                        candidate,
                        trackingStageId = "stage.tracking.1",
                        contentHash = trackingHash,
                        projectionOutputContentHash = sha256("other-tracking-output"),
                    ),
                    consistencyDraft(candidate, consistencyStageId = "stage.consistency.1"),
                ),
            )
        }
    }

    @Test
    fun consistencyReportContentHashMismatchIsRejected() {
        val body = "chapter body"
        val candidate = candidate(body)
        val memoryHash = sha256("memory-output")
        val trackingHash = sha256("tracking-output")
        val consistency = consistencyDraft(
            candidate,
            consistencyStageId = "stage.consistency.1",
            issuesJson = "{}",
            reportHash = sha256("different-report"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ChapterFinalCandidateCommitDraftMapperV1.map(
                spec(
                    candidate,
                    body,
                    artifacts(candidate, memoryHash, trackingHash, sha256("consistency-output")),
                    memoryDraft(candidate, contentHash = memoryHash),
                    trackingDraft(candidate, trackingStageId = "stage.tracking.1", contentHash = trackingHash),
                    consistency,
                ),
            )
        }
    }

    private fun candidate(bodyText: String) = ChapterCandidatePipelineIdentityV1(
        chapterVersionId = "chapter.version.candidate.0",
        chapterId = "chapter.1",
        chapterIndex = 1,
        contentHash = sha256(bodyText),
        revisionIndex = 0,
        routeBindingHash = null,
    )

    private fun spec(
        candidate: ChapterCandidatePipelineIdentityV1,
        bodyText: String,
        artifacts: List<ChapterFinalCandidateArtifactEvidenceV1>,
        memory: ChapterMemoryDerivedDraft,
        tracking: ChapterTrackingProjectionDerivedDraft,
        consistency: ChapterConsistencyDerivedDraftV1,
    ) = ChapterFinalCandidateCommitMappingSpecV1(
        candidate = candidate,
        expectedCurrentVersionId = null,
        candidateContent = bodyText,
        maximumAutomaticRevisions = 2,
        candidateContentHashHistory = listOf(sha256(bodyText)),
        artifacts = artifacts,
        memory = memory,
        tracking = tracking,
        consistency = consistency,
        committedAt = COMMIT_AT,
    )

    private fun artifacts(
        candidate: ChapterCandidatePipelineIdentityV1,
        memoryHash: String,
        trackingHash: String,
        consistencyHash: String,
    ) = listOf(
        evidence(ChapterCandidateArtifactRoleV1.BODY, candidate.contentHash),
        evidence(ChapterCandidateArtifactRoleV1.MEMORY, memoryHash),
        evidence(ChapterCandidateArtifactRoleV1.TRACKING, trackingHash),
        evidence(ChapterCandidateArtifactRoleV1.CONSISTENCY, consistencyHash),
    )

    private fun evidence(role: ChapterCandidateArtifactRoleV1, canonicalOutputHash: String) =
        ChapterFinalCandidateArtifactEvidenceV1(
            role = role,
            stageId = "stage.${role.name.lowercase()}.1",
            attemptId = "attempt.${role.name.lowercase()}.1",
            artifactRefId = "artifact.${role.name.lowercase()}.1",
            artifactRevision = 1,
            rawOutputHash = sha256("raw-${role.name}"),
            canonicalOutputHash = canonicalOutputHash,
            sourceBindingHash = sha256("binding-${role.name}"),
        )

    private fun memoryDraft(candidate: ChapterCandidatePipelineIdentityV1, contentHash: String) =
        ChapterMemoryDerivedDraft(
            summary = ChapterSummaryEntity(
                chapterSummaryId = "memory.summary.1",
                bookId = "book.1",
                chapterVersionId = candidate.chapterVersionId,
                chapterIndex = candidate.chapterIndex,
                schemaVersion = 1,
                summaryJson = "{}",
                importance = 50,
                status = DerivedDataStatus.VALID,
                modelSnapshotJson = "{}",
                createdAt = COMMIT_AT,
                updatedAt = COMMIT_AT,
            ),
            entityEvents = emptyList(),
            canonFacts = emptyList(),
            extractionContentHash = contentHash,
        )

    private fun trackingDraft(
        candidate: ChapterCandidatePipelineIdentityV1,
        trackingStageId: String,
        contentHash: String,
        projectionOutputContentHash: String = contentHash,
    ) = ChapterTrackingProjectionDerivedDraft(
        projection = ChapterTrackingProjectionEntity(
            projectionId = "tracking.tracking-projection.1",
            bookId = "book.1",
            chapterVersionId = candidate.chapterVersionId,
            chapterIndex = candidate.chapterIndex,
            generationStageId = trackingStageId,
            sourceChapterContentHash = candidate.contentHash,
            sourceMemorySnapshotHash = sha256("memory-snapshot"),
            priorForeshadowSnapshotHash = sha256("foreshadow-snapshot"),
            outputContentHash = projectionOutputContentHash,
            payloadHash = sha256("payload"),
            status = DerivedDataStatus.VALID,
            modelSnapshotJson = "{}",
            timelineEventCount = 0,
            foreshadowTransitionCount = 0,
            createdAt = COMMIT_AT,
            updatedAt = COMMIT_AT,
        ),
        timelineEvents = emptyList(),
        newForeshadows = emptyList(),
        existingForeshadowUpdates = emptyList(),
        foreshadowTransitions = emptyList(),
        trackingContentHash = contentHash,
    )

    private fun consistencyDraft(
        candidate: ChapterCandidatePipelineIdentityV1,
        consistencyStageId: String,
        decision: ChapterConsistencyGateDecisionV1 = ChapterConsistencyGateDecisionV1.ACCEPT_CANDIDATE,
        bookId: String = "book.1",
        issuesJson: String = "{}",
        reportHash: String = sha256(issuesJson),
    ) = ChapterConsistencyDerivedDraftV1(
        report = ConsistencyReportEntity(
            consistencyReportId = "consistency.report.1",
            bookId = bookId,
            targetChapterVersionId = candidate.chapterVersionId,
            targetChapterIndex = candidate.chapterIndex,
            generationStageId = consistencyStageId,
            checkerVersion = "zhijuan.consistency-combined.v1",
            issuesJson = issuesJson,
            status = DerivedDataStatus.VALID,
            createdAt = COMMIT_AT,
            updatedAt = COMMIT_AT,
        ),
        gate = ChapterConsistencyGateResultV1(decision = decision, issues = emptyList()),
        reportContentHash = reportHash,
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val COMMIT_AT = 1_000L
    }
}
