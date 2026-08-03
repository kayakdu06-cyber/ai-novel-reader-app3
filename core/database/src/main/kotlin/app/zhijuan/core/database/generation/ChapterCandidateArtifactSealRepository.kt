package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.security.ProtectedArtifactType
import app.zhijuan.core.task.GenerationStageStateMachine
import app.zhijuan.core.task.StageIdempotencyKey
import app.zhijuan.core.task.StageEvent
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

enum class ChapterCandidateArtifactRoleV1(
    val schemaId: String,
    internal val allowedPhases: Set<GenerationPhase>,
) {
    BODY("chapter-draft.v1", setOf(GenerationPhase.DRAFT_CHAPTER, GenerationPhase.REVISE_CHAPTER)),
    MEMORY("chapter-memory.v1", setOf(GenerationPhase.EXTRACT_MEMORY)),
    TRACKING("chapter-story-tracking.v1", setOf(GenerationPhase.EXTRACT_MEMORY)),
    CONSISTENCY("chapter-consistency-report.v1", setOf(GenerationPhase.CHECK_CONSISTENCY)),
}

data class ChapterCandidateStageSourceV1(
    val role: ChapterCandidateArtifactRoleV1,
    val candidateChapterVersionId: String,
    val candidateContentHash: String,
    val chapterId: String,
    val chapterIndex: Int,
    val revisionIndex: Int,
    val predecessorStageId: String,
)

/** Frozen source envelope for remote stages whose candidate ChapterVersion is not formal yet. */
object ChapterCandidateStageBindingV1 {
    fun stageSetup(
        jobId: String,
        stageId: String,
        phase: GenerationPhase,
        source: ChapterCandidateStageSourceV1,
        maxAttempts: Int,
    ): GenerationStageSetup {
        require(phase in source.role.allowedPhases)
        val inputSources = inputSources(source)
        val inputHash = sha256(listOf(SOURCE_POLICY_VERSION, inputSources).joinToString("\u0000"))
        return GenerationStageSetup(
            stageId = stageId,
            phase = phase,
            targetType = GenerationTargetType.CHAPTER,
            targetId = source.chapterId,
            inputVersionHash = inputHash,
            idempotencyKey = StageIdempotencyKey.create(jobId, phase, source.chapterId, inputHash).value,
            maxAttempts = maxAttempts,
            inputSourcesJson = inputSources,
        )
    }

    internal fun isBound(stage: GenerationStageEntity, role: ChapterCandidateArtifactRoleV1): Boolean =
        runCatching { parseAndVerify(stage).role == role }.getOrDefault(false)

    internal fun parseAndVerify(stage: GenerationStageEntity): ChapterCandidateStageSourceV1 {
        val root = runCatching { STRICT_JSON.parseToJsonElement(stage.inputSourcesJson) as JsonObject }
            .getOrElse { throw IllegalArgumentException("Candidate Stage source is invalid JSON.") }
        require(root.keys == ROOT_KEYS)
        require(root.string("sourcePolicyVersion") == SOURCE_POLICY_VERSION)
        require(root.string("pipelineVersion") == ChapterCandidateArtifactSealRepositoryV1.PIPELINE_VERSION)
        val role = ChapterCandidateArtifactRoleV1.valueOf(root.string("artifactRole"))
        val source = ChapterCandidateStageSourceV1(
            role = role,
            candidateChapterVersionId = root.string("candidateChapterVersionId"),
            candidateContentHash = root.string("candidateContentHash"),
            chapterId = root.string("chapterId"),
            chapterIndex = root.intValue("chapterIndex"),
            revisionIndex = root.intValue("revisionIndex"),
            predecessorStageId = root.string("predecessorStageId"),
        )
        require(stage.phase in role.allowedPhases && stage.targetType == GenerationTargetType.CHAPTER)
        require(stage.targetId == source.chapterId)
        require(listOf(source.candidateChapterVersionId, source.chapterId, source.predecessorStageId).all(IDENTIFIER::matches))
        require(HASH.matches(source.candidateContentHash) && source.chapterIndex in 1..10_000 && source.revisionIndex in 0..2)
        require(
            stage.inputVersionHash == sha256(
                listOf(SOURCE_POLICY_VERSION, stage.inputSourcesJson).joinToString("\u0000"),
            ),
        )
        return source
    }

    private fun inputSources(source: ChapterCandidateStageSourceV1): String = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "sourcePolicyVersion" to JsonPrimitive(SOURCE_POLICY_VERSION),
            "pipelineVersion" to JsonPrimitive(ChapterCandidateArtifactSealRepositoryV1.PIPELINE_VERSION),
            "artifactRole" to JsonPrimitive(source.role.name),
            "candidateChapterVersionId" to JsonPrimitive(source.candidateChapterVersionId),
            "candidateContentHash" to JsonPrimitive(source.candidateContentHash),
            "chapterId" to JsonPrimitive(source.chapterId),
            "chapterIndex" to JsonPrimitive(source.chapterIndex),
            "revisionIndex" to JsonPrimitive(source.revisionIndex),
            "predecessorStageId" to JsonPrimitive(source.predecessorStageId),
        ),
    ).toString()

    private fun JsonObject.string(key: String): String = requireNotNull(this[key]).jsonPrimitive.content
    private fun JsonObject.intValue(key: String): Int = requireNotNull(this[key]).jsonPrimitive.int
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private const val SOURCE_POLICY_VERSION = "zhijuan.chapter-candidate-stage-source.v1"
    private val ROOT_KEYS = setOf(
        "schemaVersion", "sourcePolicyVersion", "pipelineVersion", "artifactRole",
        "candidateChapterVersionId", "candidateContentHash", "chapterId", "chapterIndex",
        "revisionIndex", "predecessorStageId",
    )
    private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
    private val HASH = Regex("[0-9a-f]{64}")
    private val STRICT_JSON = Json { isLenient = false; ignoreUnknownKeys = false }
}

data class ChapterCandidateArtifactSealDraftV1(
    val role: ChapterCandidateArtifactRoleV1,
    val candidateChapterVersionId: String,
    val chapterId: String,
    val chapterIndex: Int,
    val candidateContentHash: String,
    val canonicalOutputHash: String,
    val sourceBindingHash: String,
    val revisionIndex: Int,
    val usage: FinalUsageCommit,
    val nextStage: GenerationStageSetup,
    val sealedAt: Long,
) {
    override fun toString(): String =
        "ChapterCandidateArtifactSealDraftV1(role=$role, chapterIndex=$chapterIndex, revisionIndex=$revisionIndex, content=redacted)"
}

data class ChapterCandidateArtifactSealResultV1(
    val stageId: String,
    val nextStageId: String,
    val role: ChapterCandidateArtifactRoleV1,
    val replayed: Boolean,
)

/**
 * Seals one validated remote candidate artifact and advances to the next frozen Stage
 * without publishing any ChapterVersion or derived memory rows.
 */
class ChapterCandidateArtifactSealRepositoryV1(
    private val database: ZhijuanDatabase,
    private val artifactStore: AndroidProtectedArtifactStore,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun seal(
        permit: ValidatedOutputCommitPermit,
        draft: ChapterCandidateArtifactSealDraftV1,
    ): ChapterCandidateArtifactSealResultV1 {
        validateDraft(draft)
        require(draft.sealedAt >= permit.validatedAt)
        val outputReference = outputReferenceJson(permit, draft)
        if (database.generationDao().findStage(permit.stageId)?.status != GenerationStageStatus.SUCCEEDED) {
            verifyArtifact(permit, draft)
        }
        return database.withTransaction {
            val dao = database.generationDao()
            val stage = requireNotNull(dao.findStage(permit.stageId)) { "Candidate Stage no longer exists." }
            val attempt = requireNotNull(dao.findAttempt(permit.attemptId)) { "Candidate Attempt no longer exists." }
            val job = requireNotNull(dao.findJob(stage.jobId)) { "Candidate Job no longer exists." }
            require(
                stage.phase in draft.role.allowedPhases &&
                    stage.targetType == GenerationTargetType.CHAPTER &&
                    stage.targetId == draft.chapterId &&
                    job.bookId == requireNotNull(dao.findBookIdForStage(stage.stageId)) &&
                    attempt.stageId == stage.stageId &&
                    attempt.status == RequestAttemptStatus.SUCCEEDED &&
                    attempt.standardErrorCode == null &&
                    attempt.inputHash == draft.sourceBindingHash &&
                    attempt.outputHash == permit.rawOutputHash &&
                    attempt.streamDraftRef == permit.artifactRefId &&
                    dao.attemptsForStage(stage.stageId).lastOrNull()?.attemptId == attempt.attemptId,
            ) { "Candidate Stage or Attempt evidence changed before sealing." }
            require(dao.findUsageForAttempt(attempt.attemptId)?.bookId == job.bookId) {
                "Candidate Usage ledger is missing or belongs to another book."
            }

            if (stage.status == GenerationStageStatus.SUCCEEDED) {
                require(stage.outputReferenceJson == outputReference) {
                    "Completed candidate Stage does not match the replayed seal."
                }
                val next = requireNotNull(dao.findStage(draft.nextStage.stageId)) {
                    "Replayed candidate seal lost its next Stage."
                }
                require(next.matches(stage.jobId, draft.nextStage))
                dao.recordUsage(attempt.attemptId, draft.usage.toFinalUpdate(draft.sealedAt))
                return@withTransaction ChapterCandidateArtifactSealResultV1(
                    stage.stageId,
                    next.stageId,
                    draft.role,
                    replayed = true,
                )
            }

            require(stage.status == GenerationStageStatus.COMMITTING) {
                "A candidate artifact can only seal from COMMITTING."
            }
            require(
                job.status in setOf(GenerationJobStatus.RUNNING, GenerationJobStatus.PAUSING) &&
                    job.currentStageId == stage.stageId,
            ) { "Candidate Job is not running the Stage being sealed." }
            requireActiveLease(stage, permit.leaseToken, draft.sealedAt)
            require(dao.findStage(draft.nextStage.stageId) == null) {
                "A new candidate next Stage already exists before its source was sealed."
            }
            val next = draft.nextStage.toEntity(stage.jobId, draft.sealedAt)
            requireNextCandidateBinding(stage, draft, next)
            dao.insertStages(listOf(next))
            dao.recordUsage(attempt.attemptId, draft.usage.toFinalUpdate(draft.sealedAt))
            check(
                GenerationStageStateMachine.transition(stage.status, StageEvent.COMMIT_SUCCEEDED) ==
                    GenerationStageStatus.SUCCEEDED,
            )
            if (
                dao.compareAndCommitStageOutput(
                    stageId = stage.stageId,
                    leaseOwnerId = permit.leaseToken.ownerId,
                    leaseAcquiredAt = permit.leaseToken.acquiredAt,
                    outputReferenceJson = outputReference,
                    updatedAt = draft.sealedAt,
                ) != 1
            ) throw StaleGenerationStateException("Candidate seal lost the current Stage lease.")
            if (
                dao.compareAndSetStageStatus(
                    stageId = next.stageId,
                    expectedStatus = GenerationStageStatus.PENDING,
                    nextStatus = GenerationStageStatus.READY,
                    errorCode = null,
                    nextRetryAt = null,
                    updatedAt = draft.sealedAt,
                ) != 1 ||
                (if (job.status == GenerationJobStatus.PAUSING) {
                    dao.compareAndPauseJobAfterStage(
                        jobId = job.jobId,
                        expectedCurrentStageId = stage.stageId,
                        nextStageId = next.stageId,
                        updatedAt = draft.sealedAt,
                    )
                } else {
                    dao.compareAndAdvanceJobStage(
                        jobId = job.jobId,
                        expectedCurrentStageId = stage.stageId,
                        nextStageId = next.stageId,
                        updatedAt = draft.sealedAt,
                    )
                }) != 1
            ) throw StaleGenerationStateException("Candidate next-Stage activation lost a concurrent update.")
            ChapterCandidateArtifactSealResultV1(
                stage.stageId,
                next.stageId,
                draft.role,
                replayed = false,
            )
        }
    }

    private fun validateDraft(draft: ChapterCandidateArtifactSealDraftV1) {
        require(
            listOf(draft.candidateChapterVersionId, draft.chapterId, draft.nextStage.stageId, draft.nextStage.targetId)
                .all(IDENTIFIER::matches),
        )
        require(draft.chapterIndex in 1..10_000 && draft.revisionIndex in 0..2)
        require(
            listOf(draft.candidateContentHash, draft.canonicalOutputHash, draft.sourceBindingHash).all(HASH::matches),
        )
        require(draft.sealedAt >= 0L)
        require(draft.nextStage.targetType == GenerationTargetType.CHAPTER && draft.nextStage.targetId == draft.chapterId)
        require(draft.nextStage.maxAttempts in 1..16)
        require(draft.nextStage.inputVersionHash.length in 1..256)
        require(draft.nextStage.idempotencyKey.length in 1..256)
        requireJsonObject(draft.nextStage.inputSourcesJson)
    }

    private fun requireNextCandidateBinding(
        currentStage: GenerationStageEntity,
        draft: ChapterCandidateArtifactSealDraftV1,
        next: GenerationStageEntity,
    ) {
        if (next.phase == GenerationPhase.COMMIT_CHAPTER) {
            require(draft.role == ChapterCandidateArtifactRoleV1.CONSISTENCY) {
                "Only a consistency artifact can advance to final commit."
            }
            return
        }
        val source = ChapterCandidateStageBindingV1.parseAndVerify(next)
        require(
            source.candidateChapterVersionId == draft.candidateChapterVersionId &&
                source.candidateContentHash == draft.candidateContentHash &&
                source.chapterId == draft.chapterId && source.chapterIndex == draft.chapterIndex &&
                source.revisionIndex == draft.revisionIndex && source.predecessorStageId == currentStage.stageId,
        ) { "Candidate next Stage does not bind the sealed source artifact." }
        val allowedNextRole = when (draft.role) {
            ChapterCandidateArtifactRoleV1.BODY -> ChapterCandidateArtifactRoleV1.MEMORY
            ChapterCandidateArtifactRoleV1.MEMORY -> ChapterCandidateArtifactRoleV1.TRACKING
            ChapterCandidateArtifactRoleV1.TRACKING -> ChapterCandidateArtifactRoleV1.CONSISTENCY
            ChapterCandidateArtifactRoleV1.CONSISTENCY -> ChapterCandidateArtifactRoleV1.BODY
        }
        require(source.role == allowedNextRole) { "Candidate Stage order is invalid." }
    }

    private fun verifyArtifact(
        permit: ValidatedOutputCommitPermit,
        draft: ChapterCandidateArtifactSealDraftV1,
    ) {
        artifactStore.readBytes(
            artifactRefId = permit.artifactRefId,
            expectedType = ProtectedArtifactType.STREAM_DRAFT,
            maximumBytes = if (draft.role == ChapterCandidateArtifactRoleV1.BODY) MAX_CHAPTER_BYTES else MAX_STRUCTURED_BYTES,
        ).use { lease ->
            require(lease.descriptor.revision == permit.artifactRevision)
            lease.withBytes { bytes ->
                require(sha256(bytes) == permit.rawOutputHash) {
                    "Candidate artifact changed after validation."
                }
                val canonicalHash = if (draft.role == ChapterCandidateArtifactRoleV1.BODY) {
                    permit.rawOutputHash
                } else {
                    val document = runCatching {
                        STRICT_JSON.parseToJsonElement(bytes.decodeToString()) as JsonObject
                    }.getOrElse { throw IllegalArgumentException("Validated candidate artifact is not a JSON object.") }
                    sha256(document.toString())
                }
                require(canonicalHash == draft.canonicalOutputHash) {
                    "Candidate mapping no longer matches the validated artifact."
                }
                if (draft.role == ChapterCandidateArtifactRoleV1.BODY) {
                    require(canonicalHash == draft.candidateContentHash) {
                        "Sealed chapter body does not match the frozen candidate hash."
                    }
                }
            }
        }
    }

    private fun outputReferenceJson(
        permit: ValidatedOutputCommitPermit,
        draft: ChapterCandidateArtifactSealDraftV1,
    ): String = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "pipelineVersion" to JsonPrimitive(PIPELINE_VERSION),
            "artifactRole" to JsonPrimitive(draft.role.name),
            "outputSchemaId" to JsonPrimitive(draft.role.schemaId),
            "attemptId" to JsonPrimitive(permit.attemptId),
            "artifactRefId" to JsonPrimitive(permit.artifactRefId),
            "artifactRevision" to JsonPrimitive(permit.artifactRevision),
            "rawOutputHash" to JsonPrimitive(permit.rawOutputHash),
            "canonicalOutputHash" to JsonPrimitive(draft.canonicalOutputHash),
            "sourceBindingHash" to JsonPrimitive(draft.sourceBindingHash),
            "candidateChapterVersionId" to JsonPrimitive(draft.candidateChapterVersionId),
            "candidateContentHash" to JsonPrimitive(draft.candidateContentHash),
            "chapterId" to JsonPrimitive(draft.chapterId),
            "chapterIndex" to JsonPrimitive(draft.chapterIndex),
            "revisionIndex" to JsonPrimitive(draft.revisionIndex),
            "nextStageId" to JsonPrimitive(draft.nextStage.stageId),
        ),
    ).toString()

    private fun GenerationStageSetup.toEntity(jobId: String, createdAt: Long) = GenerationStageEntity(
        stageId = stageId,
        jobId = jobId,
        phase = phase,
        targetType = targetType,
        targetId = targetId,
        status = GenerationStageStatus.PENDING,
        inputVersionHash = inputVersionHash,
        idempotencyKey = idempotencyKey,
        maxAttempts = maxAttempts,
        inputSourcesJson = inputSourcesJson,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun GenerationStageEntity.matches(jobId: String, setup: GenerationStageSetup): Boolean =
        this.jobId == jobId && stageId == setup.stageId && phase == setup.phase &&
            targetType == setup.targetType && targetId == setup.targetId &&
            inputVersionHash == setup.inputVersionHash && idempotencyKey == setup.idempotencyKey &&
            maxAttempts == setup.maxAttempts && inputSourcesJson == setup.inputSourcesJson

    private fun requireActiveLease(stage: GenerationStageEntity, token: GenerationLeaseToken, at: Long) {
        require(stage.leaseOwnerId == token.ownerId && stage.leaseAcquiredAt == token.acquiredAt)
        val heartbeatAt = requireNotNull(stage.leaseHeartbeatAt)
        require(at >= stage.updatedAt && at >= heartbeatAt)
        if (leasePolicy.isExpired(heartbeatAt, at)) {
            throw StaleGenerationStateException("Candidate Stage lease expired before sealing.")
        }
    }

    private fun FinalUsageCommit.toFinalUpdate(updatedAt: Long) = UsageUpdate(
        source = source,
        status = UsageLedgerStatus.FINAL,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cachedTokens = cachedTokens,
        reasoningTokens = reasoningTokens,
        totalTokens = totalTokens,
        currency = currency,
        estimatedCostMicros = estimatedCostMicros,
        priceCatalogVersion = priceCatalogVersion,
        updatedAt = updatedAt,
    )

    private fun requireJsonObject(value: String) {
        require(value.length in 2..65_536)
        runCatching { STRICT_JSON.parseToJsonElement(value) as JsonObject }
            .getOrElse { throw IllegalArgumentException("Candidate next-Stage source must be a JSON object.") }
    }

    private fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    companion object {
        const val PIPELINE_VERSION = "zhijuan.chapter-candidate-pipeline.v1"
        private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        private val HASH = Regex("[0-9a-f]{64}")
        private val STRICT_JSON = Json { isLenient = false; ignoreUnknownKeys = false }
        private const val MAX_CHAPTER_BYTES = 4 * 1_024 * 1_024
        private const val MAX_STRUCTURED_BYTES = 512 * 1_024
    }
}
