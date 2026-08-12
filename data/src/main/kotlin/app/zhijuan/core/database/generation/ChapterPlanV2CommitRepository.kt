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
import app.zhijuan.core.task.StageEvent
import app.zhijuan.core.task.StageIdempotencyKey
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class ChapterPlanV2CommitDraft(
    val canonicalPlanJson: String,
    val canonicalPlanHash: String,
    val requestBindingHash: String,
    val expectationHash: String,
    val activationManifestHash: String,
    val activationHash: String,
    val policyManifestHash: String,
    val policyCompilationHash: String,
    val contextEvidenceHash: String,
    val initialDraftStageId: String,
    val initialDraftMaxAttempts: Int,
    val usage: FinalUsageCommit,
    val committedAt: Long,
) {
    override fun toString(): String = "ChapterPlanV2CommitDraft(plan=redacted, evidence=redacted)"
}

data class ChapterPlanV2CommitResult(
    val planStageId: String,
    val initialDraftStageId: String,
    val canonicalPlanHash: String,
    val replayed: Boolean,
)

/** Commits one validated plan and creates its unique initial DRAFT in the same Room transaction. */
class ChapterPlanV2CommitRepository(
    private val database: ZhijuanDatabase,
    private val artifactStore: AndroidProtectedArtifactStore,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun commit(
        permit: ValidatedOutputCommitPermit,
        draft: ChapterPlanV2CommitDraft,
    ): ChapterPlanV2CommitResult {
        validate(draft)
        require(draft.committedAt >= permit.validatedAt)
        val canonicalPlan = canonicalObject(draft.canonicalPlanJson, "Canonical chapter plan")
        require(canonicalPlan.toString() == draft.canonicalPlanJson) {
            "Chapter-plan v2 commit input is not canonical JSON."
        }
        require(sha256(draft.canonicalPlanJson) == draft.canonicalPlanHash)
        val output = outputReference(permit, draft)
        if (database.generationDao().findStage(permit.stageId)?.status != GenerationStageStatus.SUCCEEDED) {
            verifyArtifact(permit, draft.canonicalPlanHash)
        }
        return database.withTransaction {
            val dao = database.generationDao()
            val stage = requireNotNull(dao.findStage(permit.stageId)) { "Chapter-plan v2 Stage is missing." }
            val source = ChapterPlanV2StageBinding.parseAndVerify(stage)
            val attempt = requireNotNull(dao.findAttempt(permit.attemptId)) { "Chapter-plan v2 Attempt is missing." }
            val job = requireNotNull(dao.findJob(stage.jobId)) { "Chapter-plan v2 Job is missing." }
            val initialDraft = initialDraftStage(permit, draft, canonicalPlan, job.jobId)
            require(
                stage.targetType == GenerationTargetType.CHAPTER &&
                    attempt.stageId == stage.stageId && attempt.status == RequestAttemptStatus.SUCCEEDED &&
                    attempt.standardErrorCode == null && attempt.inputHash == draft.requestBindingHash &&
                    attempt.outputHash == permit.rawOutputHash && attempt.streamDraftRef == permit.artifactRefId &&
                    dao.attemptsForStage(stage.stageId).lastOrNull()?.attemptId == attempt.attemptId,
            ) { "Chapter-plan v2 Stage or Attempt evidence changed before commit." }
            val usage = requireNotNull(dao.findUsageForAttempt(attempt.attemptId)) {
                "Chapter-plan v2 Usage ledger is missing."
            }
            require(usage.bookId == job.bookId)
            requireFrozenEvidence(source, draft, canonicalPlan)

            if (stage.status == GenerationStageStatus.SUCCEEDED) {
                require(stage.outputReferenceJson == output) {
                    "Replayed chapter-plan v2 output differs from the committed plan."
                }
                val matchingDrafts = dao.stagesForJob(job.jobId).filter {
                    it.phase == GenerationPhase.DRAFT_CHAPTER && it.targetId == stage.targetId
                }
                require(matchingDrafts.size == 1 && matchingDrafts.single().matches(initialDraft)) {
                    "Replayed chapter-plan v2 commit does not have exactly one matching initial DRAFT."
                }
                require(usage.status == UsageLedgerStatus.FINAL)
                dao.recordUsage(attempt.attemptId, draft.usage.toFinalUpdate(draft.committedAt))
                return@withTransaction ChapterPlanV2CommitResult(
                    stage.stageId, initialDraft.stageId, draft.canonicalPlanHash, replayed = true,
                )
            }

            require(stage.status == GenerationStageStatus.COMMITTING)
            require(
                job.status in setOf(GenerationJobStatus.RUNNING, GenerationJobStatus.PAUSING) &&
                    job.currentStageId == stage.stageId,
            ) { "Chapter-plan v2 Job is not executing the committed Stage." }
            requireActiveLease(stage, permit.leaseToken, draft.committedAt)
            require(dao.findStage(initialDraft.stageId) == null) {
                "Initial DRAFT exists before its chapter plan was committed."
            }
            require(dao.stagesForJob(job.jobId).none {
                it.phase == GenerationPhase.DRAFT_CHAPTER && it.targetId == stage.targetId
            }) { "A chapter may have only one initial DRAFT Stage." }

            dao.insertStages(listOf(initialDraft))
            dao.recordUsage(attempt.attemptId, draft.usage.toFinalUpdate(draft.committedAt))
            check(
                GenerationStageStateMachine.transition(stage.status, StageEvent.COMMIT_SUCCEEDED) ==
                    GenerationStageStatus.SUCCEEDED,
            )
            if (dao.compareAndCommitStageOutput(
                    stageId = stage.stageId,
                    leaseOwnerId = permit.leaseToken.ownerId,
                    leaseAcquiredAt = permit.leaseToken.acquiredAt,
                    outputReferenceJson = output,
                    updatedAt = draft.committedAt,
                ) != 1
            ) throw StaleGenerationStateException("Chapter-plan v2 commit lost its Stage lease.")
            val activated = dao.compareAndSetStageStatus(
                stageId = initialDraft.stageId,
                expectedStatus = GenerationStageStatus.PENDING,
                nextStatus = GenerationStageStatus.READY,
                errorCode = null,
                nextRetryAt = null,
                updatedAt = draft.committedAt,
            )
            val advanced = if (job.status == GenerationJobStatus.PAUSING) {
                dao.compareAndPauseJobAfterStage(job.jobId, stage.stageId, initialDraft.stageId, draft.committedAt)
            } else {
                dao.compareAndAdvanceJobStage(job.jobId, stage.stageId, initialDraft.stageId, draft.committedAt)
            }
            if (activated != 1 || advanced != 1) {
                throw StaleGenerationStateException("Initial DRAFT activation lost a concurrent update.")
            }
            ChapterPlanV2CommitResult(stage.stageId, initialDraft.stageId, draft.canonicalPlanHash, replayed = false)
        }
    }

    private fun requireFrozenEvidence(
        source: ChapterPlanSourceV2,
        draft: ChapterPlanV2CommitDraft,
        plan: JsonObject,
    ) {
        require(source.frozen.expectationHash == draft.expectationHash)
        require(source.frozen.activationManifestHash == draft.activationManifestHash)
        require(source.frozen.activationHash == draft.activationHash)
        require(source.frozen.policyManifestHash == draft.policyManifestHash)
        require(source.frozen.policyCompilationHash == draft.policyCompilationHash)
        require(source.frozen.contextEvidenceHash == draft.contextEvidenceHash)
        require(plan.string("chapterId") == sourceExpectation(source).string("chapterId"))
        require(plan.int("chapterIndex") == source.targetChapterIndex)
        require(plan.string("activationHash") == source.frozen.activationHash)
        require(plan.string("policyCompilationHash") == source.frozen.policyCompilationHash)
        require(plan.string("contextEvidenceHash") == source.frozen.contextEvidenceHash)
        require(plan.string("contextContentHash") == sourceExpectation(source).string("contextContentHash"))
        require(plan.string("contextSourceManifestHash") == sourceExpectation(source).string("contextSourceManifestHash"))
    }

    private fun sourceExpectation(source: ChapterPlanSourceV2): JsonObject =
        canonicalObject(source.frozen.expectationJson, "Frozen chapter-plan expectation")

    private fun initialDraftStage(
        permit: ValidatedOutputCommitPermit,
        draft: ChapterPlanV2CommitDraft,
        plan: JsonObject,
        jobId: String,
    ): GenerationStageEntity {
        val input = JsonObject(linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "sourcePolicyVersion" to JsonPrimitive(INITIAL_DRAFT_SOURCE_POLICY_VERSION),
            "outputSchemaId" to JsonPrimitive("chapter-draft.v1"),
            "dependencyStageIds" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(permit.stageId))),
            "planStageId" to JsonPrimitive(permit.stageId),
            "planAttemptId" to JsonPrimitive(permit.attemptId),
            "planArtifactRefId" to JsonPrimitive(permit.artifactRefId),
            "planArtifactRevision" to JsonPrimitive(permit.artifactRevision),
            "planRawOutputHash" to JsonPrimitive(permit.rawOutputHash),
            "canonicalPlanHash" to JsonPrimitive(draft.canonicalPlanHash),
            "canonicalPlan" to plan,
            "requestBindingHash" to JsonPrimitive(draft.requestBindingHash),
            "expectationHash" to JsonPrimitive(draft.expectationHash),
            "activationManifestHash" to JsonPrimitive(draft.activationManifestHash),
            "activationHash" to JsonPrimitive(draft.activationHash),
            "policyManifestHash" to JsonPrimitive(draft.policyManifestHash),
            "policyCompilationHash" to JsonPrimitive(draft.policyCompilationHash),
            "contextEvidenceHash" to JsonPrimitive(draft.contextEvidenceHash),
        )).toString()
        require(input.toByteArray().size <= MAXIMUM_STAGE_INPUT_BYTES) {
            "Canonical plan is too large for an initial DRAFT source binding."
        }
        val hash = sha256(input)
        return GenerationStageEntity(
            stageId = draft.initialDraftStageId,
            jobId = jobId,
            phase = GenerationPhase.DRAFT_CHAPTER,
            targetType = GenerationTargetType.CHAPTER,
            targetId = plan.string("chapterId"),
            status = GenerationStageStatus.PENDING,
            inputVersionHash = hash,
            idempotencyKey = StageIdempotencyKey.create(
                jobId = jobId,
                phase = GenerationPhase.DRAFT_CHAPTER,
                targetId = plan.string("chapterId"),
                inputVersionHash = hash,
            ).value,
            maxAttempts = draft.initialDraftMaxAttempts,
            inputSourcesJson = input,
            createdAt = draft.committedAt,
            updatedAt = draft.committedAt,
        )
    }

    private fun GenerationStageEntity.matches(expected: GenerationStageEntity): Boolean =
        stageId == expected.stageId && phase == expected.phase && targetType == expected.targetType &&
            targetId == expected.targetId && inputVersionHash == expected.inputVersionHash &&
            maxAttempts == expected.maxAttempts && inputSourcesJson == expected.inputSourcesJson

    private fun verifyArtifact(permit: ValidatedOutputCommitPermit, canonicalPlanHash: String) {
        artifactStore.readBytes(
            permit.artifactRefId, ProtectedArtifactType.STREAM_DRAFT, MAXIMUM_PLAN_BYTES,
        ).use { lease ->
            require(lease.descriptor.revision == permit.artifactRevision)
            lease.withBytes { bytes ->
                require(sha256(bytes) == permit.rawOutputHash)
                val plan = canonicalObject(bytes.decodeToString(), "Validated chapter plan")
                require(sha256(plan.toString()) == canonicalPlanHash)
            }
        }
    }

    private fun outputReference(permit: ValidatedOutputCommitPermit, draft: ChapterPlanV2CommitDraft): String =
        JsonObject(linkedMapOf(
            "schemaVersion" to JsonPrimitive(2),
            "outputSchemaId" to JsonPrimitive(ChapterPlanV2StageBinding.OUTPUT_SCHEMA_ID),
            "attemptId" to JsonPrimitive(permit.attemptId),
            "artifactRefId" to JsonPrimitive(permit.artifactRefId),
            "artifactRevision" to JsonPrimitive(permit.artifactRevision),
            "rawOutputHash" to JsonPrimitive(permit.rawOutputHash),
            "canonicalPlanHash" to JsonPrimitive(draft.canonicalPlanHash),
            "requestBindingHash" to JsonPrimitive(draft.requestBindingHash),
            "nextStageId" to JsonPrimitive(draft.initialDraftStageId),
        )).toString()

    private fun requireActiveLease(stage: GenerationStageEntity, token: GenerationLeaseToken, at: Long) {
        require(stage.leaseOwnerId == token.ownerId && stage.leaseAcquiredAt == token.acquiredAt)
        val heartbeat = requireNotNull(stage.leaseHeartbeatAt)
        require(at >= stage.updatedAt && at >= heartbeat)
        if (leasePolicy.isExpired(heartbeat, at)) throw StaleGenerationStateException("Chapter-plan v2 lease expired.")
    }

    private fun validate(draft: ChapterPlanV2CommitDraft) {
        require(IDENTIFIER.matches(draft.initialDraftStageId))
        require(draft.initialDraftMaxAttempts in 1..4 && draft.committedAt >= 0)
        require(listOf(
            draft.canonicalPlanHash, draft.requestBindingHash, draft.expectationHash,
            draft.activationManifestHash, draft.activationHash, draft.policyManifestHash,
            draft.policyCompilationHash, draft.contextEvidenceHash,
        ).all(HASH::matches))
    }

    private fun FinalUsageCommit.toFinalUpdate(at: Long) = UsageUpdate(
        source, UsageLedgerStatus.FINAL, inputTokens, outputTokens, cachedTokens, reasoningTokens,
        totalTokens, currency, estimatedCostMicros, priceCatalogVersion, at,
    )

    private fun canonicalObject(value: String, label: String): JsonObject {
        val parsed = runCatching { STRICT_JSON.parseToJsonElement(value) as JsonObject }
            .getOrElse { throw IllegalArgumentException("$label is not a strict JSON object.") }
        return canonical(parsed) as JsonObject
    }

    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to canonical(it.value) })
        is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(value.map(::canonical))
        else -> value
    }

    private fun sha256(value: String) = sha256(value.toByteArray())
    private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(value).joinToString("") { "%02x".format(it) }

    companion object {
        const val INITIAL_DRAFT_SOURCE_POLICY_VERSION = "zhijuan.initial-chapter-draft-source.v1"
        private const val MAXIMUM_STAGE_INPUT_BYTES = 65_536
        private const val MAXIMUM_PLAN_BYTES = 64 * 1_024
        private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        private val HASH = Regex("[0-9a-f]{64}")
        private val STRICT_JSON = Json { isLenient = false; ignoreUnknownKeys = false }
    }
}

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
        ?: throw IllegalArgumentException("Chapter-plan v2 string field is missing or invalid: $key")

private fun JsonObject.int(key: String): Int =
    (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
        ?: throw IllegalArgumentException("Chapter-plan v2 integer field is missing or invalid: $key")
