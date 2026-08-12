package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ChapterCandidateArtifactRoleV1
import app.zhijuan.core.database.generation.ChapterFinalCandidateArtifactEvidenceV1
import app.zhijuan.core.database.generation.ChapterFinalCandidateCommitDraftV1
import app.zhijuan.core.database.generation.ChapterFinalCandidateCommitRepositoryV1
import app.zhijuan.core.database.generation.ChapterFinalCandidateCommitResultV1
import app.zhijuan.core.database.generation.ChapterFinalCandidateRecoveryV1
import app.zhijuan.core.database.generation.ChapterFinalCandidateRecoveryRepository
import app.zhijuan.core.database.generation.GenerationLeaseToken
import app.zhijuan.core.database.generation.GenerationStateRepository
import app.zhijuan.core.database.generation.StoredGenerationStageState
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.task.ChapterRevisionPolicyDecisionV1
import app.zhijuan.core.task.ChapterRevisionPolicyInputV1
import app.zhijuan.core.task.ChapterRevisionPolicyV1
import app.zhijuan.core.task.StageEvent
import java.security.MessageDigest

/**
 * The single local entry point that publishes a final Stage v3 candidate.
 *
 * It rehydrates the frozen recovery snapshot, strictly re-verifies the consistency
 * mapping snapshot and the actual protected artifacts, rebuilds every derived row
 * with the existing persistence mappers, re-runs the finite revision policy as a
 * hard gate, advances PREPARING -> COMMITTING only after all local validation has
 * passed (or deterministically resumes an interrupted COMMITTING stage), and finally
 * delegates to the sole authoritative SQLCipher commit transaction.
 *
 * This path never contacts the network, never creates attempts or usage, never
 * invokes a model, and never echoes chapter content, structured JSON, hashes, IDs
 * or model snapshots in any message.
 */
internal data class ChapterFinalCandidateCommitCoordinatorDependenciesV1(
    val loadRecovery: suspend (String) -> ChapterFinalCandidateRecoveryV1,
    val recoverArtifacts: (List<ChapterFinalCandidateArtifactEvidenceV1>) ->
        ChapterFinalCandidateArtifactRecoveryResultV1,
    val transitionToCommitting: suspend (String, GenerationLeaseToken, Long) ->
        StoredGenerationStageState,
    val commitDraft: suspend (String, GenerationLeaseToken, ChapterFinalCandidateCommitDraftV1) ->
        ChapterFinalCandidateCommitResultV1,
)

class ChapterFinalCandidateCommitCoordinatorV1 internal constructor(
    private val dependencies: ChapterFinalCandidateCommitCoordinatorDependenciesV1,
) {
    constructor(
        recoveryRepository: ChapterFinalCandidateRecoveryRepository,
        artifactRecovery: ChapterFinalCandidateArtifactRecoveryCoordinator,
        generationStateRepository: GenerationStateRepository,
        finalCommitRepository: ChapterFinalCandidateCommitRepositoryV1,
    ) : this(
        ChapterFinalCandidateCommitCoordinatorDependenciesV1(
            loadRecovery = recoveryRepository::load,
            recoverArtifacts = artifactRecovery::recover,
            transitionToCommitting = { stageId, leaseToken, updatedAt ->
                generationStateRepository.transitionStage(
                    stageId = stageId,
                    expectedStatus = GenerationStageStatus.PREPARING,
                    event = StageEvent.LOCAL_OUTPUT_READY,
                    updatedAt = updatedAt,
                    leaseToken = leaseToken,
                )
            },
            commitDraft = finalCommitRepository::commit,
        ),
    )
    /**
     * Commits the final candidate for [finalStageId] under [leaseToken].
     *
     * [requestedAt] must not move backwards relative to the persisted final stage;
     * it is also the derived-row mapping time for a PREPARING stage, while an
     * interrupted COMMITTING stage is resumed with its own persisted update time so
     * the rebuilt derived rows are byte-for-byte identical.
     */
    suspend fun commit(
        finalStageId: String,
        leaseToken: GenerationLeaseToken,
        requestedAt: Long,
    ): ChapterFinalCandidateCommitResultV1 {
        val recovered = dependencies.loadRecovery(finalStageId)
        require(recovered.finalStageId == finalStageId) {
            "Recovery snapshot does not match the requested final stage."
        }
        require(requestedAt >= recovered.finalStageUpdatedAt) {
            "Final commit time cannot move backwards."
        }
        val initialStatus = recovered.finalStageStatus
        require(
            initialStatus == GenerationStageStatus.PREPARING ||
                initialStatus == GenerationStageStatus.COMMITTING,
        ) {
            "Final commit requires a PREPARING or COMMITTING stage."
        }
        val source = recovered.source

        val snapshotJson = source.consistencyMappingSnapshotJson
        val snapshot = ChapterFinalConsistencyMappingSnapshotCodecV1.parseAndVerify(snapshotJson)
        require(
            ChapterFinalConsistencyMappingSnapshotCodecV1.contentHash(snapshotJson) ==
                source.consistencyMappingSnapshotContentHash,
        ) {
            "Consistency mapping snapshot content hash mismatch."
        }
        require(snapshot.consistencyRequestSourceBindingHash == source.consistencyRequestSourceBindingHash) {
            "Consistency mapping snapshot request binding mismatch."
        }

        val expectation = snapshot.expectation
        require(expectation.sourceChapterVersionId == source.candidateChapterVersionId) {
            "Candidate chapter version mismatch."
        }
        require(expectation.sourceChapterContentHash == source.candidateContentHash) {
            "Candidate content hash mismatch."
        }
        require(expectation.chapterId == source.chapterId) {
            "Candidate chapter identity mismatch."
        }
        require(expectation.chapterIndex == source.chapterIndex) {
            "Candidate chapter index mismatch."
        }
        require(snapshot.localReport.contentHash == source.candidateContentHash) {
            "Local consistency report content hash mismatch."
        }

        val artifacts = recovered.artifacts
        val artifactResult = dependencies.recoverArtifacts(artifacts)
        val candidateContent = artifactResult.candidateContent
        val bodyCodePointCount = candidateContent.codePointCount(0, candidateContent.length)
        val bodyByteCount = utf8Size(candidateContent)
        require(sha256(candidateContent) == source.candidateContentHash) {
            "Recovered candidate body hash mismatch."
        }
        require(bodyCodePointCount == snapshot.localReport.bodyCodePointCount) {
            "Recovered candidate body code point count mismatch."
        }
        require(bodyByteCount == snapshot.localReport.bodyByteCount) {
            "Recovered candidate body byte count mismatch."
        }
        require(bodyCodePointCount == expectation.bodyCodePointCount) {
            "Recovered candidate body expectation mismatch."
        }

        val candidate = ChapterCandidatePipelineIdentityV1(
            chapterVersionId = source.candidateChapterVersionId,
            chapterId = source.chapterId,
            chapterIndex = source.chapterIndex,
            contentHash = source.candidateContentHash,
            revisionIndex = source.revisionIndex,
            routeBindingHash = recovered.candidateRouteBindingHash,
        )
        require((source.revisionIndex == 0) == (candidate.routeBindingHash == null)) {
            "Candidate route binding does not match its revision index."
        }

        val postAnalysisStageId = optionalArtifactStageId(artifacts, ChapterCandidateArtifactRoleV1.POST_ANALYSIS)
        val memoryStageId = postAnalysisStageId ?: singleArtifactStageId(artifacts, ChapterCandidateArtifactRoleV1.MEMORY)
        val trackingStageId = postAnalysisStageId ?: singleArtifactStageId(artifacts, ChapterCandidateArtifactRoleV1.TRACKING)
        val consistencyStageId = postAnalysisStageId ?: singleArtifactStageId(artifacts, ChapterCandidateArtifactRoleV1.CONSISTENCY)

        val mappingTime = if (initialStatus == GenerationStageStatus.PREPARING) {
            requestedAt
        } else {
            recovered.finalStageUpdatedAt
        }

        val memory = ChapterMemoryExtractionPersistenceMapper.map(
            memory = artifactResult.memory,
            spec = ChapterMemoryExtractionMappingSpec(
                bookId = recovered.bookId,
                generationStageId = memoryStageId,
                modelSnapshotJson = recovered.memoryModelSnapshotJson,
                createdAt = mappingTime,
            ),
        )
        val tracking = ChapterTrackingProjectionPersistenceMapper.map(
            tracking = artifactResult.tracking,
            spec = ChapterTrackingProjectionMappingSpec(
                bookId = recovered.bookId,
                generationStageId = trackingStageId,
                modelSnapshotJson = recovered.trackingModelSnapshotJson,
                createdAt = mappingTime,
            ),
        )
        val consistency = ChapterConsistencyPersistenceMapperV1.map(
            local = snapshot.localReport,
            model = artifactResult.consistency,
            expectation = snapshot.expectation,
            scene = snapshot.sceneContract,
            spec = ChapterConsistencyMappingSpecV1(
                bookId = recovered.bookId,
                generationStageId = consistencyStageId,
                modelSnapshotJson = recovered.consistencyModelSnapshotJson,
                createdAt = mappingTime,
            ),
        )
        require(consistency.gate.decision == ChapterConsistencyGateDecisionV1.ACCEPT_CANDIDATE) {
            "Consistency gate did not accept the recovered candidate."
        }

        val policyInput = ChapterRevisionPolicyInputV1(
            currentCandidateContentHash = source.candidateContentHash,
            candidateContentHashHistory = source.candidateContentHashHistory,
            bodyCodePointCount = bodyCodePointCount,
            minimumBodyCodePoints = snapshot.minimumBodyCodePoints,
            completedAutomaticRevisions = source.revisionIndex,
            totalRevisionAttemptsUsed = snapshot.totalRevisionAttemptsUsed,
            stageMaximumAttempts = snapshot.revisionStageMaximumAttempts,
            sceneContract = snapshot.sceneContract,
            issues = ChapterRevisionRequestFactoryV1.issuesFrom(consistency.gate),
        )
        val policyDecision = ChapterRevisionPolicyV1.evaluate(policyInput)
        require(policyDecision is ChapterRevisionPolicyDecisionV1.AcceptCandidate) {
            "Recovered candidate did not pass the finite revision policy."
        }
        require(policyDecision.candidateContentHash == source.candidateContentHash) {
            "Finite revision policy accepted a different candidate."
        }
        require(policyDecision.maximumAutomaticRevisions == source.maximumAutomaticRevisions) {
            "Finite revision policy limit does not match the frozen source."
        }
        require(ChapterRevisionPolicyV1.routingBindingHash(policyInput) == source.routeBindingHash) {
            "Final route binding does not match the frozen source."
        }

        val draft = ChapterFinalCandidateCommitDraftMapperV1.map(
            ChapterFinalCandidateCommitMappingSpecV1(
                candidate = candidate,
                expectedCurrentVersionId = source.expectedCurrentVersionId,
                candidateContent = candidateContent,
                maximumAutomaticRevisions = source.maximumAutomaticRevisions,
                candidateContentHashHistory = source.candidateContentHashHistory,
                artifacts = artifacts,
                memory = memory,
                tracking = tracking,
                consistency = consistency,
                committedAt = mappingTime,
            ),
        )

        if (initialStatus == GenerationStageStatus.PREPARING) {
            val transitioned = dependencies.transitionToCommitting(finalStageId, leaseToken, mappingTime)
            require(
                transitioned.stageId == finalStageId &&
                    transitioned.status == GenerationStageStatus.COMMITTING &&
                    transitioned.leaseToken == leaseToken &&
                    transitioned.updatedAt == mappingTime,
            ) {
                "Final stage transition evidence is stale."
            }
        }

        return dependencies.commitDraft(finalStageId, leaseToken, draft)
    }

    private fun singleArtifactStageId(
        artifacts: List<ChapterFinalCandidateArtifactEvidenceV1>,
        role: ChapterCandidateArtifactRoleV1,
    ): String {
        val matches = artifacts.filter { it.role == role }
        require(matches.size == 1) {
            "Final commit requires exactly one recovered artifact per role."
        }
        return matches.single().stageId
    }

    private fun optionalArtifactStageId(
        artifacts: List<ChapterFinalCandidateArtifactEvidenceV1>,
        role: ChapterCandidateArtifactRoleV1,
    ): String? {
        val matches = artifacts.filter { it.role == role }
        require(matches.size <= 1)
        return matches.singleOrNull()?.stageId
    }

    private fun sha256(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        } finally {
            bytes.fill(0)
        }
    }

    private fun utf8Size(value: String): Int {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return try {
            bytes.size
        } finally {
            bytes.fill(0)
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
}
